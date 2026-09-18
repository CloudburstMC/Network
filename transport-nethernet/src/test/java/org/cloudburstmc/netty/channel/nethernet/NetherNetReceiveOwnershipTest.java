package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetReceiveOwnershipTest {
    @Test
    void messagesMetricFailureReleasesCompletedPacket() throws Exception {
        assertReleasedAfter(new NetherChannelMetrics() {
            public void messagesIn(int count) { throw new IllegalStateException("metric failed"); }
        }, IllegalStateException.class);
    }

    @Test
    void bytesMetricErrorReleasesCompletedPacket() throws Exception {
        assertReleasedAfter(new NetherChannelMetrics() {
            public void bytesIn(int count) { throw new AssertionError("metric failed"); }
        }, AssertionError.class);
    }

    private void assertReleasedAfter(NetherChannelMetrics metrics, Class<? extends Throwable> error) throws Exception {
        var allocator = new TrackingAllocator();
        var channel = new NetherNetChildChannel(null, null, new InetSocketAddress(0), new InetSocketAddress(0));
        channel.config().setAllocator(allocator);
        channel.config().setOption(NetherChannelOption.NETHER_METRICS, metrics);
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            Method callback = NetherNetChannel.class.getDeclaredMethod("onMessage", NetherNetMessageAssembler.class,
                    ByteBuffer.class);
            callback.setAccessible(true);
            var thrown = assertThrows(InvocationTargetException.class, () -> callback.invoke(channel, assembler,
                    ByteBuffer.allocateDirect(4).put(new byte[]{0, 1, 2, 3}).flip()));
            assertInstanceOf(error, thrown.getCause());
            assertEquals(1, allocator.buffers.size());
            allocator.assertReleased();
        } finally {
            for (var buffer : allocator.buffers) if (buffer.refCnt() != 0) buffer.release(buffer.refCnt());
            channel.unsafe().closeForcibly();
        }
    }
}
