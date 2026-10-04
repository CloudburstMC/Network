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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void credentialsPushedAfterTheConnectReplaceTheOldOnes() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(socket);
            socket.writeInbound(credentials("turn:first.invalid"));
            assertTrue(pending.isDone());

            socket.writeInbound(credentials("turn:refreshed.invalid"));

            assertEquals(List.of("turn:refreshed.invalid"), signaling.getIceServers().get(0).urls());
        }
    }

    @Test
    void anEmptyFirstPushOnAReplacementKeepsPreviousCredentialsAndCompletesTheConnect() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel previous = signaling.newSocket();
            signaling.install(previous);
            previous.writeInbound(credentials("turn:working.invalid"));
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<List<IceServerInfo>> pending = signaling.install(replacement);
            JsonObject message = new JsonObject();
            message.addProperty("Type", 2);
            message.addProperty("Message", "{}");

            replacement.writeInbound(new TextWebSocketFrame(message.toString()));

            assertEquals(List.of("turn:working.invalid"), pending.join().get(0).urls());
            assertEquals(List.of("turn:working.invalid"), signaling.getIceServers().get(0).urls());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"TurnAuthServers\":[]}", "{\"TurnAuthServers\":{}}",
            "{\"TurnAuthServers\":[{}]}", "{\"TurnAuthServers\":[null]}",
            "{\"TurnAuthServers\":[{\"Urls\":[]}]}", "{\"TurnAuthServers\":[{\"Urls\":[\" \"]}]}"})
    void credentialPushWithoutAUsableServerKeepsPreviousCredentials(String response) {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            signaling.install(socket);
            socket.writeInbound(credentials("turn:working.invalid"));
            JsonObject message = new JsonObject();
            message.addProperty("Type", 2);
            message.addProperty("Message", response);

            socket.writeInbound(new TextWebSocketFrame(message.toString()));

            assertEquals(1, signaling.getIceServers().size());
            assertEquals(List.of("turn:working.invalid"), signaling.getIceServers().get(0).urls());
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
    void aCloseFrameFromTheServiceClosesTheSocket() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel socket = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(socket);
            CloseWebSocketFrame close = new CloseWebSocketFrame(1008, "Policy Violation");

            socket.writeInbound(close);

            assertEquals(0, close.refCnt());
            assertFalse(socket.isOpen());
            assertTrue(pending.isCompletedExceptionally());
            assertFalse(signaling.isChannelAlive());
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

    @ParameterizedTest
    @ValueSource(strings = {"00042", "peer /?#+%", ".", "..", "玩家🎮"})
    void theNetworkIdStaysOnePathSegmentOfTheUri(String id) {
        try (Signaling signaling = new Signaling(id)) {
            String prefix = "/ws/v1.0/signaling/";
            assertEquals(id, signaling.getLocalNetworkId());
            assertEquals("wss", signaling.uri.getScheme());
            assertEquals("signal.franchise.minecraft-services.net", signaling.uri.getHost());
            assertEquals(prefix + id, signaling.uri.getPath());
            assertFalse(signaling.uri.getRawPath().substring(prefix.length()).contains("/"));
            assertNull(signaling.uri.getRawQuery());
            assertNull(signaling.uri.getRawFragment());
            assertEquals(signaling.uri, signaling.uri.normalize());
        }
    }

    @Test
    void directConnectFutureCompletionStillPublishesCredentials() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<List<IceServerInfo>> pending = signaling.connect(null);
            signaling.nextSocket();
            List<IceServerInfo> servers = List.of(new IceServerInfo.Builder()
                    .setUrls(List.of("turn:subclass.invalid")).build());

            signaling.onLoop(() -> signaling.connectFuture.complete(servers));

            assertSame(servers, pending.join());
            assertSame(servers, signaling.getIceServers());
        }
    }

    @Test
    void supersededDirectCompletionCannotPublishCredentials() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<List<IceServerInfo>> previous = signaling.connect(null);
            signaling.nextSocket();
            signaling.onLoop(() -> signaling.connectFuture = new CompletableFuture<>());
            List<IceServerInfo> stale = List.of(new IceServerInfo.Builder()
                    .setUrls(List.of("turn:stale.invalid")).build());

            previous.complete(stale);

            assertTrue(signaling.getIceServers().isEmpty());
            assertFalse(signaling.connectFuture.isDone());
        }
    }

    @Test
    void credentialsArePublishedBeforeConnectObserversRun() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<List<IceServerInfo>> pending = signaling.connect(null);
            CompletableFuture<Void> observed = pending.thenAccept(servers -> {
                assertSame(servers, signaling.getIceServers());
                assertFalse(Thread.holdsLock(signaling));
                CompletableFuture.runAsync(() -> signaling.sendSignal("peer", "CANDIDATEADD 42 candidate"))
                        .orTimeout(5, TimeUnit.SECONDS).join();
            });
            EmbeddedChannel socket = signaling.nextSocket();

            signaling.receive(socket, credentials("turn:published.invalid"));

            observed.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anInactiveObserverCanStartTheNextAttempt() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<List<IceServerInfo>> previous = signaling.connect(null);
            EmbeddedChannel socket = signaling.nextSocket();
            CompletableFuture<CompletableFuture<List<IceServerInfo>>> next = previous.handle((servers, error) -> {
                assertFalse(Thread.holdsLock(signaling));
                assertTrue(error != null);
                return signaling.connect(null);
            });

            signaling.onLoop(() -> socket.close());

            CompletableFuture<List<IceServerInfo>> pending = next.get(5, TimeUnit.SECONDS);
            assertFalse(pending == previous);
            EmbeddedChannel replacement = signaling.nextSocket();
            signaling.receive(replacement, credentials("turn:next.invalid"));
            assertEquals(List.of("turn:next.invalid"), pending.get(5, TimeUnit.SECONDS).get(0).urls());
            assertTrue(replacement.isOpen());
        }
    }

    @Test
    void supersededBindCannotCloseTheReplacement() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<Void> bind = CompletableFuture.runAsync(() -> {
                try {
                    signaling.bind(null, null);
                } catch (ConnectException e) {
                    throw new CompletionException(e);
                }
            });
            EmbeddedChannel previous = signaling.nextSocket();
            CompletableFuture<Void> reconnect = signaling.reconnectAsync("MCToken fresh");
            EmbeddedChannel replacement = signaling.nextSocket();
            signaling.receive(replacement, credentials("turn:replacement.invalid"));

            reconnect.get(5, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, bind::join);
            assertFalse(previous.isOpen());
            assertTrue(replacement.isOpen());
            assertSame(replacement, signaling.channel);
            assertEquals("MCToken fresh", signaling.xboxToken);
        }
    }

    @Test
    void failedBindClosesSignalingEvenWhenInactiveClearedTheAttempt() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            CompletableFuture<Void> bind = CompletableFuture.runAsync(() -> {
                try {
                    signaling.bind(null, null);
                } catch (ConnectException e) {
                    throw new CompletionException(e);
                }
            });
            EmbeddedChannel socket = signaling.nextSocket();
            signaling.onLoop(() -> socket.close());

            assertThrows(CompletionException.class, bind::join);
            assertTrue(signaling.closedEvent.await(5, TimeUnit.SECONDS));
            assertTrue(signaling.connect(null).isCompletedExceptionally());
        }
    }

    @Test
    void failedReconnectCanBeRetriedWithoutLosingHandlers() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            signaling.connectReady();
            AtomicInteger received = new AtomicInteger();
            signaling.setSignalHandler("42", signal -> received.incrementAndGet());
            CompletableFuture<Void> failed = signaling.reconnectAsync("MCToken rejected");
            EmbeddedChannel rejected = signaling.nextSocket();
            signaling.onLoop(() -> rejected.close());
            assertThrows(CompletionException.class, failed::join);

            CompletableFuture<Void> retry = signaling.reconnectAsync("MCToken accepted");
            EmbeddedChannel replacement = signaling.nextSocket();
            signaling.receive(replacement, credentials("turn:retry.invalid"));
            retry.get(5, TimeUnit.SECONDS);
            signaling.receive(replacement, signal("CANDIDATEADD 42 candidate"));

            assertEquals(1, received.get());
            assertEquals("MCToken accepted", signaling.xboxToken);
            assertTrue(replacement.isOpen());
        }
    }

    @Test
    void closeWaitsForTheWholeFrameWithoutHoldingTheMonitor() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            EmbeddedChannel socket = signaling.connectReady();
            ChannelHandlerContext context = socket.pipeline().context(signaling);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicInteger received = new AtomicInteger();
            signaling.beforeRead = () -> {
                entered.countDown();
                await(resume);
            };
            signaling.setSignalHandler("42", message -> {
                assertFalse(Thread.holdsLock(signaling));
                assertTrue(signaling.isChannelAlive());
                CompletableFuture.runAsync(() -> signaling.sendSignal("peer", message)).orTimeout(5, TimeUnit.SECONDS).join();
                received.incrementAndGet();
            });
            io.netty.util.concurrent.Future<?> reading = signaling.eventLoopGroup.next().submit(
                    () -> socket.writeInbound(signal("CANDIDATEADD 42 candidate")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                signaling.requestClose();
                assertTrue(signaling.isChannelAlive());
            } finally {
                resume.countDown();
            }
            reading.syncUninterruptibly();
            assertTrue(signaling.closedEvent.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.get());
            TextWebSocketFrame stale = signal("CANDIDATEADD 42 late");
            signaling.channelRead(context, stale);
            assertEquals(0, stale.refCnt());
            assertEquals(1, received.get());
        }
    }

    @Test
    void reconnectDeadlineClosesTheHalfOpenSocketAndAllowsRetry() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            signaling.connectReady();
            CompletableFuture<Void> stalled = signaling.reconnectAsync("MCToken stalled");
            EmbeddedChannel halfOpen = signaling.nextSocket();

            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> stalled.get(25, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ConnectException);
            assertTrue(failure.getCause().getCause() instanceof java.util.concurrent.TimeoutException);
            assertFalse(halfOpen.isOpen());
            CompletableFuture<Void> retry = signaling.reconnectAsync("MCToken retry");
            EmbeddedChannel replacement = signaling.nextSocket();
            signaling.receive(replacement, credentials("turn:retry.invalid"));
            retry.get(5, TimeUnit.SECONDS);
            assertTrue(replacement.isOpen());
        }
    }

    @Test
    void blockingLifecycleCallsOnTheSignalingLoopFailWithoutChangingTheSocket() throws Exception {
        try (LoopSignaling signaling = new LoopSignaling()) {
            EmbeddedChannel socket = signaling.connectReady();
            signaling.onLoop(() -> {
                assertThrows(ConnectException.class, () -> signaling.reconnect("MCToken fresh"));
                assertThrows(ConnectException.class, () -> signaling.bind(null, null));
            });
            assertSame(socket, signaling.channel);
            assertTrue(socket.isOpen());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static TextWebSocketFrame signal(String signal) {
        JsonObject message = new JsonObject();
        message.addProperty("Type", 1);
        message.addProperty("From", "peer");
        message.addProperty("Message", signal);
        return new TextWebSocketFrame(message.toString());
    }

    private static final class LoopSignaling extends NetherNetXboxSignaling {
        private final LinkedBlockingQueue<EmbeddedChannel> opened = new LinkedBlockingQueue<>();
        private final List<EmbeddedChannel> sockets = new CopyOnWriteArrayList<>();
        private final CountDownLatch closedEvent = new CountDownLatch(1);
        private volatile Runnable beforeRead = () -> {};

        private LoopSignaling() {
            super("1", "MCToken initial");
        }

        @Override
        ChannelFuture connectChannel() {
            EmbeddedChannel socket = new EmbeddedChannel(this);
            socket.freezeTime();
            sockets.add(socket);
            opened.add(socket);
            return socket.newSucceededFuture();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            beforeRead.run();
            super.channelRead0(ctx, frame);
        }

        @Override
        protected void onClosed() {
            closedEvent.countDown();
        }

        private EmbeddedChannel nextSocket() throws InterruptedException {
            EmbeddedChannel socket = opened.poll(5, TimeUnit.SECONDS);
            assertTrue(socket != null, "The attempt must open a socket");
            return socket;
        }

        private void onLoop(Runnable action) {
            eventLoopGroup.next().submit(action).syncUninterruptibly();
        }

        private void receive(EmbeddedChannel socket, TextWebSocketFrame frame) {
            onLoop(() -> socket.writeInbound(frame));
        }

        private EmbeddedChannel connectReady() throws Exception {
            CompletableFuture<?> pending = connect(null);
            EmbeddedChannel socket = nextSocket();
            receive(socket, credentials("turn:initial.invalid"));
            pending.get(5, TimeUnit.SECONDS);
            return socket;
        }

        private CompletableFuture<Void> reconnectAsync(String token) {
            return CompletableFuture.runAsync(() -> {
                try {
                    reconnect(token);
                } catch (ConnectException e) {
                    throw new CompletionException(e);
                }
            });
        }

        private void requestClose() {
            super.close();
        }

        @Override
        public void close() {
            requestClose();
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            sockets.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
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
            this("1");
        }

        private Signaling(String networkId) {
            super(networkId, "MCToken unused");
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
            super.close();
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            sockets.forEach(EmbeddedChannel::finishAndReleaseAll);
        }
    }
}
