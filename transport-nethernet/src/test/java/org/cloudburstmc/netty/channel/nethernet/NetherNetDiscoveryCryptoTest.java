package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetDiscoveryCryptoTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 14, 15, 16, 17, 512, 10000, 65533})
    void ciphertextMatchesTheExistingFormatAndRoundTrips(int size) throws Exception {
        byte[] bytes = payload(size);
        ByteBuf source = Unpooled.directBuffer(size + 3).writeZero(3).writeBytes(bytes);
        source.skipBytes(3);
        try {
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(source);
            assertArrayEquals(referencePacket(bytes, size + 2), encrypted);
            assertEquals(0, source.readableBytes());
            assertEquals(1, source.refCnt());
            assertDecoded(bytes, encrypted);
        } finally {
            source.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 64, 128, 129, 130})
    void acceptsLengthsUpToActualSizeWithoutTruncatingPayload(int declaredLength) throws Exception {
        byte[] bytes = payload(128);
        assertDecoded(bytes, referencePacket(bytes, declaredLength));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 32, 33, 47, 49, 63})
    void malformedCiphertextLengthIsRejectedBeforeDecryption(int size) throws Exception {
        ByteBuf source = Unpooled.buffer(size).writeZero(size);
        try {
            assertNull(NetherNetConstants.decryptDiscoveryPacket(source));
            assertEquals(1, source.refCnt());
        } finally {
            source.release();
        }
    }

    @Test
    void rejectsOverstatedLengthAndTampering() throws Exception {
        byte[] bytes = payload(80);
        byte[] mismatch = referencePacket(bytes, 400);
        byte[] offByOne = referencePacket(bytes, bytes.length + 3);
        byte[] tampered = referencePacket(bytes, bytes.length + 2);
        tampered[0] ^= 1;
        byte[] understatedTampered = referencePacket(bytes, 0);
        understatedTampered[0] ^= 1;
        for (byte[] wire : new byte[][]{mismatch, offByOne, tampered, understatedTampered}) {
            ByteBuf source = Unpooled.wrappedBuffer(wire);
            try {
                assertNull(NetherNetConstants.decryptDiscoveryPacket(source));
            } finally {
                source.release();
            }
        }
        assertDecoded(bytes, referencePacket(bytes, bytes.length + 2));
    }

    @Test
    void aPaddingFailureDoesNotPoisonTheNextPacket() throws Exception {
        Cipher raw = Cipher.getInstance("AES/ECB/NoPadding");
        raw.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"));
        ByteBuf invalid = Unpooled.buffer(48).writeZero(32).writeBytes(raw.doFinal(new byte[16]));
        try {
            assertThrows(BadPaddingException.class, () -> NetherNetConstants.decryptDiscoveryPacket(invalid));
        } finally {
            invalid.release();
        }
        byte[] bytes = payload(60);
        assertDecoded(bytes, referencePacket(bytes, bytes.length + 2));
    }

    @Test
    void oversizedPlaintextDoesNotConsumeItsInput() {
        ByteBuf source = Unpooled.buffer(65534).writeZero(65534);
        try {
            assertThrows(IllegalArgumentException.class, () -> NetherNetConstants.encryptDiscoveryPacket(source));
            assertEquals(0, source.readerIndex());
            assertEquals(1, source.refCnt());
        } finally {
            source.release();
        }
    }

    @Test
    @Timeout(15)
    void cryptoStateIsIndependentAcrossThreadsAndPackets() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                int id = worker;
                work.add(pool.submit(() -> {
                    for (int round = 0; round < 250; round++) {
                        byte[] bytes = payload(17 + (round * 11 + id * 37) % 2048);
                        ByteBuf source = Unpooled.wrappedBuffer(bytes);
                        try {
                            assertDecoded(bytes, NetherNetConstants.encryptDiscoveryPacket(source));
                        } finally {
                            source.release();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> future : work) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        new Random(7111L + size).nextBytes(bytes);
        return bytes;
    }

    private static void assertDecoded(byte[] expected, byte[] wire) throws Exception {
        ByteBuf input = Unpooled.wrappedBuffer(wire);
        ByteBuf decoded;
        try {
            decoded = NetherNetConstants.decryptDiscoveryPacket(input);
            assertNotNull(decoded);
            assertFalse(input.isReadable());
        } finally {
            input.release();
        }
        try {
            assertArrayEquals(expected, ByteBufUtil.getBytes(decoded));
        } finally {
            decoded.release();
        }
    }

    private static byte[] key() throws Exception {
        byte[] id = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0xdeadbeefL).array();
        return MessageDigest.getInstance("SHA-256").digest(id);
    }

    private static byte[] referencePacket(byte[] body, int declaredLength) throws Exception {
        byte[] plain = ByteBuffer.allocate(body.length + 2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) declaredLength).put(body).array();
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key(), "HmacSHA256"));
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] packet = Arrays.copyOf(mac.doFinal(plain), 32 + ciphertext.length);
        System.arraycopy(ciphertext, 0, packet, 32, ciphertext.length);
        return packet;
    }
}
