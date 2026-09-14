package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetMessageAssemblerTest {
    private final TrackingAllocator allocator = new TrackingAllocator();

    private static ByteBuffer frame(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        for (int value : bytes) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }

    @Test
    void completedMessagesSurviveNativeBufferReuseAndAssemblerClose() {
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            ByteBuffer nativeBuffer = frame(0, 1, 2, 3);
            ByteBuf first = assembler.decode(nativeBuffer, allocator);
            nativeBuffer.clear().put(new byte[]{0, 4, 5, 6}).flip();
            ByteBuf second = assembler.decode(nativeBuffer, allocator);
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
    }

    @Test
    void allCountdownFragmentsRetainTheirOriginalNettyAllocation() {
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            ByteBuffer nativeBuffer = frame(255, 0);
            ByteBuf message = null;
            byte[] expected = new byte[256];
            for (int i = 0; i < expected.length; i++) {
                nativeBuffer.clear().put((byte) (255 - i)).put((byte) i).flip();
                message = assembler.decode(nativeBuffer, allocator);
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
    }

    @Test
    void badCountdownReleasesPartialMessageAndAllowsTheNextMessage() {
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            assertNull(assembler.decode(frame(2, 1), allocator));
            assertNull(assembler.decode(frame(0, 2), allocator));
            allocator.assertReleased();
            ByteBuf next = assembler.decode(frame(0, 3), allocator);
            try {
                assertArrayEquals(new byte[]{3}, ByteBufUtil.getBytes(next));
            } finally {
                next.release();
            }
        }
        allocator.assertReleased();
    }

    @Test
    void closingPartialMessageReleasesItAndIgnoresLateCallbacks() {
        var assembler = new NetherNetMessageAssembler("reliable");
        assertNull(assembler.decode(frame(1, 42), allocator));
        assembler.close();
        assembler.close();
        allocator.assertReleased();
        assertNull(assembler.decode(frame(0, 43), allocator));
        assertEquals(1, allocator.buffers.size());
    }

    @Test
    void emptyFramesAndHeaderOnlyFragmentsPreserveCountdownBehaviour() {
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            assertNull(assembler.decode(frame(), allocator));
            assertNull(assembler.decode(frame(0), allocator));
            assertNull(assembler.decode(frame(2), allocator));
            assertNull(assembler.decode(frame(1, 7), allocator));
            assertNull(assembler.decode(frame(), allocator));
            ByteBuf message = assembler.decode(frame(0), allocator);
            try {
                assertArrayEquals(new byte[]{7}, ByteBufUtil.getBytes(message));
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
    }

    @Test
    void allocationFailureReleasesEarlierFragments() {
        try (var assembler = new NetherNetMessageAssembler("reliable")) {
            assertNull(assembler.decode(frame(1, 7), allocator));
            allocator.fail = true;
            assertThrows(IllegalStateException.class, () -> assembler.decode(frame(0, 8), allocator));
            allocator.assertReleased();
            allocator.fail = false;
            ByteBuf message = assembler.decode(frame(0, 9), allocator);
            try {
                assertEquals(9, message.readUnsignedByte());
            } finally {
                message.release();
            }
        }
        allocator.assertReleased();
    }

    private static final class TrackingAllocator extends AbstractByteBufAllocator {
        final List<ByteBuf> buffers = new ArrayList<>();
        boolean fail;

        TrackingAllocator() {
            super(true);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            throw new AssertionError("Expected direct allocation");
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            if (fail) {
                throw new IllegalStateException("Allocation failed");
            }
            ByteBuf buffer = Unpooled.directBuffer(initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        void assertReleased() {
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt(), "payload allocation leaked");
            }
        }
    }
}
