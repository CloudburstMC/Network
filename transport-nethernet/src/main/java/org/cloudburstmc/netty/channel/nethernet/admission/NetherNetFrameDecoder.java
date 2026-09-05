package org.cloudburstmc.netty.channel.nethernet.admission;

import java.util.Arrays;

/** Bounded countdown framing. Unordered traffic must fit one SCTP message. */
public final class NetherNetFrameDecoder {
    public static final int FRAME_LIMIT = 10000, MESSAGE_LIMIT = 262144;
    private byte[] assembly;
    private int size, expected = -1;
    public byte[] decode(byte[] frame, boolean reliable) {
        if (frame.length < 2 || frame.length > FRAME_LIMIT) throw new IllegalArgumentException("Invalid NetherNet frame length");
        int remaining = Byte.toUnsignedInt(frame[0]), payload = frame.length - 1;
        // Countdown alone cannot disambiguate interleaved/reordered fragmented messages.
        if (!reliable) {
            if (remaining != 0) throw new IllegalArgumentException("Fragmented unordered NetherNet message is unsupported");
            return Arrays.copyOfRange(frame, 1, frame.length);
        }
        if (remaining >= (MESSAGE_LIMIT + FRAME_LIMIT - 2) / (FRAME_LIMIT - 1) ||
            (expected != -1 && expected != remaining) || size + payload > MESSAGE_LIMIT) {
            clear(); throw new IllegalArgumentException("Invalid NetherNet fragment sequence");
        }
        if (expected == -1 && remaining == 0) return Arrays.copyOfRange(frame, 1, frame.length);
        if (assembly == null) assembly = new byte[MESSAGE_LIMIT];
        System.arraycopy(frame, 1, assembly, size, payload); size += payload; expected = remaining - 1;
        if (remaining != 0) return null;
        byte[] message = Arrays.copyOf(assembly, size); clear(); return message;
    }
    public void clear() { assembly = null; size = 0; expected = -1; }
    public int retainedBytes() { return assembly == null ? 0 : assembly.length; }
}
