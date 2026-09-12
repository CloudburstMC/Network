package org.cloudburstmc.netty.signalling.admission;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.cloudburstmc.netty.signalling.admission.NetherNetFrameDecoder.FRAME_LIMIT;
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

    /** Every frame handed to the decoder is owned by it, so nothing may survive the call. */
    private static ByteBuf consumed(ByteBuf frame) {
        assertEquals(0, frame.refCnt(), "decoder must release the frame it was given");
        return frame;
    }

    @Test
    void channelsStayIndependentAndPartialCloseReleasesAssembly() {
        var decoder = new NetherNetFrameDecoder();
        ByteBuf first = frame(1, 10, 11);
        assertNull(decoder.decode(first, true));
        consumed(first);
        assertArrayEquals(new byte[]{99}, drain(decoder.decode(frame(0, 99), false)));
        assertArrayEquals(new byte[]{10, 11, 12}, drain(decoder.decode(frame(0, 12), true)));
        assertEquals(0, decoder.retainedBytes());
        decoder.decode(frame(1, 42), true);
        assertNotEquals(0, decoder.retainedBytes());
        decoder.clear();
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void malformedOutOfOrderAndOverLimitAreRejectedWithoutLeaking() {
        var decoder = new NetherNetFrameDecoder();
        decoder.decode(frame(2, 1), true);
        ByteBuf outOfOrder = frame(0, 2);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(outOfOrder, true));
        consumed(outOfOrder);
        assertEquals(0, decoder.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(frame(255, 1), true));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(Unpooled.buffer(10001)
                .writerIndex(10001), true));
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
                // the assembly must grow past the first fragment rather than stay at its initial size
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
}
