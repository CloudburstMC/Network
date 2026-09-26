package org.cloudburstmc.netty.signaling.admission;

import io.netty.buffer.*;
import io.netty.channel.*;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.Test;

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
    void unreliableMessagesAreHeldToOneSegmentOfThePeersSize() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2));
        ByteBuf fitting = Unpooled.buffer(19_999).writeZero(19_999);
        ByteBuf oversized = Unpooled.buffer(20_000).writeZero(20_000);
        try {
            group.register(channel).sync();
            channel.setMaxOutboundMessageSize(20_000);
            ChannelFuture fits = channel.write(new NetherNetPacket(fitting, false));
            ChannelFuture over = channel.write(new NetherNetPacket(oversized, false)).await();
            assertInstanceOf(IllegalArgumentException.class, over.cause());
            assertFalse(fits.isDone(), "a message that fits one segment waits for the handshake");
            channel.close().sync();
            // Closing releases the queued message before failing its promise, after the close future completes
            assertFalse(fits.await().isSuccess());
            assertEquals(0, fitting.refCnt());
            assertEquals(0, oversized.refCnt());
        } finally {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void aMessageOverTheWriteLimitIsQueuedOnItsOwn() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        var channel = new AdmittedNetherNetChildChannel(null, null, new InetSocketAddress(1), new InetSocketAddress(2));
        int largeSize = 2 * AdmittedNetherNetChildChannel.WRITE_LIMIT;
        int tooLargeSize = NetherNetConstants.MAX_ASSEMBLED_MESSAGE_SIZE + 1;
        ByteBuf tooLarge = Unpooled.buffer(tooLargeSize).writerIndex(tooLargeSize);
        ByteBuf large = Unpooled.buffer(largeSize).writeZero(largeSize);
        ByteBuf behind = Unpooled.buffer(1).writeZero(1);
        try {
            group.register(channel).sync();
            ChannelFuture refused = channel.write(tooLarge);
            assertTrue(refused.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, refused.cause());
            ChannelFuture queued = channel.write(large);
            ChannelFuture full = channel.write(behind);
            assertTrue(full.await(5, TimeUnit.SECONDS), "nothing joins a queue already past the limit");
            assertInstanceOf(IllegalStateException.class, full.cause());
            assertFalse(queued.isDone(), "the large message waits for the handshake");
            channel.close().sync();
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            assertFalse(queued.isSuccess());
            assertEquals(0, tooLarge.refCnt());
            assertEquals(0, large.refCnt());
            assertEquals(0, behind.refCnt());
        } finally {
            channel.close().awaitUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void preHandshakeWritesAreBoundedPromisesFailAndBuffersReleaseOnClose() throws Exception {
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
            long pending = channel.unsafe().outboundBuffer().totalPendingWriteBytes();
            assertTrue(pending > 0 && pending <= AdmittedNetherNetChildChannel.WRITE_LIMIT, "pending=" + pending);
            assertFalse(channel.isWritable());
            assertTrue(writes.stream().anyMatch(f -> f.isDone() && !f.isSuccess()));
            assertTrue(writes.stream().anyMatch(f -> !f.isDone())); // acceptance waits for actual native send
            int oversize = channel.getMaxOutboundMessageSize();
            ByteBuf unrel = Unpooled.buffer(oversize).writeZero(oversize);
            buffers.add(unrel);
            ChannelFuture oversized = channel.write(new NetherNetPacket(unrel, false)).await();
            assertFalse(oversized.isSuccess());
            assertInstanceOf(IllegalArgumentException.class, oversized.cause());
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
}
