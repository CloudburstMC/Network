package org.cloudburstmc.netty.channel.nethernet.codec;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetServerDataCodecTest {
    private static final PongData STABLE_DATA = new PongData("Dedicated Server", "Bedrock level", 0, 0, 10,
            false, false, 2, 4, true, false, "3e8a5e7a932c5aa4");

    @Test
    void encodingMatchesTheStableBedrockCapture() throws Exception {
        byte[] capture = stableCapture();
        assertEquals(64, capture.length);
        ByteBuf buffer = Unpooled.buffer(capture.length, capture.length);
        try {
            NetherNetServerDataCodec.encode(buffer, STABLE_DATA);
            assertArrayEquals(capture, ByteBufUtil.getBytes(buffer));
            assertEquals(1, buffer.refCnt());
        } finally {
            buffer.release();
        }
    }

    @Test
    void decodingTheStableCaptureRespectsTheReaderIndexAndOwnership() throws Exception {
        ByteBuf buffer = Unpooled.buffer().writeZero(3).writeBytes(stableCapture()).skipBytes(3);
        try {
            assertEquals(STABLE_DATA, NetherNetServerDataCodec.decode(buffer));
            assertFalse(buffer.isReadable());
            assertEquals(1, buffer.refCnt());
        } finally {
            buffer.release();
        }
    }

    @Test
    void everyTruncationOfTheStableCaptureIsRejected() throws Exception {
        byte[] capture = stableCapture();
        for (int length = 0; length < capture.length; length++) {
            ByteBuf buffer = Unpooled.wrappedBuffer(capture, 0, length);
            try {
                assertThrows(RuntimeException.class, () -> NetherNetServerDataCodec.decode(buffer),
                        "Truncated at byte " + length);
            } finally {
                buffer.release();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 255})
    void unsupportedVersionsAreNotMisreadAsVersionSix(int version) throws Exception {
        ByteBuf buffer = Unpooled.wrappedBuffer(stableCapture());
        buffer.setByte(0, version);
        try {
            assertThrows(IllegalArgumentException.class, () -> NetherNetServerDataCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {41, 42, 43, 44})
    void booleanFieldsRejectValuesOtherThanZeroAndOne(int offset) throws Exception {
        ByteBuf buffer = Unpooled.wrappedBuffer(stableCapture());
        buffer.setByte(offset, 2);
        try {
            assertThrows(IllegalArgumentException.class, () -> NetherNetServerDataCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void trailingBytesAreRejected() throws Exception {
        ByteBuf buffer = Unpooled.buffer().writeBytes(stableCapture()).writeByte(0);
        try {
            assertThrows(IllegalArgumentException.class, () -> NetherNetServerDataCodec.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void legacyConstructionKeepsTheNonceAcrossAdvertisementUpdates() {
        PongData legacy = new PongData("Server", "World", 0, 0, 10, false, false, 2, 4);
        PongData.Builder builder = new PongData.Builder();
        assertEquals(legacy, builder.build());
        assertTrue(legacy.acceptsOnlineAuth());
        assertTrue(legacy.acceptsSelfSignedAuth());
        assertTrue(legacy.nonce().matches("[0-9a-f]{1,16}"));
        assertEquals(legacy.nonce(), builder.setPlayerCount(5).build().nonce());

        PongData custom = builder.setAcceptsOnlineAuth(false).setAcceptsSelfSignedAuth(false)
                .setNonce("consumer-shared-nonce").build();
        assertFalse(custom.acceptsOnlineAuth());
        assertFalse(custom.acceptsSelfSignedAuth());
        assertEquals("consumer-shared-nonce", custom.nonce());
        assertEquals(legacy.nonce(), new PongData.Builder().build().nonce());
    }

    // The user supplied the decoded stable BDS capture on September 6, 2026.
    private static byte[] stableCapture() throws IOException {
        try (var stream = NetherNetServerDataCodecTest.class.getResourceAsStream("/discovery/stable-1.26.45.1-v6.hex")) {
            assertNotNull(stream);
            return ByteBufUtil.decodeHexDump(new String(stream.readAllBytes(), StandardCharsets.US_ASCII).strip());
        }
    }
}
