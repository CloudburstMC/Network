package org.cloudburstmc.netty.signalling.admission;

import io.netty.buffer.ByteBuf;

/**
 * Bounded countdown framing. Unordered traffic must fit one SCTP message.
 */
public final class NetherNetFrameDecoder {
    public static final int FRAME_LIMIT = 10000, MESSAGE_LIMIT = 262144;
    private ByteBuf assembly;
    private int expected = -1;

    /**
     * Consumes the frame and returns a completed message, or {@code null} while one is still assembling. The
     * returned buffer belongs to the caller.
     */
    public ByteBuf decode(ByteBuf frame, boolean reliable) {
        try {
            return assemble(frame, reliable);
        } finally {
            frame.release();
        }
    }

    private ByteBuf assemble(ByteBuf frame, boolean reliable) {
        int length = frame.readableBytes();
        if (length < 2 || length > FRAME_LIMIT) {
            throw new IllegalArgumentException("Invalid NetherNet frame length");
        }
        int start = frame.readerIndex();
        int remaining = frame.getUnsignedByte(start);
        int payload = length - 1;
        // Countdown alone cannot disambiguate interleaved/reordered fragmented messages.
        if (!reliable) {
            if (remaining != 0) {
                throw new IllegalArgumentException("Fragmented unordered NetherNet message is unsupported");
            }
            return frame.retainedSlice(start + 1, payload);
        }
        int size = this.assembly == null ? 0 : this.assembly.readableBytes();
        if (remaining >= (MESSAGE_LIMIT + FRAME_LIMIT - 2) / (FRAME_LIMIT - 1) ||
                (this.expected != -1 && this.expected != remaining) || size + payload > MESSAGE_LIMIT) {
            this.clear();
            throw new IllegalArgumentException("Invalid NetherNet fragment sequence");
        }
        if (this.expected == -1 && remaining == 0) {
            return frame.retainedSlice(start + 1, payload);
        }
        if (this.assembly == null) {
            this.assembly = frame.alloc().buffer(payload, MESSAGE_LIMIT);
        }
        this.assembly.writeBytes(frame, start + 1, payload);
        this.expected = remaining - 1;
        if (remaining != 0) {
            return null;
        }
        ByteBuf message = this.assembly;
        this.assembly = null;
        this.clear();
        return message;
    }

    public void clear() {
        if (this.assembly != null) {
            this.assembly.release();
            this.assembly = null;
        }
        this.expected = -1;
    }

    public int retainedBytes() {
        return this.assembly == null ? 0 : this.assembly.capacity();
    }
}
