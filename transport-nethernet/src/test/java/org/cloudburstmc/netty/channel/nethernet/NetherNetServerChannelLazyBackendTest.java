package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSessionListener;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
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
        }

        @Override
        public void setSignalHandler(long connectionId, SignalHandler handler) {
        }

        @Override
        public void removeSignalHandler(long connectionId) {
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

        @Override
        public WebRtcSession accept(String offerSdp, List<IceServerInfo> iceServers,
                                    WebRtcSessionListener listener, boolean fullIceAnswer) {
            throw new UnsupportedOperationException("not under test");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
