/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetMessageAssemblerTest {
    private final TrackingAllocator allocator = new TrackingAllocator();
    private final CountingMetrics metrics = new CountingMetrics();

    private static ByteBuffer frame(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        for (int value : bytes) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }

    private void assertCounted(int fragments, int failures) {
        assertEquals(fragments, metrics.fragments, "fragments");
        assertEquals(failures, metrics.failures, "decode failures");
    }

    private static final class CountingMetrics implements NetherChannelMetrics {
        int fragments;
        int failures;

        @Override
        public void fragmentsIn(int count) {
            fragments += count;
        }

        @Override
        public void decodeFail(int count) {
            failures += count;
        }
    }

    @Test
    void completedMessagesSurviveNativeBufferReuseAndAssemblerClose() {
        try (var assembler = new NetherNetMessageAssembler(true)) {
            ByteBuffer nativeBuffer = frame(0, 1, 2, 3);
            ByteBuf first = assembler.decode(nativeBuffer, allocator, metrics);
            nativeBuffer.clear().put(new byte[]{0, 4, 5, 6}).flip();
            ByteBuf second = assembler.decode(nativeBuffer, allocator, metrics);
            nativeBuffer.clear().putInt(0);
            assembler.close();
            try {
                assertArrayEquals(new byte[]{1, 2, 3}, ByteBufUtil.getBytes(first));
                assertArrayEquals(new byte[]{4, 5, 6}, ByteBufUtil.getBytes(second));
                assertEquals(2, allocator.buffers.size());
                assertSame(allocator.buffers.get(0), first);
                assertSame(allocator.buffers.get(1), second);
            } finally {
                first.release();
                second.release();
            }
        }
        allocator.assertReleased();
        assertCounted(0, 0);
    }

    @Test
    void allCountdownFragmentsRetainTheirOriginalNettyAllocation() {
        try (var assembler = new NetherNetMessageAssembler(true)) {
            ByteBuffer nativeBuffer = frame(255, 0);
            ByteBuf message = null;
            byte[] expected = new byte[256];
            for (int i = 0; i < expected.length; i++) {
                nativeBuffer.clear().put((byte) (255 - i)).put((byte) i).flip();
                message = assembler.decode(nativeBuffer, allocator, metrics);
                expected[i] = (byte) i;
                if (i < 255) {
                    assertNull(message);
                }
            }
            nativeBuffer.clear().putShort((short) 0);
            assembler.close();
            try {
                CompositeByteBuf composite = assertInstanceOf(CompositeByteBuf.class, message);
                assertEquals(256, composite.numComponents(), "fragments must not be consolidated");
                assertEquals(256, allocator.buffers.size(), "one payload allocation per callback");
                assertArrayEquals(expected, ByteBufUtil.getBytes(message));
                for (int i = 0; i < 256; i++) {
                    ByteBuf component = allocator.buffers.get(i);
                    assertEquals(1, component.refCnt());
                    assertEquals(component.memoryAddress(), composite.internalComponent(i).memoryAddress());
                }
            } finally {
                if (message != null) {
                    message.release();
                }
            }
        }
        allocator.assertReleased();
        assertCounted(255, 0);
    }

    @Test
    void aBadCountdownEndsTheReliableStreamAndReleasesWhatWasAssembled() {
        var assembler = new NetherNetMessageAssembler(true);
        assertNull(assembler.decode(frame(2, 1), allocator, metrics));
        assertBroken(TransportDisconnectReason.BAD_PACKET, assembler, frame(0, 2));
        allocator.assertReleased();
        assertNull(assembler.decode(frame(0, 3), allocator, metrics), "nothing is delivered after");
        assertEquals(1, allocator.buffers.size());
        assertCounted(1, 1);
    }

    @Test
    void framesOfAByteOrLessEndTheReliableStream() {
        for (ByteBuffer frame : new ByteBuffer[]{frame(), frame(0), frame(2)}) {
            var assembler = new NetherNetMessageAssembler(true);
            assertNull(assembler.decode(frame(1, 7), allocator, metrics));
            assertBroken(TransportDisconnectReason.BAD_PACKET, assembler, frame);
        }
        allocator.assertReleased();
        assertCounted(3, 3);
    }

    @Test
    void aReliableFrameMayFillTheAdvertisedSizeButNoMore() {
        ByteBuffer frame = ByteBuffer.allocateDirect(NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE + 1);
        var assembler = new NetherNetMessageAssembler(true);
        ByteBuf message = assembler.decode(frame.limit(NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE), allocator,
                metrics);
        try {
            assertEquals(NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE - 1, message.readableBytes());
        } finally {
            message.release();
        }
        assertBroken(TransportDisconnectReason.BAD_PACKET, assembler, frame.clear());
        allocator.assertReleased();
        assertCounted(0, 1);
    }

    @Test
    void aLargeFragmentedMessageReassemblesByteForByte() {
        int fragments = 4;
        int payload = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1;
        byte[] expected = new byte[fragments * payload];
        ByteBuffer frame = ByteBuffer.allocateDirect(1 + payload);
        ByteBuf message = null;
        try (var assembler = new NetherNetMessageAssembler(true)) {
            for (int i = 0; i < fragments; i++) {
                frame.clear().put((byte) (fragments - 1 - i));
                for (int b = 0; b < payload; b++) {
                    byte value = (byte) ((i * 31 + b) % 251);
                    expected[i * payload + b] = value;
                    frame.put(value);
                }
                message = assembler.decode(frame.flip(), allocator, metrics);
                if (i < fragments - 1) {
                    assertNull(message, "completed early");
                }
            }
            assertArrayEquals(expected, ByteBufUtil.getBytes(message));
        } finally {
            if (message != null) {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(fragments - 1, 0);
    }

    @Test
    void theProtocolVectorsDecode() throws Exception {
        // The NXS fixtures, which external-signaling checks the hashes of
        JsonObject vectors = JsonParser.parseString(Files.readString(
                Path.of("../docs/external-signaling/fixtures/cloudburst-protocol-vectors.v1.json"))).getAsJsonObject();
        for (JsonElement entry : vectors.getAsJsonArray("nethernetFrames")) {
            JsonObject vector = entry.getAsJsonObject();
            byte[] bytes = HexFormat.of().parseHex(vector.get("frameHex").getAsString());
            try (var assembler = new NetherNetMessageAssembler(true)) {
                ByteBuf decoded = assembler.decode(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip(),
                        allocator, metrics);
                if (vector.getAsJsonObject("decoded").get("complete").getAsBoolean()) {
                    try {
                        assertArrayEquals(HexFormat.of().parseHex(vector.get("payloadHex").getAsString()),
                                ByteBufUtil.getBytes(decoded));
                    } finally {
                        decoded.release();
                    }
                } else {
                    assertNull(decoded);
                }
            }
        }
        allocator.assertReleased();
    }

    @Test
    void closingPartialMessageReleasesItAndIgnoresLateCallbacks() {
        var assembler = new NetherNetMessageAssembler(true);
        assertNull(assembler.decode(frame(1, 42), allocator, metrics));
        assembler.close();
        assembler.close();
        allocator.assertReleased();
        assertNull(assembler.decode(frame(0, 43), allocator, metrics));
        assertEquals(1, allocator.buffers.size());
        assertCounted(1, 0);
    }

    @Test
    void emptyFramesAndHeaderOnlyFragmentsPreserveCountdownBehaviourOnTheUnreliableChannel() {
        try (var assembler = new NetherNetMessageAssembler(false)) {
            assertNull(assembler.decode(frame(), allocator, metrics));
            assertNull(assembler.decode(frame(0), allocator, metrics));
            assertNull(assembler.decode(frame(2), allocator, metrics));
            assertNull(assembler.decode(frame(1, 7), allocator, metrics));
            assertNull(assembler.decode(frame(), allocator, metrics));
            ByteBuf message = assembler.decode(frame(0), allocator, metrics);
            try {
                assertArrayEquals(new byte[]{7}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(2, 3);
    }

    @Test
    void allocationFailureReleasesEarlierFragments() {
        try (var assembler = new NetherNetMessageAssembler(true)) {
            assertNull(assembler.decode(frame(1, 7), allocator, metrics));
            allocator.fail = true;
            assertThrows(IllegalStateException.class, () -> assembler.decode(frame(0, 8), allocator, metrics));
            allocator.assertReleased();
            allocator.fail = false;
            ByteBuf message = assembler.decode(frame(0, 9), allocator, metrics);
            try {
                assertEquals(9, message.readUnsignedByte());
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(1, 0);
    }

    @Test
    void aMessageThatLosesItsTailDoesNotEatTheNextOne() {
        try (var assembler = new NetherNetMessageAssembler(false)) {
            assertNull(assembler.decode(frame(2, 1), allocator, metrics));
            assertNull(assembler.decode(frame(1, 2), allocator, metrics));
            // The last fragment never arrives and the next message opens instead
            assertNull(assembler.decode(frame(2, 7), allocator, metrics));
            assertNull(assembler.decode(frame(1, 8), allocator, metrics));
            ByteBuf message = assembler.decode(frame(0, 9), allocator, metrics);

            assertNotNull(message, "the following message should still complete");
            try {
                assertArrayEquals(new byte[]{7, 8, 9}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(4, 1);
    }

    @Test
    void aMessageWithAGapIsDroppedWithoutTakingTheNextWithIt() {
        try (var assembler = new NetherNetMessageAssembler(false)) {
            assertNull(assembler.decode(frame(2, 1), allocator, metrics));
            // The middle fragment never arrives, so the message cannot be completed
            assertNull(assembler.decode(frame(0, 3), allocator, metrics));
            ByteBuf message = assembler.decode(frame(0, 9), allocator, metrics);

            assertNotNull(message, "the assembler should be clean again");
            try {
                assertArrayEquals(new byte[]{9}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(1, 1);
    }

    @Test
    void aGappedMessageIsFollowedOutToItsLastFragment() {
        try (var assembler = new NetherNetMessageAssembler(false)) {
            assertNull(assembler.decode(frame(4, 1), allocator, metrics));
            // Two fragments go missing, so the rest of this message is followed out and dropped
            assertNull(assembler.decode(frame(1, 4), allocator, metrics));
            assertNull(assembler.decode(frame(0, 5), allocator, metrics));
            ByteBuf message = assembler.decode(frame(0, 9), allocator, metrics);

            assertNotNull(message, "the assembler should be clean again");
            try {
                assertArrayEquals(new byte[]{9}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        assertCounted(1, 1);
    }

    @Test
    void aReliableMessageOverTheBudgetEndsTheStream() {
        int payload = NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE - 1;
        ByteBuffer segment = ByteBuffer.allocateDirect(1 + payload);
        var assembler = new NetherNetMessageAssembler(true);
        int remaining = 255;
        for (int i = 0; i < NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE / payload; i++, remaining--) {
            segment.clear().put(0, (byte) remaining);
            assertNull(assembler.decode(segment, allocator, metrics));
        }
        segment.clear().put(0, (byte) remaining);
        assertBroken(TransportDisconnectReason.SPLIT_QUEUE_TOO_LONG, assembler, segment);
        allocator.assertReleased();
        assertCounted(NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE / payload, 1);
    }

    @Test
    void anUnreliableMessageOverTheBudgetIsDroppedWithoutTakingTheNextWithIt() {
        int payload = NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE - 1;
        ByteBuffer segment = ByteBuffer.allocateDirect(1 + payload);
        try (var assembler = new NetherNetMessageAssembler(false)) {
            // 256 segments of the advertised size come to 64 MiB
            for (int remaining = 255; remaining >= 0; remaining--) {
                segment.clear().put(0, (byte) remaining);
                assertNull(assembler.decode(segment, allocator, metrics));
            }
            ByteBuf message = assembler.decode(frame(0, 9), allocator, metrics);

            assertNotNull(message, "the assembler should be clean again");
            try {
                assertArrayEquals(new byte[]{9}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
        // Fragments are kept until the next would pass the cap
        assertCounted(NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE / payload, 1);
    }

    private void assertBroken(TransportDisconnectReason reason, NetherNetMessageAssembler assembler,
                              ByteBuffer frame) {
        assertEquals(reason, assertThrows(NetherNetFramingException.class,
                () -> assembler.decode(frame, allocator, metrics)).reason());
    }
}
