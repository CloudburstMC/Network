package org.cloudburstmc.netty.signalling.admission;

import io.netty.buffer.*;
import io.netty.channel.*;
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
            ByteBuf unrel = Unpooled.buffer(10_000).writeZero(10_000);
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
