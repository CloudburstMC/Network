package org.cloudburstmc.netty.signaling.diagnostic;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticChannelsTest {
    @Test
    void callbackOwnsOneFrameAndDoesNotReopenAfterConsumption() {
        DiagnosticChannels channels = new DiagnosticChannels();
        ByteBuffer borrowed = ByteBuffer.allocateDirect(DiagnosticExchange.FRAME_BYTES);
        byte[] original = new byte[DiagnosticExchange.FRAME_BYTES];
        Arrays.fill(original, (byte) 42);
        borrowed.put(original).flip();
        channels.receive(true, borrowed);
        borrowed.clear().put(new byte[DiagnosticExchange.FRAME_BYTES]);
        assertArrayEquals(original, channels.poll());
        assertNull(channels.poll());
        assertEquals(1, channels.receivedFrames());
        assertEquals(DiagnosticExchange.FRAME_BYTES, channels.receivedBytes());
        channels.receive(true, ByteBuffer.wrap(original));
        assertTrue(channels.protocolFailed());
        assertNull(channels.poll());
    }

    @Test
    void invalidSizeChannelAndTextCannotQueueApplicationData() {
        for (int size : new int[] {0, 55, 57, 256}) {
            DiagnosticChannels channels = new DiagnosticChannels();
            channels.receive(true, ByteBuffer.allocate(size));
            assertTrue(channels.protocolFailed());
            assertNull(channels.poll());
            assertEquals(0, channels.receivedBytes());
        }
        DiagnosticChannels unreliable = new DiagnosticChannels();
        unreliable.receive(false, ByteBuffer.allocate(DiagnosticExchange.FRAME_BYTES));
        assertTrue(unreliable.protocolFailed());
        assertNull(unreliable.poll());
        DiagnosticChannels text = new DiagnosticChannels();
        text.onText(null, "ping");
        text.receive(true, ByteBuffer.allocate(DiagnosticExchange.FRAME_BYTES));
        assertTrue(text.protocolFailed());
        assertNull(text.poll());
    }

    @Test
    void aBurstCannotGrowTheSingleFrameMailbox() {
        DiagnosticChannels channels = new DiagnosticChannels();
        channels.receive(true, ByteBuffer.allocate(DiagnosticExchange.FRAME_BYTES));
        for (int index = 0; index < 100; index++) {
            channels.receive(true, ByteBuffer.allocate(DiagnosticExchange.FRAME_BYTES));
        }
        assertTrue(channels.protocolFailed());
        assertEquals(DiagnosticExchange.FRAME_BYTES, channels.poll().length);
        assertNull(channels.poll());
        assertEquals(1, channels.receivedFrames());
    }
}
