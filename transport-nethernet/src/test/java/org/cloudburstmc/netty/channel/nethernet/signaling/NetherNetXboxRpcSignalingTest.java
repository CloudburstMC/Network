/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetXboxRpcSignalingTest {

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
            signaling.setFailureHandler(message -> notFound.incrementAndGet());
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject(), true);
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
    void notFoundResponseOnTheCurrentSocketIsReportedOnce() throws Exception {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setFailureHandler(message -> notFound.incrementAndGet());
            CompletableFuture<JsonObject> future = signaling.sendJsonRpcRequest("test", new JsonObject(), true);
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
    void closeFailsPendingRequestsAndCancelsTheirDeadlines(boolean closeSignaling) throws Exception {
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
    void responsesCannotCrossSockets() throws Exception {
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
    void turnCredentialsAreFetchedAgainOnALongLivedSocket() {
        try (Signaling signaling = new Signaling()) {
            CompletableFuture<?> pending = signaling.install(signaling.transport);
            signaling.transport.pipeline().fireUserEventTriggered(
                    WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            String first = signaling.readOutbound().get("id").getAsString();
            signaling.respond(signaling.transport, first, "result", turnServers("turn:first.invalid"));
            assertTrue(pending.isDone());

            signaling.advance(30 * 60);

            List<JsonObject> refreshes = signaling.readRequests(NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH);
            assertEquals(1, refreshes.size());
            signaling.respond(signaling.transport, refreshes.get(0).get("id").getAsString(), "result",
                    turnServers("turn:refreshed.invalid"));
            assertEquals(List.of("turn:refreshed.invalid"), signaling.getIceServers().get(0).urls());
        }
    }

    @Test
    void routeProbeTimeoutDoesNotDisableLaterProbesOrReportNotFound() {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setFailureHandler(message -> notFound.incrementAndGet());
            signaling.connectAs("self");

            signaling.advance(30);
            assertEquals(1, signaling.readRouteProbes().size());
            signaling.advance(20);
            assertEquals(0, notFound.get());
            assertTrue(signaling.isRouteAlive(Long.MAX_VALUE));
            signaling.advance(10);
            assertEquals(1, signaling.readRouteProbes().size(), "A lost RPC reply must not disable the probe");
        }
    }

    @Test
    void aProbeThatComesBackProvesTheRouteUntilOneIsRefused() {
        try (Signaling signaling = new Signaling()) {
            signaling.connectAs("self");
            signaling.advance(30);
            String first = signaling.readRouteProbes().get(0).get("id").getAsString();
            signaling.respond(signaling.transport, first, "result", new JsonObject());

            signaling.deliver(Signaling.message("self", NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE,
                    new JsonObject()), true);

            assertEquals("request-1", signaling.readOutbound().get("id").getAsString());
            assertNull(signaling.transport.readOutbound(), "A returned probe gets no delivery notification");
            assertTrue(signaling.isRouteAlive(60_000));

            signaling.advance(30);
            String second = signaling.readRouteProbes().get(0).get("id").getAsString();
            JsonObject error = new JsonObject();
            error.addProperty("message", "Player not registered");
            signaling.respond(signaling.transport, second, "error", error);

            assertFalse(signaling.isRouteAlive(60_000));
        }
    }

    @Test
    void aProbeRefusedOnANewSocketDisablesTheCheckInsteadOfFailingIt() {
        try (Signaling signaling = new Signaling()) {
            signaling.connectAs("self");
            signaling.advance(30);
            String first = signaling.readRouteProbes().get(0).get("id").getAsString();
            JsonObject error = new JsonObject();
            error.addProperty("message", "Self addressed messages are not supported");

            signaling.respond(signaling.transport, first, "error", error);

            assertTrue(signaling.isRouteAlive(60_000));
            signaling.advance(30);
            assertTrue(signaling.readRouteProbes().isEmpty());
        }
    }

    @Test
    void aTokenWithoutAPlayerIdSendsNoProbes() {
        try (Signaling signaling = new Signaling()) {
            signaling.connect();

            signaling.advance(60);

            assertTrue(signaling.readRouteProbes().isEmpty());
            assertTrue(signaling.isRouteAlive(60_000));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryNotificationsGetNoDeliveryNotification(boolean requestHasId) {
        try (Signaling signaling = new Signaling()) {
            JsonObject params = new JsonObject();
            params.addProperty("messageId", "message-0");

            signaling.deliver(Signaling.message("peer", NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY, params),
                    requestHasId);

            if (requestHasId) {
                JsonObject response = signaling.readOutbound();
                assertEquals("request-1", response.get("id").getAsString());
                assertTrue(response.get("result").isJsonNull());
                assertFalse(response.has("method"));
            }
            assertNull(signaling.transport.readOutbound(), "A delivery notification must not be acknowledged");
        }
    }

    @Test
    void webRtcMessagesStillReachTheirHandlerAndGetADeliveryNotification() {
        try (Signaling signaling = new Signaling()) {
            AtomicReference<String> received = new AtomicReference<>();
            signaling.setSignalHandler("42", received::set);

            signaling.deliver(Signaling.message("peer", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC,
                    webRtc("CANDIDATEADD 42 candidate")), true);

            assertEquals("CANDIDATEADD 42 candidate", received.get());
            assertEquals("request-1", signaling.readOutbound().get("id").getAsString());
            JsonObject notification = signaling.readOutbound();
            assertEquals(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, notification.get("method").getAsString());
            JsonObject delivery = JsonParser.parseString(notification.getAsJsonObject("params").get("message").getAsString())
                    .getAsJsonObject();
            assertEquals(NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY, delivery.get("method").getAsString());
            assertEquals("message-1", delivery.getAsJsonObject("params").get("messageId").getAsString());
            assertNull(signaling.transport.readOutbound());
        }
    }

    @Test
    void messagesDeliveredAsAnArrayReachTheirHandlers() {
        try (Signaling signaling = new Signaling()) {
            List<String> received = new ArrayList<>();
            signaling.setSignalHandler("42", received::add);
            signaling.setSignalHandler("43", received::add);
            JsonArray batch = new JsonArray();
            batch.add(Signaling.message("peer", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC,
                    webRtc("CANDIDATEADD 42 candidate")));
            batch.add(Signaling.message("peer", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC,
                    webRtc("CANDIDATEADD 43 candidate")));

            signaling.deliver(batch, false);

            assertEquals(List.of("CANDIDATEADD 42 candidate", "CANDIDATEADD 43 candidate"), received);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {NetherNetConstants.XBOX_RPC_METHOD_PING, NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH})
    void maintenanceErrorsDoNotReportPeerFailure(String method) {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger failures = new AtomicInteger();
            signaling.setFailureHandler(message -> failures.incrementAndGet());
            CompletableFuture<JsonObject> request = signaling.sendJsonRpcRequest(method, new JsonObject());
            String id = signaling.readOutbound().get("id").getAsString();

            signaling.respond(signaling.transport, id, "error", notFound(false));

            assertTrue(request.isCompletedExceptionally());
            assertEquals(0, failures.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusedSelfProbeDoesNotReportPeerFailure(boolean identityError) {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger failures = new AtomicInteger();
            signaling.setFailureHandler(message -> failures.incrementAndGet());
            signaling.connectAs("self");
            signaling.advance(30);
            String id = signaling.readRouteProbes().get(0).get("id").getAsString();

            signaling.respond(signaling.transport, id, "error", notFound(identityError));

            assertEquals(0, failures.get());
            assertTrue(signaling.isRouteAlive(60_000));
            signaling.advance(30);
            assertTrue(signaling.readRouteProbes().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void peerSignalsAndDeliveryNotificationsReportPeerFailure(boolean delivery) {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger failures = new AtomicInteger();
            signaling.setFailureHandler(message -> failures.incrementAndGet());
            if (delivery) {
                signaling.deliver(Signaling.message("peer", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC,
                        webRtc("CANDIDATEADD 42 candidate")), false);
            } else {
                signaling.sendSignal("peer", "CANDIDATEADD 42 candidate");
            }
            String id = signaling.readOutbound().get("id").getAsString();

            signaling.respond(signaling.transport, id, "error", notFound(true));
            signaling.respond(signaling.transport, id, "error", notFound(true));

            assertEquals(1, failures.get());
        }
    }

    @Test
    void peerFailureUsesTheHandlerThatSentTheRequest() {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger previous = new AtomicInteger();
            AtomicInteger current = new AtomicInteger();
            signaling.setFailureHandler(reason -> previous.incrementAndGet());
            signaling.sendSignal("peer", "CANDIDATEADD 42 candidate");
            String id = signaling.readOutbound().get("id").getAsString();
            signaling.setFailureHandler(reason -> current.incrementAndGet());

            signaling.respond(signaling.transport, id, "error", notFound(false));

            assertEquals(1, previous.get());
            assertEquals(0, current.get());
        }
    }

    private static JsonObject notFound(boolean identityError) {
        JsonObject error = new JsonObject();
        error.addProperty("message", identityError ? "Identity expired" : "Player not registered");
        if (identityError) {
            JsonObject data = new JsonObject();
            data.addProperty("Code", "MissingOrExpiredIdentity");
            error.add("data", data);
        }
        return error;
    }

    private static JsonObject webRtc(String signal) {
        JsonObject params = new JsonObject();
        params.addProperty("netherNetId", "123");
        params.addProperty("message", signal);
        return params;
    }

    private static JsonObject turnServers(String url) {
        JsonArray urls = new JsonArray();
        urls.add(url);
        JsonObject server = new JsonObject();
        server.add("Urls", urls);
        JsonArray servers = new JsonArray();
        servers.add(server);
        JsonObject result = new JsonObject();
        result.add("TurnAuthServers", servers);
        return result;
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

        /** Connects the transport and answers the TURN request. */
        private void connect() {
            CompletableFuture<?> pending = install(transport);
            transport.pipeline().fireUserEventTriggered(
                    WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            respond(transport, readOutbound().get("id").getAsString(), "result", new JsonObject());
            assertTrue(pending.isDone());
        }

        /** Connects with a token whose pmid claim is the given player id. */
        private void connectAs(String playerId) {
            xboxToken = "MCToken unused." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"pmid\":\"" + playerId + "\"}").getBytes(StandardCharsets.UTF_8)) + ".unused";
            connect();
        }

        private static JsonObject message(String from, String method, JsonObject params) {
            JsonObject inner = new JsonObject();
            inner.addProperty("jsonrpc", "2.0");
            inner.addProperty("method", method);
            inner.add("params", params);
            JsonObject message = new JsonObject();
            message.addProperty("From", from);
            message.addProperty("Id", "message-1");
            message.addProperty("Message", inner.toString());
            return message;
        }

        /** Delivers messages as the service does, one as an object or several as an array. */
        private void deliver(JsonElement params, boolean requestHasId) {
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("method", NetherNetConstants.XBOX_RPC_METHOD_RECEIVE_MESSAGE);
            if (requestHasId) {
                request.addProperty("id", "request-1");
            }
            request.add("params", params);
            transport.writeInbound(new TextWebSocketFrame(request.toString()));
        }

        private JsonObject readOutbound() {
            return readOutbound(transport);
        }

        /** Reads every frame written so far and returns the route probes among them. */
        private List<JsonObject> readRouteProbes() {
            List<JsonObject> probes = new ArrayList<>();
            for (JsonObject request : readRequests(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE)) {
                JsonObject inner = JsonParser.parseString(request.getAsJsonObject("params").get("message").getAsString())
                        .getAsJsonObject();
                if (NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE.equals(inner.get("method").getAsString())) {
                    probes.add(request);
                }
            }
            return probes;
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

        /** Reads every frame written so far and returns the requests for the given method. */
        private List<JsonObject> readRequests(String method) {
            List<JsonObject> requests = new ArrayList<>();
            Object message;
            while ((message = transport.readOutbound()) != null) {
                try {
                    if (message instanceof TextWebSocketFrame frame) {
                        JsonObject request = JsonParser.parseString(frame.text()).getAsJsonObject();
                        if (request.has("method") && method.equals(request.get("method").getAsString())) {
                            requests.add(request);
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
            return requests;
        }

        private void respond(EmbeddedChannel socket, String id, String field, JsonElement value) {
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
