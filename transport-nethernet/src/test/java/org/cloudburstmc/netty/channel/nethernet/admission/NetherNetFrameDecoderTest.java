package org.cloudburstmc.netty.channel.nethernet.admission;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetFrameDecoderTest {
    @Test
    void channelsStayIndependentAndPartialCloseReleasesAssembly() {
        var decoder = new NetherNetFrameDecoder();
        assertNull(decoder.decode(new byte[]{1, 10, 11}, true));
        assertArrayEquals(new byte[]{99}, decoder.decode(new byte[]{0, 99}, false));
        assertArrayEquals(new byte[]{10, 11, 12}, decoder.decode(new byte[]{0, 12}, true));
        assertEquals(0, decoder.retainedBytes());
        decoder.decode(new byte[]{1, 42}, true);
        decoder.clear();
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void malformedOutOfOrderAndOverLimitAreRejectedWithoutLeaking() {
        var decoder = new NetherNetFrameDecoder();
        decoder.decode(new byte[]{2, 1}, true);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[]{0, 2}, true));
        assertEquals(0, decoder.retainedBytes());
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[]{(byte) 255, 1}, true));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[10001], true));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[]{0}, true));
        for (int i = 26; i > 0; i--) {
            byte[] frame = new byte[10000];
            frame[0] = (byte) i;
            assertNull(decoder.decode(frame, true));
        }
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[10000], true));
        assertEquals(0, decoder.retainedBytes());
    }

    @Test
    void unreliableFragmentsCannotBeMisassembledAcrossReordering() {
        var decoder = new NetherNetFrameDecoder();
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[]{1, 7}, false));
        assertEquals(0, decoder.retainedBytes());
        assertArrayEquals(new byte[]{8}, decoder.decode(new byte[]{0, 8}, false));
    }
}
