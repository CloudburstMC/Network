package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetChannelRttSamplerTest {

    private final NioEventLoopGroup group = new NioEventLoopGroup(1);

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void startsSamplerWhenTransportOpensAfterRegistration() throws Exception {
        TestNetherNetChannel channel = new TestNetherNetChannel();
        try {
            group.register(channel).sync();
            channel.openTransport();

            assertTrue(channel.sampled.await(2, TimeUnit.SECONDS));
            assertEquals(13, channel.rttMillis());
            assertEquals(1, channel.sampleRequests.get());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void startsSamplerWhenTransportOpensBeforeRegistration() throws Exception {
        TestNetherNetChannel channel = new TestNetherNetChannel();
        try {
            channel.openTransport();
            group.register(channel).sync();

            assertTrue(channel.sampled.await(2, TimeUnit.SECONDS));
            assertEquals(13, channel.rttMillis());
            assertEquals(1, channel.sampleRequests.get());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void synchronousCloseFromChannelActiveCancelsSampler() throws Exception {
        TestNetherNetChannel channel = new TestNetherNetChannel();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.close();
            }
        });

        group.register(channel).sync();
        channel.openTransport();
        channel.closeFuture().sync();

        assertFalse(channel.sampled.await(1200, TimeUnit.MILLISECONDS));
        assertEquals(0, channel.sampleRequests.get());
    }

    private static final class TestNetherNetChannel extends NetherNetChannel {
        private final CountDownLatch sampled = new CountDownLatch(1);
        private final AtomicInteger sampleRequests = new AtomicInteger();

        private TestNetherNetChannel() {
            super(null, new InetSocketAddress("127.0.0.1", 19132),
                    new InetSocketAddress("127.0.0.1", 19133));
            this.config = new DefaultNetherChannelConfig(this);
        }

        private void openTransport() {
            markTransportOpen();
        }

        @Override
        protected void requestRttSample(DoubleConsumer callback) {
            sampleRequests.incrementAndGet();
            callback.accept(12.6);
            sampled.countDown();
        }

        @Override
        protected void sendFramed(ByteBuf framed) {
        }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                    promise.setFailure(new UnsupportedOperationException("Test channel cannot connect"));
                }
            };
        }
    }
}
