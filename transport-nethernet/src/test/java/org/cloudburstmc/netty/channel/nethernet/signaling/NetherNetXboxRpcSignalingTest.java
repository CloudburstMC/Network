package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetXboxRpcSignalingTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryReceiptsDoNotGenerateMoreDeliveryReceipts(boolean requestHasId) {
        try (Signaling signaling = new Signaling()) {
            signaling.receive(NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY, new JsonObject(), requestHasId);

            if (requestHasId) {
                JsonObject response = signaling.readOutbound();
                assertEquals("request-1", response.get("id").getAsString());
                assertTrue(response.get("result").isJsonNull());
                assertFalse(response.has("method"));
            }
            assertNull(signaling.transport.readOutbound(), "A receipt must not trigger Signaling_SendClientMessage");
        }
    }

    @Test
    void webRtcMessagesStillReachTheirHandlerAndReceiveAReceipt() {
        try (Signaling signaling = new Signaling()) {
            AtomicReference<String> received = new AtomicReference<>();
            signaling.setSignalHandler(42, received::set);
            JsonObject params = new JsonObject();
            params.addProperty("netherNetId", "123");
            params.addProperty("message", "CANDIDATEADD 42 candidate");

            signaling.receive(NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC, params, true);

            assertEquals("CANDIDATEADD 42 candidate", received.get());
            JsonObject response = signaling.readOutbound();
            assertEquals("request-1", response.get("id").getAsString());
            JsonObject receipt = signaling.readOutbound();
            assertEquals(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, receipt.get("method").getAsString());
            JsonObject delivery = JsonParser.parseString(receipt.getAsJsonObject("params").get("message").getAsString())
                    .getAsJsonObject();
            assertEquals(NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY, delivery.get("method").getAsString());
            assertNull(signaling.transport.readOutbound());
        }
    }

    @Test
    void oldTurnRequestFailureCannotFailTheReplacementConnect() {
        try (Signaling signaling = new Signaling()) {
            signaling.install(signaling.transport);
            signaling.transport.pipeline().fireUserEventTriggered(
                    WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            JsonObject turnRequest = signaling.readOutbound();
            assertEquals(NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH, turnRequest.get("method").getAsString());

            EmbeddedChannel replacement = new EmbeddedChannel(signaling);
            try {
                CompletableFuture<?> pending = signaling.install(replacement);

                signaling.transport.close();

                assertFalse(pending.isDone());
                assertTrue(replacement.isOpen());
                assertTrue(signaling.getIceServers().isEmpty());
            } finally {
                replacement.finishAndReleaseAll();
            }
        }
    }

    @Test
    void unansweredRpcExpiresAndItsLateErrorDoesNotReportNotFound() throws Exception {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setNotFoundHandler(message -> notFound.incrementAndGet());
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());
            String id = signaling.readOutbound().get("id").getAsString();

            signaling.advance(19);
            assertFalse(future.isDone());
            assertEquals(1, signaling.pendingRequestCount());
            signaling.advance(1);

            assertInstanceOf(TimeoutException.class, assertThrows(CompletionException.class, future::join).getCause());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
            JsonObject error = new JsonObject();
            error.addProperty("message", "Player not registered");
            signaling.respond(signaling.transport, id, "error", error);
            assertEquals(0, notFound.get());
            assertTrue(signaling.transport.isOpen());
        }
    }

    @Test
    void successfulReplyCancelsItsDeadline() throws Exception {
        try (Signaling signaling = new Signaling()) {
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());
            String id = signaling.readOutbound().get("id").getAsString();
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);

            signaling.respond(signaling.transport, id, "result", result);

            assertEquals(result, future.join());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
            signaling.advance(20);
            assertFalse(future.isCompletedExceptionally());
        }
    }

    @Test
    void explicitCurrentNotFoundResponseStillNotifiesOnce() throws Exception {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setNotFoundHandler(message -> notFound.incrementAndGet());
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());
            String id = signaling.readOutbound().get("id").getAsString();
            JsonObject error = new JsonObject();
            error.addProperty("message", "Player not registered");

            signaling.respond(signaling.transport, id, "error", error);
            signaling.respond(signaling.transport, id, "error", error);

            assertTrue(future.isCompletedExceptionally());
            assertEquals(1, notFound.get());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
        }
    }

    @Test
    void writeFailureCancelsTheDeadlineWithoutWaitingForSocketClose() throws Exception {
        try (Signaling signaling = new Signaling()) {
            IllegalStateException failure = new IllegalStateException("write rejected");
            AtomicReference<Object> written = new AtomicReference<>();
            signaling.transport.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    written.set(message);
                    ReferenceCountUtil.release(message);
                    promise.setFailure(failure);
                }
            });

            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());

            assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
            assertEquals(0, ((TextWebSocketFrame) written.get()).refCnt());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
            assertTrue(signaling.transport.isOpen());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeCancelsPendingRpcDeadlines(boolean closeSignaling) throws Exception {
        try (Signaling signaling = new Signaling()) {
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());
            signaling.readOutbound();

            if (closeSignaling) {
                signaling.close();
            } else {
                signaling.transport.close();
            }

            assertInstanceOf(ClosedChannelException.class, assertThrows(CompletionException.class, future::join).getCause());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
        }
    }

    @Test
    void responsesCannotCrossSocketOwnership() throws Exception {
        try (Signaling signaling = new Signaling()) {
            CompletableFuture<JsonObject> previousRequest = signaling.sendJsonRpcRequest("previous", new JsonObject());
            String previousId = signaling.readOutbound().get("id").getAsString();
            EmbeddedChannel replacement = new EmbeddedChannel(signaling);
            replacement.freezeTime();
            try {
                signaling.install(replacement);
                CompletableFuture<JsonObject> currentRequest = signaling.sendJsonRpcRequest("current", new JsonObject());
                String currentId = signaling.readOutbound(replacement).get("id").getAsString();

                signaling.respond(signaling.transport, currentId, "result", new JsonObject());
                signaling.respond(replacement, previousId, "result", new JsonObject());

                assertFalse(previousRequest.isDone());
                assertFalse(currentRequest.isDone());
                signaling.respond(replacement, currentId, "result", new JsonObject());
                assertFalse(currentRequest.isCompletedExceptionally());
                assertTrue(currentRequest.isDone());
                signaling.transport.close();
                assertInstanceOf(ClosedChannelException.class,
                        assertThrows(CompletionException.class, previousRequest::join).getCause());
                assertEquals(0, signaling.pendingRequestCount());
                assertEquals(-1, replacement.runScheduledPendingTasks());
            } finally {
                replacement.finishAndReleaseAll();
            }
        }
    }

    @Test
    void malformedErrorCompletesTheRequestAndCancelsItsDeadline() throws Exception {
        try (Signaling signaling = new Signaling()) {
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject());
            String id = signaling.readOutbound().get("id").getAsString();

            signaling.respond(signaling.transport, id, "error", new JsonArray());

            assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, future::join).getCause());
            assertEquals(0, signaling.pendingRequestCount());
            assertEquals(-1, signaling.transport.runScheduledPendingTasks());
        }
    }

    @Test
    void oldTimeoutAndRecurringTasksCannotAffectTheReplacementConnect() {
        try (Signaling signaling = new Signaling()) {
            signaling.install(signaling.transport);
            signaling.transport.pipeline().fireUserEventTriggered(
                    WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            signaling.readOutbound();
            EmbeddedChannel replacement = new EmbeddedChannel(signaling);
            try {
                CompletableFuture<?> pending = signaling.install(replacement);

                signaling.advance(30);

                assertFalse(pending.isDone());
                assertTrue(replacement.isOpen());
                assertTrue(signaling.getIceServers().isEmpty());
                assertNull(signaling.transport.readOutbound());
                assertNull(replacement.readOutbound());
            } finally {
                replacement.finishAndReleaseAll();
            }
        }
    }

    @Test
    void routeProbeTimeoutDoesNotDisableLaterProbesOrReportNotFound() {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setNotFoundHandler(message -> notFound.incrementAndGet());
            signaling.xboxToken = "MCToken unused." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"pmid\":\"self\"}".getBytes(StandardCharsets.UTF_8)) + ".unused";
            signaling.install(signaling.transport);
            signaling.transport.pipeline().fireUserEventTriggered(
                    WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            String turnId = signaling.readOutbound().get("id").getAsString();
            signaling.respond(signaling.transport, turnId, "result", new JsonObject());

            signaling.advance(30);
            assertEquals(1, signaling.readRouteProbes());
            signaling.advance(20);
            assertEquals(0, notFound.get());
            assertTrue(signaling.isRouteAlive(Long.MAX_VALUE));
            signaling.advance(10);
            assertEquals(1, signaling.readRouteProbes(), "A lost RPC reply must not disable the self-probe");
        }
    }

    private static final class Signaling extends NetherNetXboxRpcSignaling implements AutoCloseable {
        private final EmbeddedChannel transport;

        private Signaling() {
            super("local", "MCToken unused");
            transport = new EmbeddedChannel(this);
            transport.freezeTime();
            channel = transport;
        }

        private synchronized CompletableFuture<List<IceServerInfo>> install(EmbeddedChannel socket) {
            channel = socket;
            connectFuture = new CompletableFuture<>();
            return connectFuture;
        }

        private void receive(String method, JsonObject params, boolean requestHasId) {
            JsonObject inner = new JsonObject();
            inner.addProperty("jsonrpc", "2.0");
            inner.addProperty("method", method);
            inner.add("params", params);
            JsonObject message = new JsonObject();
            message.addProperty("From", "peer");
            message.addProperty("Id", "message-1");
            message.addProperty("Message", inner.toString());
            JsonArray messages = new JsonArray();
            messages.add(message);
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("method", NetherNetConstants.XBOX_RPC_METHOD_RECEIVE_MESSAGE);
            if (requestHasId) {
                request.addProperty("id", "request-1");
            }
            request.add("params", messages);
            transport.writeInbound(new TextWebSocketFrame(request.toString()));
        }

        private JsonObject readOutbound() {
            return readOutbound(transport);
        }

        private JsonObject readOutbound(EmbeddedChannel socket) {
            TextWebSocketFrame frame = socket.readOutbound();
            assertNotNull(frame);
            try {
                return JsonParser.parseString(frame.text()).getAsJsonObject();
            } finally {
                frame.release();
            }
        }

        private void respond(EmbeddedChannel socket, String id, String field, com.google.gson.JsonElement value) {
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.add(field, value);
            socket.writeInbound(new TextWebSocketFrame(response.toString()));
        }

        private void advance(long seconds) {
            transport.advanceTimeBy(seconds, TimeUnit.SECONDS);
            transport.runScheduledPendingTasks();
            transport.runPendingTasks();
        }

        private int pendingRequestCount() throws Exception {
            Field field = NetherNetXboxRpcSignaling.class.getDeclaredField("pendingRequests");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(this)).size();
        }

        private int readRouteProbes() {
            int count = 0;
            Object message;
            while ((message = transport.readOutbound()) != null) {
                try {
                    if (!(message instanceof TextWebSocketFrame frame)) {
                        continue;
                    }
                    JsonObject request = JsonParser.parseString(frame.text()).getAsJsonObject();
                    if (!NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE.equals(request.get("method").getAsString())) {
                        continue;
                    }
                    JsonObject inner = JsonParser.parseString(request.getAsJsonObject("params").get("message").getAsString())
                            .getAsJsonObject();
                    if (NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE.equals(inner.get("method").getAsString())) {
                        count++;
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
            return count;
        }

        @Override
        public void close() {
            try {
                super.close();
                transport.finishAndReleaseAll();
            } finally {
                eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }
}
