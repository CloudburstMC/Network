package org.cloudburstmc.netty.signaling.admission;

import org.cloudburstmc.netty.channel.nethernet.NetherNetPacket;
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
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void writesBeforeActivationWaitUnflushedAndReleaseOnClose() throws Exception {
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
            assertTrue(writes.stream().noneMatch(ChannelFuture::isDone), "nothing goes out before activation");
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
        int largest = NetherNetConstants.MAX_SEGMENTS * (NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1);
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
}
