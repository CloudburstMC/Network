package com.nukkitx.network.raknet;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@UtilityClass
public class RakNetUtil {
    private static final byte[] RAKNET_UNCONNECTED_MAGIC = new byte[]{
            0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120
    };
    public static final byte RAKNET_PROTOCOL_VERSION = 9; // Mojangs version.
    public static final short MINIMUM_MTU_SIZE = 400;
    public static final short MAXIMUM_MTU_SIZE = 1492;
    public static final int MAX_ENCAPSULATED_HEADER_SIZE = 9;
    public static final int MAX_MESSAGE_HEADER_SIZE = 23;

    public static String readString(ByteBuf buffer) {
        byte[] stringBytes = new byte[buffer.readShort()];
        buffer.readBytes(stringBytes);
        return new String(stringBytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buffer, String string) {
        byte[] stringBytes = string.getBytes(StandardCharsets.UTF_8);
        buffer.writeShort(stringBytes.length);
        buffer.writeBytes(stringBytes);
    }

    public static void verifyUnconnectedMagic(ByteBuf buffer) {
        byte[] readMagic = new byte[RAKNET_UNCONNECTED_MAGIC.length];
        buffer.readBytes(readMagic);

        if (!Arrays.equals(readMagic, RAKNET_UNCONNECTED_MAGIC)) {
            throw new RuntimeException("Invalid packet magic.");
        }
    }

    public static void writeUnconnectedMagic(ByteBuf buffer) {
        buffer.writeBytes(RAKNET_UNCONNECTED_MAGIC);
    }
}
