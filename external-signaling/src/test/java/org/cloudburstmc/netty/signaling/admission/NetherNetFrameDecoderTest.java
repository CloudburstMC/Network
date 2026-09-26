package org.cloudburstmc.netty.signaling.admission;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.cloudburstmc.netty.signaling.admission.NetherNetFrameDecoder.FRAME_LIMIT;
import static org.cloudburstmc.netty.signaling.admission.NetherNetFrameDecoder.MESSAGE_LIMIT;
import static org.junit.jupiter.api.Assertions.*;

class NetherNetFrameDecoderTest {
    private static ByteBuf frame(int... bytes) {
        ByteBuf buffer = Unpooled.buffer(bytes.length);
        for (int value : bytes) {
            buffer.writeByte(value);
        }
        return buffer;
    }

    private static byte[] drain(ByteBuf message) {
        assertNotNull(message);
        byte[] bytes = new byte[message.readableBytes()];
        message.readBytes(bytes);
        message.release();
        return bytes;
    }

    /** Frames must be released once neither the decoder nor a completed message owns them. */
    private static ByteBuf consumed(ByteBuf frame) {
        assertEquals(0, frame.refCnt(), "decoder must release the frame it was given");
        return frame;
    }

    @Test
    void channelsStayIndependentAndPartialCloseReleasesAssembly() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf first = frame(1, 10, 11);
        assertNull(decoder.decode(first, true));
        assertEquals(1, first.refCnt(), "partial assembly owns the fragment");
        assertArrayEquals(new byte[]{99}, drain(decoder.decode(frame(0, 99), false)));
        assertArrayEquals(new byte[]{10, 11, 12}, drain(decoder.decode(frame(0, 12), true)));
        consumed(first);
        assertEquals(0, decoder.retainedBytes());
        ByteBuf partial = frame(1, 42);
        decoder.decode(partial, true);
        assertNotEquals(0, decoder.retainedBytes());
        decoder.clear();
        assertEquals(0, decoder.retainedBytes());
        consumed(partial);
    }

    @Test
    void malformedOutOfOrderAndOverLimitAreRejectedWithoutLeaking() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf partial = frame(2, 1);
        decoder.decode(partial, true);
        ByteBuf outOfOrder = frame(0, 2);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(outOfOrder, true));
        consumed(outOfOrder);
        consumed(partial);
        assertEquals(0, decoder.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(frame(255, 1), true));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(Unpooled.buffer(MESSAGE_LIMIT + 1)
                .writerIndex(MESSAGE_LIMIT + 1), true));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(frame(0), true));
        for (int i = 26; i > 0; i--) {
            ByteBuf fragment = Unpooled.buffer(10000).writeByte(i).writerIndex(10000);
            assertNull(decoder.decode(fragment, true));
        }
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(Unpooled.buffer(10000)
                .writerIndex(10000), true));
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void anUnfragmentedMessageMayFillTheAdvertisedSize() {
        var decoder = new NetherNetFrameDecoder();
        for (int length : new int[]{FRAME_LIMIT + 1, MESSAGE_LIMIT}) {
            ByteBuf frame = Unpooled.buffer(length).writeByte(0);
            for (int b = 1; b < length; b++) {
                frame.writeByte(b % 251);
            }
            byte[] message = drain(decoder.decode(frame, true));
            assertEquals(length - 1, message.length);
            assertEquals((byte) ((length - 1) % 251), message[length - 2]);
            consumed(frame);
        }
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void largeFragmentedMessageReassemblesByteForByte() {
        var decoder = new NetherNetFrameDecoder();
        int fragments = 4, payload = FRAME_LIMIT - 1;
        byte[] expected = new byte[fragments * payload];
        ByteBuf last = null;
        for (int i = 0; i < fragments; i++) {
            ByteBuf fragment = Unpooled.buffer(FRAME_LIMIT).writeByte(fragments - 1 - i);
            for (int b = 0; b < payload; b++) {
                byte value = (byte) ((i * 31 + b) % 251);
                expected[i * payload + b] = value;
                fragment.writeByte(value);
            }
            last = decoder.decode(fragment, true);
            if (i < fragments - 1) {
                assertNull(last, "message completed early");
                // Retained components must account for every payload received so far.
                assertTrue(decoder.retainedBytes() >= (i + 1) * payload);
            }
        }
        assertArrayEquals(expected, drain(last));
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void unreliableFragmentsCannotBeMisassembledAcrossReordering() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf fragmented = frame(1, 7);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(fragmented, false));
        consumed(fragmented);
        assertEquals(0, decoder.retainedBytes());
        assertArrayEquals(new byte[]{8}, drain(decoder.decode(frame(0, 8), false)));
    }

    @Test
    void fragmentedMessageKeepsOwnedFramesWithoutConsolidatingOrCopying() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf[] frames = new ByteBuf[27];
        ByteBuf message = null;
        try {
            for (int i = 0; i < frames.length; i++) {
                frames[i] = frame(frames.length - i - 1, i);
                message = decoder.decode(frames[i], true);
            }
            CompositeByteBuf composite = assertInstanceOf(CompositeByteBuf.class, message);
            assertEquals(frames.length, composite.numComponents(), "must exceed the default 16 without copying");
            decoder.clear();
            for (int i = 0; i < frames.length; i++) {
                assertEquals(1, frames[i].refCnt(), "completed message owns each frame");
                assertSame(frames[i].array(), composite.internalComponent(i).array());
                assertEquals(i, message.getUnsignedByte(i));
            }
        } finally {
            if (message != null) {
                message.release();
            }
            decoder.clear();
        }
        for (ByteBuf frame : frames) {
            consumed(frame);
        }
    }

    @Test
    void malformedFrameReleasesAnExistingPartialMessage() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf first = frame(1, 7);
        assertNull(decoder.decode(first, true));
        ByteBuf malformed = frame(0);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(malformed, true));
        consumed(first);
        consumed(malformed);
        assertEquals(0, decoder.retainedBytes());
    }
}
