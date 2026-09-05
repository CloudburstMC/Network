package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetBackpressureTest {
    @Test
    @Timeout(15)
    void concurrentEngineDrainsDoNotStrandPendingWrites() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        DrainingChannel channel = new DrainingChannel();
        Thread engine = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    channel.onEngineBytesSent(channel.sentBytes.take());
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "test-engine-drain");
        engine.setDaemon(true);
        byte[] atWatermark = new byte[2 * 1024 * 1024];
        try {
            group.register(channel).sync();
            engine.start();
            for (int attempt = 0; attempt < 2000; attempt++) {
                channel.write(Unpooled.wrappedBuffer(atWatermark));
                var tail = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1}));
                assertTrue(tail.await(3, TimeUnit.SECONDS), "A drained engine must resume queued writes");
                assertTrue(tail.isSuccess(), () -> String.valueOf(tail.cause()));
            }
        } finally {
            channel.close().syncUninterruptibly();
            engine.interrupt();
            engine.join(1000);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static class DrainingChannel extends NetherNetChannel {
        final LinkedBlockingQueue<Integer> sentBytes = new LinkedBlockingQueue<>();

        DrainingChannel() {
            super(null, new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("127.0.0.1", 19133));
            config = new DefaultNetherChannelConfig(this);
            markTransportOpen();
        }

        @Override protected void sendFramed(ByteBuf framed) {
            sentBytes.add(framed.readableBytes());
        }

        @Override protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
                    promise.setFailure(new UnsupportedOperationException());
                }
            };
        }
    }
}
