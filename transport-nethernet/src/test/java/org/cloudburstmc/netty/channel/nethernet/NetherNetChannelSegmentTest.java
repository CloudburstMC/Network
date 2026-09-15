package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetChannelSegmentTest {
    private final TrackingAllocator allocator = new TrackingAllocator();

    /** Copies each segment out, as the native send does, since the buffer is recycled at once. */
    private List<byte[]> segmentsOf(ByteBuf message, int maxPayload) {
        List<byte[]> sent = new ArrayList<>();
        int count = NetherNetChannel.segment(message, allocator, maxPayload, view -> {
            byte[] copy = new byte[view.remaining()];
            view.get(copy);
            sent.add(copy);
        });
        assertEquals(count, sent.size());
        return sent;
    }

    private static ByteBuffer wire(byte[] bytes) {
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
    }

    @Test
    void oneSegmentCarriesAZeroCountdownAheadOfThePayload() {
        List<byte[]> sent = segmentsOf(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}), 16);

        assertEquals(1, sent.size());
        assertArrayEquals(new byte[]{0, 1, 2, 3}, sent.get(0));
        allocator.assertReleased();
    }

    @Test
    void aLongMessageCountsDownToZero() {
        List<byte[]> sent = segmentsOf(Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5}), 2);

        assertEquals(3, sent.size());
        assertArrayEquals(new byte[]{2, 1, 2}, sent.get(0));
        assertArrayEquals(new byte[]{1, 3, 4}, sent.get(1));
        assertArrayEquals(new byte[]{0, 5}, sent.get(2));
        allocator.assertReleased();
    }

    @Test
    void whatIsSegmentedReassemblesToWhatWentIn() {
        byte[] payload = new byte[5000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        List<byte[]> sent = segmentsOf(Unpooled.wrappedBuffer(payload), 600);
        assertEquals(9, sent.size());

        ByteBuf assembled = null;
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            for (byte[] segment : sent) {
                assembled = assembler.decode(wire(segment), allocator);
            }
        }
        assertNotNull(assembled, "the last segment should complete the message");
        try {
            assertArrayEquals(payload, ByteBufUtil.getBytes(assembled));
        } finally {
            assembled.release();
        }
        allocator.assertReleased();
    }

    @Test
    void theMessageKeepsItsOwnIndexes() {
        ByteBuf message = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        segmentsOf(message, 2);

        assertEquals(0, message.readerIndex());
        assertEquals(3, message.readableBytes());
    }

    @Test
    void anEmptyMessageSendsNothingAndAllocatesNothing() {
        assertEquals(0, segmentsOf(Unpooled.EMPTY_BUFFER, 16).size());
        assertTrue(allocator.buffers.isEmpty());
    }

    @Test
    void aSenderThatThrowsStillGivesTheSegmentBack() {
        ByteBuf message = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});

        assertThrows(IllegalStateException.class, () -> NetherNetChannel.segment(
                message, allocator, 2, view -> {
                    throw new IllegalStateException("send failed");
                }));
        allocator.assertReleased();
    }
}
