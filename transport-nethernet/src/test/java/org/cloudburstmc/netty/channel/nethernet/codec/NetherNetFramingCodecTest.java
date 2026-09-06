package org.cloudburstmc.netty.channel.nethernet.codec;

import org.cloudburstmc.netty.channel.nethernet.NetherNetUnreliableFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetFramingCodecTest {

    private static final int MAX_MESSAGE_SIZE = 100; // 99 payload bytes per fragment

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = new EmbeddedChannel(new NetherNetFramingCodec(MAX_MESSAGE_SIZE));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    private static ByteBuf framed(int header, byte[] payload) {
        ByteBuf buf = Unpooled.buffer(1 + payload.length);
        buf.writeByte(header);
        buf.writeBytes(payload);
        return buf;
    }

    private static byte[] bytes(int length, long seed) {
        byte[] data = new byte[length];
        java.util.Random random = new java.util.Random(seed);
        random.nextBytes(data);
        return data;
    }

    private static byte[] readAll(ByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        return data;
    }

    @Test
    void defaultAndCappedFramesIncludeTheHeaderAndRoundTripLargeMessages() {
        for (int configured : new int[]{0, 262144, Integer.MAX_VALUE}) {
            int limit = configured == 0 ? 65536 : 262144;
            EmbeddedChannel frames = new EmbeddedChannel(new NetherNetFramingCodec(configured));
            byte[] payload = bytes(3 * 1024 * 1024, 19);
            try {
                assertTrue(frames.writeOutbound(Unpooled.wrappedBuffer(payload)));
                int count = 1 + (payload.length - 1) / (limit - 1);
                for (int remaining = count - 1; remaining >= 0; remaining--) {
                    ByteBuf fragment = frames.readOutbound();
                    assertNotNull(fragment);
                    try {
                        assertEquals(remaining, fragment.getUnsignedByte(fragment.readerIndex()));
                        assertTrue(fragment.readableBytes() <= limit);
                        if (remaining > 0) {
                            assertEquals(limit, fragment.readableBytes());
                        }
                        frames.writeInbound(fragment.retain());
                    } finally {
                        fragment.release();
                    }
                }
                assertNull(frames.readOutbound());
                ByteBuf decoded = frames.readInbound();
                assertNotNull(decoded);
                assertArrayEquals(payload, readAll(decoded));
            } finally {
                frames.finishAndReleaseAll();
            }
        }
    }

    @Test
    void singleMessagePassesThrough() {
        byte[] payload = bytes(50, 1);
        channel.writeInbound(framed(0, payload));

        ByteBuf out = channel.readInbound();
        assertNotNull(out);
        assertArrayEquals(payload, readAll(out));
        assertNull(channel.readInbound());
    }

    @Test
    void fragmentedMessageReassemblesInOrder() {
        byte[] part1 = bytes(99, 2);
        byte[] part2 = bytes(99, 3);
        byte[] part3 = bytes(40, 4);

        channel.writeInbound(framed(2, part1));
        assertNull(channel.readInbound());
        channel.writeInbound(framed(1, part2));
        assertNull(channel.readInbound());
        channel.writeInbound(framed(0, part3));

        ByteBuf out = channel.readInbound();
        assertNotNull(out);
        byte[] expected = new byte[part1.length + part2.length + part3.length];
        System.arraycopy(part1, 0, expected, 0, part1.length);
        System.arraycopy(part2, 0, expected, part1.length, part2.length);
        System.arraycopy(part3, 0, expected, part1.length + part2.length, part3.length);
        assertArrayEquals(expected, readAll(out));
    }

    @Test
    void unreliableMessagesDoNotCompleteReliableAssemblies() {
        ByteBuf first = framed(2, new byte[]{1});
        ByteBuf middle = framed(1, new byte[]{2});
        ByteBuf last = framed(0, new byte[]{3});
        ByteBuf unreliable = framed(0, new byte[]{42});
        ByteBuf another = framed(0, new byte[]{43});

        assertFalse(channel.writeInbound(first));
        assertTrue(channel.writeInbound(new NetherNetUnreliableFrame(unreliable)));
        assertArrayEquals(new byte[]{42}, readAll(channel.readInbound()));
        assertEquals(0, unreliable.refCnt());
        assertFalse(channel.writeInbound(middle));
        assertTrue(channel.writeInbound(new NetherNetUnreliableFrame(another)));
        assertArrayEquals(new byte[]{43}, readAll(channel.readInbound()));
        assertEquals(0, another.refCnt());
        assertTrue(channel.writeInbound(last));
        assertArrayEquals(new byte[]{1, 2, 3}, readAll(channel.readInbound()));
        assertEquals(0, first.refCnt());
        assertEquals(0, middle.refCnt());
        assertEquals(0, last.refCnt());
        assertNull(channel.readInbound());
    }

    @Test
    void invalidUnreliableMessagesAreReleasedWithoutDiscardingReliableAssembly() {
        assertFalse(channel.writeInbound(framed(1, new byte[]{1})));
        for (ByteBuf invalid : List.of(
                Unpooled.buffer(1),
                framed(0, new byte[0]),
                framed(1, new byte[]{9}),
                framed(255, new byte[]{9}),
                framed(0, new byte[NetherNetFramingCodec.MAX_REASSEMBLED_SIZE + 1]))) {
            assertFalse(channel.writeInbound(new NetherNetUnreliableFrame(invalid)));
            assertEquals(0, invalid.refCnt());
        }
        assertTrue(channel.writeInbound(framed(0, new byte[]{2})));
        assertArrayEquals(new byte[]{1, 2}, readAll(channel.readInbound()));
        assertNull(channel.readInbound());
    }

    @Test
    void outOfOrderFragmentDropsMessage() {
        channel.writeInbound(framed(3, bytes(99, 5)));
        // Expected countdown 2, send 1 instead.
        channel.writeInbound(framed(1, bytes(99, 6)));
        assertNull(channel.readInbound());

        // A fresh message afterwards still works.
        byte[] payload = bytes(10, 7);
        channel.writeInbound(framed(0, payload));
        ByteBuf out = channel.readInbound();
        assertNotNull(out);
        assertArrayEquals(payload, readAll(out));
    }

    @Test
    void oversizedReassemblyIsDropped() {
        // Each fragment far below the 16MB cap individually, but the codec
        // must stop accumulating once the total crosses it. Use a large
        // fixed-size codec to keep the fragment count small.
        EmbeddedChannel big = new EmbeddedChannel(new NetherNetFramingCodec(9 * 1024 * 1024));
        byte[] chunk = new byte[8 * 1024 * 1024];
        big.writeInbound(framed(2, chunk));
        big.writeInbound(framed(1, chunk));
        big.writeInbound(framed(0, new byte[16]));
        assertNull(big.readInbound());
        big.finishAndReleaseAll();
    }

    @Test
    void oversizedSingleMessageIsDroppedAndReleased() {
        ByteBuf oversized = Unpooled.buffer(NetherNetFramingCodec.MAX_REASSEMBLED_SIZE + 2);
        oversized.writeZero(oversized.capacity());

        assertFalse(channel.writeInbound(oversized));
        assertEquals(0, oversized.refCnt());
        assertNull(channel.readInbound());
    }

    @Test
    void rejectsMessageSizesThatCannotCarryPayload() {
        assertThrows(IllegalArgumentException.class, () -> new NetherNetFramingCodec(1));
        assertThrows(IllegalArgumentException.class, () -> new NetherNetFramingCodec(-1));
    }

    @Test
    void smallOutboundGetsZeroHeader() {
        byte[] payload = bytes(42, 8);
        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(payload)));

        ByteBuf out = channel.readOutbound();
        assertNotNull(out);
        assertEquals(0, out.readUnsignedByte());
        assertArrayEquals(payload, readAll(out));
        assertNull(channel.readOutbound());
    }

    @Test
    void largeOutboundFragmentsWithCountdown() {
        byte[] payload = bytes(99 * 2 + 50, 9); // 3 fragments
        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(payload)));

        int[] expectedHeaders = {2, 1, 0};
        int offset = 0;
        for (int expectedHeader : expectedHeaders) {
            ByteBuf out = channel.readOutbound();
            assertNotNull(out);
            assertEquals(expectedHeader, out.readUnsignedByte());
            byte[] chunk = readAll(out);
            for (byte b : chunk) {
                assertEquals(payload[offset++], b);
            }
        }
        assertEquals(payload.length, offset);
        assertNull(channel.readOutbound());
    }

    @Test
    void fragmentedWriteWaitsForEveryFragment() {
        AtomicReference<ChannelPromise> first = new AtomicReference<>();
        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (first.compareAndSet(null, promise)) {
                    ReferenceCountUtil.release(msg);
                } else {
                    ctx.write(msg, promise);
                }
            }
        });

        ChannelPromise aggregate = channel.newPromise();
        channel.writeAndFlush(Unpooled.wrappedBuffer(bytes(250, 14)), aggregate);

        assertNotNull(first.get());
        assertFalse(aggregate.isDone(), "The last fragment completing does not finish earlier writes");
        first.get().setSuccess();
        assertTrue(aggregate.isSuccess());
    }

    @Test
    void earlierFragmentFailureFailsTheMessagePromise() {
        IllegalStateException failure = new IllegalStateException("first fragment rejected");
        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            private boolean first = true;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (first) {
                    first = false;
                    ReferenceCountUtil.release(msg);
                    promise.setFailure(failure);
                } else {
                    ctx.write(msg, promise);
                }
            }
        });

        ChannelPromise aggregate = channel.newPromise();
        channel.writeAndFlush(Unpooled.wrappedBuffer(bytes(250, 15)), aggregate);

        assertTrue(aggregate.isDone());
        assertFalse(aggregate.isSuccess());
        assertSame(failure, aggregate.cause());
    }

    @Test
    void outboundRoundTripsThroughInbound() {
        byte[] payload = bytes(1000, 10); // 11 fragments at 99 bytes
        assertTrue(channel.writeOutbound(Unpooled.wrappedBuffer(payload)));

        ByteBuf out;
        while ((out = channel.readOutbound()) != null) {
            channel.writeInbound(out);
        }

        ByteBuf reassembled = channel.readInbound();
        assertNotNull(reassembled);
        assertArrayEquals(payload, readAll(reassembled));
    }

    @Test
    void tooManyFragmentsFailsThePromise() {
        // 100 byte max size → 99 payload per fragment → 256 fragments max.
        byte[] payload = new byte[99 * 256 + 1];
        ThreadLocalRandom.current().nextBytes(payload);

        assertThrows(IllegalArgumentException.class,
                () -> channel.writeOutbound(Unpooled.wrappedBuffer(payload)));
        assertNull(channel.readOutbound());
    }

    @Test
    void partialAssemblyReleasedOnClose() {
        channel.writeInbound(framed(5, bytes(99, 11)));
        channel.writeInbound(framed(4, bytes(99, 12)));
        // Close with an assembly in flight; finishAndReleaseAll in tearDown
        // plus the leak detector would flag an unreleased composite.
        assertFalse(channel.finish());
        assertNull(channel.readInbound());
    }

    @Test
    void emptyFinalFragmentCompletesMessage() {
        byte[] part = bytes(99, 13);
        channel.writeInbound(framed(1, part));
        channel.writeInbound(framed(0, new byte[0]));

        ByteBuf out = channel.readInbound();
        assertNotNull(out);
        assertArrayEquals(part, readAll(out));
    }
}
