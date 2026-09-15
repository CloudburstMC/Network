package org.cloudburstmc.netty.signaling.control;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class JdkWebSocketTransportTest {
    private static final String PROTOCOL = "transport-test";
    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();
    private final List<JdkWebSocketTransport> transports = new ArrayList<>();

    JdkWebSocketTransportTest() {
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    @AfterEach
    void stop() throws Exception {
        this.transports.forEach(JdkWebSocketTransport::abort);
        this.scheduler.shutdownNow();
        this.receiverExecutor.shutdownNow();
        assertTrue(this.scheduler.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(this.receiverExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static JdkWebSocketTransport.Limits limits() {
        return new JdkWebSocketTransport.Limits(4096, 64, 64, 32768,
                DEADLINE, DEADLINE, DEADLINE, DEADLINE);
    }

    private JdkWebSocketTransport connect(Loopback server,
                                         Function<String, ? extends CompletionStage<?>> receiver) throws Exception {
        JdkWebSocketTransport transport = JdkWebSocketTransport.connect(server.client(), server.uri(), PROTOCOL,
                Map.of("X-Control-Test", "opaque-auth-value"), limits(), this.receiverExecutor,
                this.scheduler, receiver);
        this.transports.add(transport);
        await(transport.opened());
        return transport;
    }

    private JdkWebSocketTransport fake(FakeSocket socket, JdkWebSocketTransport.Limits limits,
                                      Executor executor,
                                      Function<String, ? extends CompletionStage<?>> receiver) throws Exception {
        JdkWebSocketTransport transport = JdkWebSocketTransport.connect(socket, URI.create("ws://localhost/test"),
                PROTOCOL, Map.of(), limits, executor, this.scheduler, receiver);
        this.transports.add(transport);
        await(transport.opened());
        return transport;
    }

    @Test
    void realWsReceivesFragmentsAndPingWithoutLosingDemand() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        try (Loopback server = new Loopback(false)) {
            JdkWebSocketTransport transport = connect(server, text -> {
                received.add(text);
                return CompletableFuture.completedFuture(null);
            });
            // Even a UTF-8 code point can straddle RFC 6455 continuation frames.
            server.send(new TextWebSocketFrame(false, 0,
                    Unpooled.wrappedBuffer(new byte[]{'o', 'n', 'e', ' ', (byte) 0xf0})));
            server.send(new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{1, 2, 3})));
            server.send(new ContinuationWebSocketFrame(false, 0,
                    Unpooled.wrappedBuffer(new byte[]{(byte) 0x9f, (byte) 0x98, (byte) 0x80})));
            server.send(new ContinuationWebSocketFrame(true, 0, " café"));
            server.send(new TextWebSocketFrame("next"));
            assertEquals("one 😀 café", received.poll(5, TimeUnit.SECONDS));
            assertEquals("next", received.poll(5, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{1, 2, 3}, server.pongs.poll(5, TimeUnit.SECONDS));
            assertEquals("opaque-auth-value", server.header);
            await(transport.sendText("outbound 😀"));
            assertEquals("outbound 😀", server.texts.poll(5, TimeUnit.SECONDS));
            assertEquals(1000, await(transport.closeGracefully()).statusCode());
            assertTrue(this.scheduler.getQueue().isEmpty(), "Terminal connections release all timers");
            assertFalse(this.scheduler.isShutdown(), "Borrowed scheduler remains usable");
        }
    }

    @Test
    void realWssUsesCertificateTrustAndHostnameVerification() throws Exception {
        try (Loopback server = new Loopback(true)) {
            JdkWebSocketTransport transport = connect(server, text -> CompletableFuture.completedFuture(null));
            await(transport.sendText("encrypted"));
            assertEquals("encrypted", server.texts.poll(5, TimeUnit.SECONDS));
            await(transport.closeGracefully());

            JdkWebSocketTransport wrongHost = JdkWebSocketTransport.connect(server.client(),
                    URI.create(server.uri().toString().replace("localhost", "127.0.0.1")), PROTOCOL,
                    Map.of(), limits(), this.receiverExecutor, this.scheduler,
                    text -> CompletableFuture.completedFuture(null));
            this.transports.add(wrongHost);
            assertTrue(hasCause(failure(wrongHost.opened()), SSLHandshakeException.class));

            JdkWebSocketTransport untrusted = JdkWebSocketTransport.connect(HttpClient.newHttpClient(),
                    server.uri(), PROTOCOL, Map.of(), limits(), this.receiverExecutor, this.scheduler,
                    text -> CompletableFuture.completedFuture(null));
            this.transports.add(untrusted);
            Throwable rejected = failure(untrusted.opened());
            assertTrue(hasCause(rejected, SSLHandshakeException.class), rejected.toString());
        }
    }

    @Test
    void realWsBackpressuresUntilTheApplicationAcceptsTheMessage() throws Exception {
        CompletableFuture<Void> firstAccepted = new CompletableFuture<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        try (Loopback server = new Loopback(false)) {
            connect(server, text -> {
                if (text.equals("first")) {
                    first.countDown();
                    return firstAccepted;
                }
                second.countDown();
                return CompletableFuture.completedFuture(null);
            });
            server.send(new TextWebSocketFrame("first"));
            assertTrue(first.await(5, TimeUnit.SECONDS));
            server.send(new TextWebSocketFrame("second"));
            assertFalse(second.await(150, TimeUnit.MILLISECONDS));
            firstAccepted.complete(null);
            assertTrue(second.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void realWsSerializesConcurrentProducersWithoutLosingMessages() throws Exception {
        ExecutorService producers = Executors.newFixedThreadPool(8);
        try (Loopback server = new Loopback(false)) {
            JdkWebSocketTransport transport = connect(server, text -> CompletableFuture.completedFuture(null));
            List<CompletableFuture<Void>> sent = new ArrayList<>();
            HashSet<String> expected = new HashSet<>();
            for (int i = 0; i < 32; i++) {
                String message = "message-" + i;
                expected.add(message);
                sent.add(CompletableFuture.supplyAsync(() -> transport.sendText(message), producers)
                        .thenCompose(CompletionStage::toCompletableFuture));
            }
            await(CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)));
            HashSet<String> actual = new HashSet<>();
            for (int i = 0; i < 32; i++) {
                actual.add(server.texts.poll(5, TimeUnit.SECONDS));
            }
            assertEquals(expected, actual);
            await(transport.closeGracefully());
        } finally {
            producers.shutdownNow();
            assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void realWsRejectsBinaryAndOversizedUtf8() throws Exception {
        for (boolean binary : List.of(false, true)) {
            try (Loopback server = new Loopback(false)) {
                AtomicInteger delivered = new AtomicInteger();
                JdkWebSocketTransport transport = connect(server, text -> {
                    delivered.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
                server.send(binary ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{1}))
                        : new TextWebSocketFrame("€".repeat(2000)));
                assertNotNull(failure(transport.closed()));
                assertEquals(0, delivered.get());
                assertTrue(this.scheduler.getQueue().isEmpty());
            }
        }
    }

    @Test
    void realWsRejectsMissingSubprotocol() throws Exception {
        try (Loopback server = new Loopback(false, false)) {
            JdkWebSocketTransport transport = JdkWebSocketTransport.connect(server.client(), server.uri(), PROTOCOL,
                    Map.of(), limits(), this.receiverExecutor, this.scheduler,
                    text -> CompletableFuture.completedFuture(null));
            this.transports.add(transport);
            assertTrue(failure(transport.opened()).getMessage().contains("subprotocol"));
            assertNotNull(failure(transport.closed()));
        }
    }

    @Test
    void realWsUpgradeAndCloseEachHaveBoundedDeadlines() throws Exception {
        for (boolean handshake : List.of(false, true)) {
            try (Loopback server = new Loopback(false, true, handshake)) {
                server.replyClose = false;
                JdkWebSocketTransport.Limits bounds = new JdkWebSocketTransport.Limits(64, 10, 2, 128,
                        Duration.ofMillis(200), DEADLINE, DEADLINE, Duration.ofMillis(200));
                JdkWebSocketTransport transport = JdkWebSocketTransport.connect(server.client(), server.uri(),
                        PROTOCOL, Map.of(), bounds, this.receiverExecutor, this.scheduler,
                        text -> CompletableFuture.completedFuture(null));
                this.transports.add(transport);
                if (handshake) {
                    await(transport.opened());
                    assertInstanceOf(TimeoutException.class, failure(transport.closeGracefully()));
                } else {
                    assertTrue(hasCause(failure(transport.opened()), java.net.http.HttpTimeoutException.class));
                    assertNotNull(failure(transport.closed()));
                }
                assertTrue(this.scheduler.getQueue().isEmpty());
            }
        }
    }

    @Test
    void copiesJdkOwnedCharactersAndHoldsDemandUntilHandlerCompletes() throws Exception {
        FakeSocket socket = new FakeSocket();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        List<String> received = new ArrayList<>();
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        fake(socket, limits(), tasks::add, text -> {
            received.add(text);
            return accepted;
        });
        StringBuilder buffer = new StringBuilder("original");
        socket.text(buffer, false);
        buffer.replace(0, buffer.length(), "changed!");
        socket.text("😀", true);
        assertEquals(0, socket.demand);
        tasks.remove().run();
        assertEquals(List.of("original😀"), received);
        assertEquals(0, socket.demand);
        accepted.complete(null);
        assertEquals(1, socket.demand);
    }

    @Test
    void abortDiscardsQueuedReceiveWorkBeforeCallingTheApplication() throws Exception {
        FakeSocket socket = new FakeSocket();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger delivered = new AtomicInteger();
        JdkWebSocketTransport transport = fake(socket, limits(), tasks::add, text -> {
            delivered.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        socket.text("queued before abort", true);
        assertEquals(1, tasks.size());
        transport.abort();
        tasks.remove().run();
        assertEquals(0, delivered.get());
        assertEquals(0, socket.demand);
        assertTrue(this.scheduler.getQueue().isEmpty());
    }

    @Test
    void serializesSendsAndCountsTheActiveSendAgainstBothLimits() throws Exception {
        FakeSocket socket = new FakeSocket();
        JdkWebSocketTransport.Limits bounds = new JdkWebSocketTransport.Limits(20, 10, 3, 8,
                DEADLINE, DEADLINE, DEADLINE, DEADLINE);
        JdkWebSocketTransport transport = fake(socket, bounds, this.receiverExecutor,
                text -> CompletableFuture.completedFuture(null));
        CompletionStage<Void> first = transport.sendText("😀");
        CompletionStage<Void> second = transport.sendText("two");
        assertFalse(first.toCompletableFuture().isDone());
        assertEquals(List.of("😀"), socket.sent);
        assertTrue(failure(transport.sendText("no")).getMessage().contains("capacity"));
        CompletionStage<Void> third = transport.sendText("");
        assertTrue(failure(transport.sendText("")).getMessage().contains("capacity"));
        assertEquals(1, socket.pending.size());
        socket.finishSend();
        await(first);
        assertEquals(List.of("😀", "two"), socket.sent);
        socket.finishSend();
        await(second);
        socket.finishSend();
        await(third);
        assertTrue(socket.pending.isEmpty());
    }

    @Test
    void queuedDiagnosticAckCannotOutliveItsInstallationOrOriginalDeadline() throws Exception {
        for (String invalidation : List.of("withdrawal", "deadline")) {
            FakeSocket socket = new FakeSocket();
            var transport = fake(socket, limits(), this.receiverExecutor, text -> CompletableFuture.completedFuture(null));
            var now = new java.util.concurrent.atomic.AtomicLong(1000);
            var installed = new java.util.concurrent.atomic.AtomicBoolean(true);
            var binding = new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission.Binding(
                    new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.Context("https://provider.example", "host", "01".repeat(16), 1),
                    "authority:1", 1, "hpr:1", "A".repeat(43), 1, "A".repeat(43), "ab".repeat(32));
            var installation = new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission.Installation(binding, () -> {
                if (!installed.get() || now.get() >= 2000) throw new IllegalStateException("installation unavailable");
            });
            var first = transport.sendText("earlier frame awaiting actual send completion");
            var ack = transport.sendText("diagnostic ACK for original binding", installation::requireCurrent);
            var later = transport.sendText("later sequence");
            assertEquals(1, socket.sent.size()); assertFalse(ack.toCompletableFuture().isDone());
            if (invalidation.equals("withdrawal")) installed.set(false); else now.set(2000);
            socket.finishSend(); await(first);
            assertEquals(1, socket.sent.size(), invalidation + " must reject at actual dequeue");
            assertEquals("installation unavailable", failure(ack).getMessage());
            assertNotNull(failure(later)); assertNotNull(failure(transport.closed())); assertTrue(socket.aborted);
            assertTrue(socket.pending.isEmpty()); assertTrue(scheduler.getQueue().isEmpty());
            // Restoring a fixture token cannot revive the failed physical sequence owner.
            installed.set(true); now.set(1000);
            assertNotNull(failure(transport.sendText("late ACK", installation::requireCurrent)));
            assertEquals(1, socket.sent.size());
            var replacementSocket = new FakeSocket();
            var replacement = fake(replacementSocket, limits(), receiverExecutor, text -> CompletableFuture.completedFuture(null));
            var fresh = replacement.sendText("fresh writer", installation::requireCurrent);
            replacementSocket.finishSend(); await(fresh); replacement.abort();
        }
    }

    @Test
    void queuedGuardsRunOnlyAtHandoffAndHealthySendsStillDrainBeforeClose() throws Exception {
        FakeSocket socket = new FakeSocket();
        var transport = fake(socket, limits(), receiverExecutor, text -> CompletableFuture.completedFuture(null));
        var checks = new AtomicInteger();
        var first = transport.sendText("first", checks::incrementAndGet);
        var second = transport.sendText("second", checks::incrementAndGet);
        assertEquals(1, checks.get());
        var closed = transport.closeGracefully();
        socket.finishSend(); await(first); assertEquals(2, checks.get());
        socket.finishSend(); await(second);
        assertEquals(List.of("first", "second"), socket.sent); assertTrue(socket.outputClosed);
        socket.listener.onClose(socket, WebSocket.NORMAL_CLOSURE, ""); await(closed);
        assertFalse(socket.aborted); assertTrue(scheduler.getQueue().isEmpty());
    }

    @Test
    void initialGuardFailureAndReentrantAbortNeverReachSocket() throws Exception {
        for (boolean reentrant : List.of(false, true)) {
            FakeSocket socket = new FakeSocket();
            var transport = fake(socket, limits(), receiverExecutor, text -> CompletableFuture.completedFuture(null));
            var sending = transport.sendText("guarded", () -> {
                if (reentrant) transport.abort(); else throw new IllegalStateException("guard failed");
            });
            assertNotNull(failure(sending)); assertNotNull(failure(transport.closed()));
            assertTrue(socket.sent.isEmpty()); assertTrue(socket.aborted); assertTrue(scheduler.getQueue().isEmpty());
        }
    }

    @Test
    void applicationMonitorDoesNotInvertTransportLockDuringQueuedGuard() throws Exception {
        var socket = new FakeSocket();
        var transport = fake(socket, limits(), receiverExecutor, text -> CompletableFuture.completedFuture(null));
        var ownerMonitor = new Object(); var entered = new CountDownLatch(1);
        transport.sendText("first");
        var second = transport.sendText("guarded", () -> {
            entered.countDown(); synchronized (ownerMonitor) { /* Coordinator ownership check. */ }
        });
        CompletableFuture<Void> abort;
        synchronized (ownerMonitor) {
            receiverExecutor.submit(socket::finishSend);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            abort = CompletableFuture.runAsync(transport::abort);
            abort.get(1, TimeUnit.SECONDS); // A guard under the transport lock would deadlock here.
        }
        receiverExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
        assertNotNull(failure(second)); assertEquals(List.of("first"), socket.sent);
        assertTrue(socket.aborted); assertTrue(scheduler.getQueue().isEmpty());
    }

    @Test
    void oversizedAndMalformedOutboundMessagesNeverReachTheSocket() throws Exception {
        FakeSocket socket = new FakeSocket();
        JdkWebSocketTransport transport = fake(socket, limits(), this.receiverExecutor,
                text -> CompletableFuture.completedFuture(null));
        assertNotNull(failure(transport.sendText("😀".repeat(1025))));
        assertThrows(IllegalArgumentException.class, () -> transport.sendText("\uD800"));
        assertTrue(socket.sent.isEmpty());
    }

    @Test
    void sendFailureFailsQueuedWorkAndLateCompletionCannotRestartIt() throws Exception {
        FakeSocket socket = new FakeSocket();
        JdkWebSocketTransport transport = fake(socket, limits(), this.receiverExecutor,
                text -> CompletableFuture.completedFuture(null));
        CompletionStage<Void> active = transport.sendText("active");
        CompletionStage<Void> queued = transport.sendText("queued");
        socket.pending.remove().completeExceptionally(new IllegalStateException("failed write"));
        assertEquals("failed write", failure(active).getMessage());
        assertEquals("failed write", failure(queued).getMessage());
        assertNotNull(failure(transport.closed()));
        assertTrue(socket.aborted);
        assertEquals(List.of("active"), socket.sent);
        assertTrue(this.scheduler.getQueue().isEmpty());
    }

    @Test
    void drainsBeforeClosingAndAbortsWhenPeerDoesNotReply() throws Exception {
        FakeSocket socket = new FakeSocket();
        JdkWebSocketTransport.Limits bounds = new JdkWebSocketTransport.Limits(64, 10, 2, 128,
                DEADLINE, DEADLINE, DEADLINE, Duration.ofMillis(200));
        JdkWebSocketTransport transport = fake(socket, bounds, this.receiverExecutor,
                text -> CompletableFuture.completedFuture(null));
        CompletionStage<Void> first = transport.sendText("first");
        CompletionStage<Void> second = transport.sendText("second");
        CompletionStage<JdkWebSocketTransport.Close> closing = transport.closeGracefully();
        assertFalse(socket.outputClosed);
        assertNotNull(failure(transport.sendText("late")));
        socket.finishSend();
        await(first);
        socket.finishSend();
        await(second);
        assertTrue(socket.outputClosed);
        assertInstanceOf(TimeoutException.class, failure(closing));
        assertTrue(socket.aborted);
    }

    @Test
    void sendTimeoutAndCloseDeadlineBoundAnUnfinishedSend() throws Exception {
        for (boolean closing : List.of(false, true)) {
            FakeSocket socket = new FakeSocket();
            JdkWebSocketTransport.Limits bounds = new JdkWebSocketTransport.Limits(64, 10, 2, 128,
                    DEADLINE, DEADLINE, closing ? DEADLINE : Duration.ofMillis(100), Duration.ofMillis(100));
            JdkWebSocketTransport transport = fake(socket, bounds, this.receiverExecutor,
                    text -> CompletableFuture.completedFuture(null));
            CompletionStage<Void> active = transport.sendText("never completes");
            CompletionStage<Void> queued = transport.sendText("queued");
            if (closing) {
                transport.close();
            }
            assertInstanceOf(TimeoutException.class, failure(transport.closed()));
            assertNotNull(failure(active));
            assertNotNull(failure(queued));
            socket.finishSend();
            assertEquals(1, socket.sent.size());
            assertTrue(socket.aborted);
        }
    }

    @Test
    void limitsEmptyFragmentsAndTimesOutIncompleteOrUnacceptedMessages() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            FakeSocket socket = new FakeSocket();
            JdkWebSocketTransport.Limits bounds = new JdkWebSocketTransport.Limits(64, 2, 2, 128,
                    DEADLINE, Duration.ofMillis(100), DEADLINE, DEADLINE);
            JdkWebSocketTransport transport = fake(socket, bounds, this.receiverExecutor,
                    text -> new CompletableFuture<>());
            socket.text("", mode == 2);
            if (mode == 0) {
                socket.text("", false);
                socket.text("", false);
            }
            Throwable failure = failure(transport.closed());
            if (mode != 0) {
                assertInstanceOf(TimeoutException.class, failure);
            }
            assertTrue(socket.aborted);
            assertTrue(this.scheduler.getQueue().isEmpty());
        }
    }

    @Test
    void abortDuringUpgradeCancelsHandshakeAndRejectsLateOpen() throws Exception {
        FakeSocket socket = new FakeSocket();
        socket.openImmediately = false;
        JdkWebSocketTransport transport = JdkWebSocketTransport.connect(socket, URI.create("ws://localhost/test"),
                PROTOCOL, Map.of(), limits(), this.receiverExecutor, this.scheduler,
                text -> CompletableFuture.completedFuture(null));
        this.transports.add(transport);
        transport.abort();
        assertTrue(socket.handshake.isCancelled());
        socket.listener.onOpen(socket);
        assertTrue(socket.aborted);
        assertEquals(0, socket.demand);
        assertNotNull(failure(transport.opened()));
        assertNotNull(failure(transport.closed()));
    }

    @Test
    void receiverRejectionAndFailureTerminateConnection() throws Exception {
        for (boolean reject : List.of(false, true)) {
            FakeSocket socket = new FakeSocket();
            Executor executor = reject ? task -> { throw new java.util.concurrent.RejectedExecutionException(); }
                    : this.receiverExecutor;
            JdkWebSocketTransport transport = fake(socket, limits(), executor,
                    text -> CompletableFuture.failedFuture(new IllegalStateException("receiver failed")));
            socket.text("message", true);
            assertNotNull(failure(transport.closed()));
            assertTrue(socket.aborted);
            assertTrue(this.scheduler.getQueue().isEmpty());
        }
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletionStage<?> stage) {
        return assertThrows(ExecutionException.class,
                () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS)).getCause();
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        while (failure != null) {
            if (type.isInstance(failure)) {
                return true;
            }
            failure = failure.getCause();
        }
        return false;
    }

    private static final class FakeSocket implements WebSocket, WebSocket.Builder {
        final List<String> sent = new ArrayList<>();
        final ArrayDeque<CompletableFuture<WebSocket>> pending = new ArrayDeque<>();
        final CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
        WebSocket.Listener listener;
        long demand;
        volatile boolean aborted;
        boolean outputClosed;
        boolean openImmediately = true;

        void text(CharSequence value, boolean last) {
            assertTrue(this.demand > 0, "Fake server must obey JDK callback demand");
            this.demand--;
            this.listener.onText(this, value, last);
        }

        void finishSend() {
            this.pending.remove().complete(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            assertTrue(this.pending.isEmpty(), "JDK does not allow concurrent text sends");
            assertTrue(last);
            this.sent.add(data.toString());
            CompletableFuture<WebSocket> future = new CompletableFuture<>();
            this.pending.add(future);
            return future;
        }

        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new AssertionError(); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { throw new AssertionError(); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { throw new AssertionError(); }
        @Override public CompletableFuture<WebSocket> sendClose(int code, String reason) {
            assertTrue(this.pending.isEmpty());
            this.outputClosed = true;
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { this.demand += n; }
        @Override public String getSubprotocol() { return PROTOCOL; }
        @Override public boolean isOutputClosed() { return this.outputClosed || this.aborted; }
        @Override public boolean isInputClosed() { return this.aborted; }
        @Override public void abort() { this.aborted = true; }
        @Override public WebSocket.Builder header(String name, String value) { return this; }
        @Override public WebSocket.Builder connectTimeout(Duration timeout) { return this; }
        @Override public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) { return this; }
        @Override public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
            this.listener = listener;
            if (this.openImmediately) {
                listener.onOpen(this);
                this.handshake.complete(this);
            }
            return this.handshake;
        }
    }

    /** Real Netty RFC 6455 peer, with ordinary PKIX trust and hostname verification for TLS tests. */
    private static final class Loopback implements AutoCloseable {
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final CompletableFuture<Channel> peer = new CompletableFuture<>();
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> pongs = new LinkedBlockingQueue<>();
        final SelfSignedCertificate certificate;
        final Channel server;
        volatile String header;
        volatile String controlProof, requestedProtocol;
        final String address;
        volatile boolean replyClose = true;

        Loopback(boolean tls) throws Exception {
            this(tls, true);
        }

        Loopback(boolean tls, boolean protocol) throws Exception {
            this(tls, protocol, true);
        }

        Loopback(boolean tls, boolean protocol, boolean handshake) throws Exception {
            this(tls, protocol, handshake, "127.0.0.1", PROTOCOL);
        }

        Loopback(boolean tls, boolean protocol, boolean handshake, String address, String selectedProtocol) throws Exception {
            this.address = address;
            this.certificate = tls ? new SelfSignedCertificate("localhost") : null;
            SslContext ssl = tls ? SslContextBuilder.forServer(this.certificate.certificate(),
                    this.certificate.privateKey()).build() : null;
            this.server = new ServerBootstrap().group(this.group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            if (ssl != null) {
                                channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                            }
                            channel.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(8192),
                                    new SimpleChannelInboundHandler<Object>() {
                                        @Override
                                        protected void channelRead0(ChannelHandlerContext ctx, Object message) {
                                            if (message instanceof FullHttpRequest request) {
                                                header = request.headers().get("X-Control-Test");
                                                controlProof = request.headers().get("Nxs-Control-Proof");
                                                requestedProtocol = request.headers().get("Sec-WebSocket-Protocol");
                                                if (!handshake) {
                                                    return;
                                                }
                                                new WebSocketServerHandshakerFactory(uri().toString(),
                                                        protocol ? selectedProtocol : null, false)
                                                        .newHandshaker(request).handshake(ctx.channel(), request)
                                                        .addListener(result -> {
                                                            if (result.isSuccess()) {
                                                                peer.complete(ctx.channel());
                                                            } else {
                                                                peer.completeExceptionally(result.cause());
                                                            }
                                                        });
                                            } else if (message instanceof TextWebSocketFrame text) {
                                                texts.add(text.text());
                                            } else if (message instanceof PongWebSocketFrame pong) {
                                                byte[] bytes = new byte[pong.content().readableBytes()];
                                                pong.content().readBytes(bytes);
                                                pongs.add(bytes);
                                            } else if (message instanceof CloseWebSocketFrame close) {
                                                if (replyClose) {
                                                    ctx.writeAndFlush(close.retainedDuplicate())
                                                            .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                                                }
                                            }
                                        }

                                        @Override
                                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                            ctx.close(); // TLS rejection is an expected negative test.
                                        }
                                    });
                        }
                    }).bind(address, 0).sync().channel();
        }

        URI uri() {
            return URI.create((this.certificate == null ? "ws" : "wss") + "://" + (address.equals("::1") ? "[::1]" : "localhost") + ":"
                    + ((InetSocketAddress) this.server.localAddress()).getPort() + "/control");
        }

        HttpClient client() throws Exception {
            HttpClient.Builder builder = HttpClient.newBuilder();
            if (this.certificate != null) {
                KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
                trust.load(null, null);
                trust.setCertificateEntry("loopback", this.certificate.cert());
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(trust);
                SSLContext ssl = SSLContext.getInstance("TLS");
                ssl.init(null, factory.getTrustManagers(), null);
                builder.sslContext(ssl);
            }
            return builder.build();
        }

        void send(WebSocketFrame frame) throws Exception {
            this.peer.get(5, TimeUnit.SECONDS).writeAndFlush(frame).sync();
        }

        @Override
        public void close() throws Exception {
            if (this.peer.isDone() && !this.peer.isCompletedExceptionally()) {
                this.peer.join().close().sync();
            }
            this.server.close().sync();
            this.group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            if (this.certificate != null) {
                this.certificate.delete();
            }
        }
    }
}
