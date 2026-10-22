package org.cloudburstmc.netty.signaling.admission;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeAdmissionWriteTest {
    @Test
    void nettyClosureCannotHideNativeTeardownFailure() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var failure = new IllegalStateException("deterministically stalled native teardown");
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2),
                peer -> {
                    throw failure;
                });
        try {
            group.register(channel).sync();
            ChannelFuture close = channel.close().await();
            assertSame(failure, close.cause());
            assertTrue(channel.closeFuture().await().isSuccess(), "Netty closure alone conceals teardown failure");
            var terminal = channel.nativeTermination().toCompletableFuture();
            assertTrue(terminal.isCompletedExceptionally());
            assertSame(failure,
                    assertThrows(CompletionException.class, terminal::join).getCause());
            assertEquals(0, channel.queuedFrames());
            assertEquals(0, channel.retainedAssemblyBytes());
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void preHandshakeWritesWaitForTheNativeSendAndReleaseOnClose() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2));
        List<ByteBuf> buffers = new ArrayList<>();
        List<ChannelFuture> writes = new ArrayList<>();
        try {
            group.register(channel).sync();
            for (int i = 0; i < 8; i++) {
                ByteBuf buffer = Unpooled.buffer(200_000).writeZero(200_000);
                buffers.add(buffer);
                writes.add(channel.write(buffer));
            }
            group.next().submit(() -> {
            }).sync();
            assertFalse(channel.isWritable(), "past the high water mark");
            assertTrue(writes.stream().noneMatch(ChannelFuture::isDone)); // acceptance waits for actual native send
            channel.close().sync();
            channel.eventLoop().submit(() -> {
            }).sync();
            for (ChannelFuture write : writes) {
                assertTrue(write.isDone());
                assertFalse(write.isSuccess());
            }
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt());
            }
        } finally {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void oversizedMessagesAreRefusedWithoutEndingTheConnection() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2));
        int largest = NetherNetConstants.MAX_SEGMENTS * (NetherNetFrameDecoder.FRAME_LIMIT - 1);
        ByteBuf reliable = Unpooled.buffer(largest + 1).writerIndex(largest + 1);
        ByteBuf unreliable = Unpooled.buffer(10_000).writeZero(10_000);
        try {
            group.register(channel).sync();
            assertInstanceOf(IllegalArgumentException.class, channel.write(reliable).await().cause());
            assertInstanceOf(IllegalArgumentException.class,
                    channel.write(new NetherNetPacket(unreliable, false)).await().cause());
            assertTrue(channel.isOpen());
            assertEquals(0, reliable.refCnt());
            assertEquals(0, unreliable.refCnt());
        } finally {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    @Timeout(15)
    void overflowingTheSendQueueEndsTheConnectionAsQueueTooLong() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2));
        List<Object> events = new ArrayList<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                events.add(event);
            }
        });
        int largest = NetherNetConstants.MAX_SEGMENTS * (NetherNetFrameDecoder.FRAME_LIMIT - 1);
        // One array behind every message, so the queue fills without holding its limit in memory
        byte[] shared = new byte[largest];
        int count = AdmittedNetherNetChildChannel.WRITE_LIMIT / largest + 4;
        List<ByteBuf> buffers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            buffers.add(Unpooled.wrappedBuffer(shared));
        }
        try {
            group.register(channel).sync();
            // Written from the event loop, as encoded batches are, so the burst outruns any close
            List<ChannelFuture> writes = channel.eventLoop().submit(() -> buffers.stream().map(channel::write).toList())
                    .get(5, TimeUnit.SECONDS);

            assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
            assertEquals(List.of(TransportDisconnectReason.QUEUE_TOO_LONG), events);
            assertTrue(writes.stream().anyMatch(write -> write.cause() instanceof IllegalStateException));
            for (ChannelFuture write : writes) {
                assertTrue(write.await(5, TimeUnit.SECONDS));
                assertFalse(write.isSuccess());
            }
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt());
            }
        } finally {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
