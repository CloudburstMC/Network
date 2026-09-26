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
import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetXboxSignalingLifecycleTest {

    @Test
    void failedOldWaiterCannotAbortTheReplacementAttempt() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);

            assertThrows(ConnectException.class,
                    () -> signaling.joinConnect(CompletableFuture.failedFuture(new ConnectException("old attempt failed"))));

            assertTrue(replacement.isOpen());
            assertFalse(pending.isDone());
            assertSame(replacement, signaling.channel);
        }
    }

    @Test
    void staleCredentialsCannotCompleteTheNewAttemptOrRefreshItsLiveness() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel previous = signaling.newSocket();
            signaling.install(previous);
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);
            signaling.lastMessageReceivedAt = 123;
            TextWebSocketFrame stale = credentials("turn:old.invalid");

            previous.writeInbound(stale);

            assertEquals(0, stale.refCnt());
            assertEquals(123, signaling.lastMessageReceivedAt);
            assertFalse(pending.isDone());
            assertTrue(signaling.getIceServers().isEmpty());

            replacement.writeInbound(credentials("turn:current.invalid"));

            assertTrue(pending.isDone());
            assertFalse(pending.isCompletedExceptionally());
            assertEquals(List.of("turn:current.invalid"), signaling.getIceServers().get(0).urls());
        }
    }

    @Test
    void staleHandshakeAndExceptionLeaveTheReplacementAlone() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel previous = signaling.newSocket();
            signaling.install(previous);
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);
            signaling.lastMessageReceivedAt = 123;

            previous.pipeline().fireUserEventTriggered(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);

            assertEquals(-1, previous.runScheduledPendingTasks(), "A stale handshake must not start ping loops");
            assertEquals(123, signaling.lastMessageReceivedAt);
            previous.pipeline().fireExceptionCaught(new IllegalStateException("old socket failed"));

            assertFalse(previous.isOpen());
            assertTrue(replacement.isOpen());
            assertFalse(pending.isDone());
            assertSame(replacement, signaling.channel);
        }
    }

    @Test
    void theHandshakeStartsProtocolPings() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            signaling.install(socket);
            socket.pipeline().fireUserEventTriggered(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);

            socket.advanceTimeBy(15, TimeUnit.SECONDS);
            socket.runScheduledPendingTasks();

            boolean pinged = false;
            Object frame;
            while ((frame = socket.readOutbound()) != null) {
                pinged |= frame instanceof PingWebSocketFrame;
                ReferenceCountUtil.release(frame);
            }
            assertTrue(pinged);
        }
    }

    @Test
    void aPongKeepsAQuietSocketAlive() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            signaling.install(socket);
            signaling.lastMessageReceivedAt = System.currentTimeMillis() - 60_000;
            assertTrue(signaling.isChannelAlive());
            assertFalse(signaling.isChannelAlive(45_000), "An open socket that went silent is not alive");

            socket.writeInbound(new PongWebSocketFrame());

            assertTrue(signaling.isChannelAlive(45_000));
        }
    }

    @Test
    void closingTheSocketCancelsItsRecurringTasks() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            signaling.install(socket);
            socket.pipeline().fireUserEventTriggered(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
            assertTrue(socket.runScheduledPendingTasks() > 0, "The handshake starts the ping loop");

            socket.close();

            assertEquals(-1, socket.runScheduledPendingTasks());
        }
    }

    @Test
    void reconnectOnAClosedSignalingFails() {
        Signaling signaling = new Signaling();
        signaling.close();

        assertThrows(ConnectException.class, () -> signaling.reconnect("MCToken fresh"));
        assertTrue(signaling.connect(null).isCompletedExceptionally());
    }

    private static TextWebSocketFrame credentials(String url) {
        JsonArray urls = new JsonArray();
        urls.add(url);
        JsonObject server = new JsonObject();
        server.add("Urls", urls);
        JsonArray servers = new JsonArray();
        servers.add(server);
        JsonObject credentials = new JsonObject();
        credentials.add("TurnAuthServers", servers);
        JsonObject message = new JsonObject();
        message.addProperty("Type", 2);
        message.addProperty("Message", credentials.toString());
        return new TextWebSocketFrame(message.toString());
    }

    private static final class Signaling extends NetherNetXboxSignaling implements AutoCloseable {
        private final List<EmbeddedChannel> sockets = new ArrayList<>();

        private Signaling() {
            super("1", "MCToken unused");
        }

        private EmbeddedChannel newSocket() {
            EmbeddedChannel socket = new EmbeddedChannel(this);
            socket.freezeTime();
            sockets.add(socket);
            return socket;
        }

        private synchronized CompletableFuture<List<IceServerInfo>> install(EmbeddedChannel socket) {
            channel = socket;
            connectFuture = new CompletableFuture<>();
            return connectFuture;
        }

        @Override
        public void close() {
            try {
                super.close();
                sockets.forEach(EmbeddedChannel::finishAndReleaseAll);
            } finally {
                eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }
}
