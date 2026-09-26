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
import java.util.ArrayList;
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
    void notFoundResponseOnTheCurrentSocketIsReportedOnce() throws Exception {
        try (Signaling signaling = new Signaling()) {
            AtomicInteger notFound = new AtomicInteger();
            signaling.setFailureHandler(message -> notFound.incrementAndGet());
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
