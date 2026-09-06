package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSessionListener;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the lazy backend contract: a signaling endpoint that fails to bind
 * must never cause backend (native engine) creation, and a successful bind
 * materializes the backend exactly once. This is the regression guard for
 * the create-then-immediately-dispose churn that a taken TCP port used to
 * trigger on shared hosts.
 */
class NetherNetServerChannelLazyBackendTest {

    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void failedSignalingBindNeverCreatesBackend() throws Exception {
        AtomicInteger backendCreations = new AtomicInteger();
        StubSignaling signaling = new StubSignaling(true);

        ChannelFuture future = bootstrap(() -> {
            backendCreations.incrementAndGet();
            return new StubBackend();
        }, signaling).bind(new InetSocketAddress(0));

        assertThrows(Exception.class, future::sync);
        assertEquals(0, backendCreations.get(), "backend must not be created when the signaling bind fails");

        // Closing the failed channel must not throw despite no backend existing.
        future.channel().close().syncUninterruptibly();
        assertTrue(signaling.closed, "doClose still closes the signaling endpoint");
    }

    @Test
    void successfulBindCreatesBackendOnce() throws Exception {
        AtomicInteger backendCreations = new AtomicInteger();
        StubBackend backend = new StubBackend();
        StubSignaling signaling = new StubSignaling(false);

        ChannelFuture future = bootstrap(() -> {
            backendCreations.incrementAndGet();
            return backend;
        }, signaling).bind(new InetSocketAddress(0));

        future.sync();
        assertTrue(signaling.bound, "signaling bound before the backend materialized");
        assertEquals(1, backendCreations.get(), "backend materializes exactly once per bound channel");
        assertFalse(backend.closed);

        future.channel().close().syncUninterruptibly();
        assertTrue(backend.closed, "channel close disposes the materialized backend");
        assertTrue(signaling.closed);
    }

    @Test
    void handshakeTimeoutClosesSessionBeforeChildRegistration() throws Exception {
        StubBackend backend = new StubBackend();
        StubSignaling signaling = new StubSignaling(false);
        NetherNetServerChannel server = (NetherNetServerChannel) bootstrap(() -> backend, signaling)
                .option(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, 0)
                .bind(new InetSocketAddress(0)).sync().channel();
        try {
            server.acceptConnection(1, "v=0\r\n", "1");

            assertTrue(backend.session.closed.await(2, TimeUnit.SECONDS));
            server.eventLoop().submit(() -> {}).sync();
            assertEquals(1, backend.session.closes.get());
            assertTrue(signaling.handlers.isEmpty());
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    void sessionClosedBeforeAcceptReturnsIsNotLeftAttached() throws Exception {
        StubBackend backend = new StubBackend();
        backend.closeBeforeReturn = true;
        StubSignaling signaling = new StubSignaling(false);
        NetherNetServerChannel server = (NetherNetServerChannel) bootstrap(() -> backend, signaling)
                .bind(new InetSocketAddress(0)).sync().channel();
        try {
            server.acceptConnection(2, "v=0\r\n", "2");

            assertTrue(backend.session.closed.await(2, TimeUnit.SECONDS));
            server.eventLoop().submit(() -> {}).sync();
            assertEquals(1, backend.session.closes.get());
            assertTrue(signaling.handlers.isEmpty());
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void negotiationFailureSignalsAnErrorAndClosesTheChild(boolean failureBeforeReturn, boolean signalingFails) throws Exception {
        StubBackend backend = new StubBackend();
        backend.failBeforeReturn = failureBeforeReturn;
        StubSignaling signaling = new StubSignaling(false);
        signaling.failSend = signalingFails;
        NetherNetServerChannel server = (NetherNetServerChannel) bootstrap(() -> backend, signaling)
                .bind(new InetSocketAddress(0)).sync().channel();
        try {
            server.acceptConnection(4, "v=0\r\n", "42");
            server.eventLoop().submit(() -> {}).sync();
            if (!failureBeforeReturn) {
                backend.listener.onNegotiationFailed("SetRemoteDescription failed");
            }

            assertTrue(backend.session.closed.await(2, TimeUnit.SECONDS));
            server.eventLoop().submit(() -> {}).sync();

            assertEquals(new SentSignal("42", NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR
                    + " 4 SetRemoteDescription failed"), signaling.sent.poll(2, TimeUnit.SECONDS));
            assertTrue(signaling.sent.isEmpty());
            assertEquals(1, backend.session.closes.get());
            assertTrue(signaling.handlers.isEmpty());
        } finally {
            server.close().syncUninterruptibly();
        }
    }

    @Test
    void serverCloseClosesAcceptedChannelsAsWellAsTheBackend() throws Exception {
        StubBackend backend = new StubBackend();
        StubSignaling signaling = new StubSignaling(false);
        AtomicReference<Channel> accepted = new AtomicReference<>();
        CountDownLatch registered = new CountDownLatch(1);
        NetherNetServerChannel server = (NetherNetServerChannel) bootstrap(() -> backend, signaling)
                .option(NetherChannelOption.NETHER_SERVER_ANSWER_DECORATOR, answer -> answer)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRegistered(ChannelHandlerContext ctx) {
                        accepted.set(ctx.channel());
                        registered.countDown();
                        ctx.fireChannelRegistered();
                    }
                })
                .bind(new InetSocketAddress(0)).sync().channel();
        try {
            server.acceptConnection(3, "v=0\r\n", "3");
            server.eventLoop().submit(() -> {}).sync();
            backend.listener.onAnswerReady("v=0\r\n");
            backend.listener.onTransportOpen();
            assertTrue(registered.await(2, TimeUnit.SECONDS));
            Channel child = accepted.get();
            child.eventLoop().submit(() -> {}).sync();
            assertTrue(child.isActive());

            server.close().sync();

            assertTrue(child.closeFuture().await(2, TimeUnit.SECONDS));
            assertFalse(child.isOpen());
            assertEquals(1, backend.session.closes.get());
            assertTrue(backend.closed);
            assertTrue(signaling.handlers.isEmpty());
        } finally {
            server.close().syncUninterruptibly();
            Channel child = accepted.get();
            if (child != null) {
                child.close().syncUninterruptibly();
            }
        }
    }

    private ServerBootstrap bootstrap(java.util.function.Supplier<WebRtcServerBackend> backendSupplier,
                                      NetherNetServerSignaling signaling) {
        return new ServerBootstrap()
                .group(group)
                .channelFactory(() -> new NetherNetServerChannel(backendSupplier, signaling))
                .childHandler(new ChannelInboundHandlerAdapter());
    }

    private static final class StubSignaling implements NetherNetServerSignaling {
        private final boolean failBind;
        volatile boolean bound;
        volatile boolean closed;
        boolean failSend;
        final Map<Long, SignalHandler> handlers = new ConcurrentHashMap<>();
        final LinkedBlockingQueue<SentSignal> sent = new LinkedBlockingQueue<>();

        private StubSignaling(boolean failBind) {
            this.failBind = failBind;
        }

        @Override
        public void bind(SocketAddress localAddress) throws ConnectException {
            if (failBind) {
                throw new ConnectException("port taken");
            }
            bound = true;
        }

        @Override
        public void setNewConnectionHandler(NewConnectionHandler handler) {
        }

        @Override
        public void setAdvertisementData(PongData pongData) {
        }

        @Override
        public void sendSignal(String targetNetworkId, String data) {
            sent.add(new SentSignal(targetNetworkId, data));
            if (failSend) {
                throw new IllegalStateException("Signaling unavailable");
            }
        }

        @Override
        public void setSignalHandler(long connectionId, SignalHandler handler) {
            handlers.put(connectionId, handler);
        }

        @Override
        public void removeSignalHandler(long connectionId) {
            handlers.remove(connectionId);
        }

        @Override
        public String getLocalNetworkId() {
            return "0";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class StubBackend implements WebRtcServerBackend {
        volatile boolean closed;
        volatile WebRtcSessionListener listener;
        boolean closeBeforeReturn;
        boolean failBeforeReturn;
        final StubSession session = new StubSession();

        @Override
        public WebRtcSession accept(String offerSdp, List<IceServerInfo> iceServers,
                                    WebRtcSessionListener listener, boolean fullIceAnswer) {
            this.listener = listener;
            if (closeBeforeReturn) {
                listener.onTransportClosed();
            }
            if (failBeforeReturn) {
                listener.onNegotiationFailed("SetRemoteDescription failed");
            }
            return session;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class StubSession implements WebRtcSession {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void send(ByteBuffer data) { }
        @Override public void addRemoteCandidate(String candidateSdp) { }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    private record SentSignal(String targetNetworkId, String data) {
    }
}
