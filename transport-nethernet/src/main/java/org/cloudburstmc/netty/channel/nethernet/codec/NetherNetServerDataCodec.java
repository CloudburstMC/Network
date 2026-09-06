package org.cloudburstmc.netty.channel.nethernet.codec;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;

/** Binary ServerData v6, as emitted by stable Bedrock 1.26.45.1 on LAN discovery. */
public final class NetherNetServerDataCodec {
    public static final int VERSION = 6;

    private NetherNetServerDataCodec() {
    }

    /**
     * Writes the version byte and all v6 fields, excluding outer discovery framing.
     *
     * @param buffer the caller-owned output buffer
     * @param data the complete advertisement
     */
    public static void encode(ByteBuf buffer, PongData data) {
        buffer.writeByte(VERSION);
        writeString(buffer, data.serverName());
        writeString(buffer, data.levelName());
        writeSignedVarInt(buffer, data.gameType());
        buffer.writeIntLE(data.playerCount());
        buffer.writeIntLE(data.maxPlayerCount());
        buffer.writeBoolean(data.isEditorWorld());
        buffer.writeBoolean(data.isHardcore());
        buffer.writeBoolean(data.acceptsOnlineAuth());
        buffer.writeBoolean(data.acceptsSelfSignedAuth());
        writeString(buffer, data.nonce());
        writeSignedVarInt(buffer, data.transportLayer());
        writeSignedVarInt(buffer, data.connectionType());
    }

    /**
     * Reads one complete v6 record. Unsupported versions and trailing bytes are rejected.
     *
     * @param buffer the caller-owned buffer containing only the binary ServerData record
     * @return the decoded advertisement
     */
    public static PongData decode(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            throw new IllegalArgumentException("Missing LAN advertisement version");
        }
        int version = buffer.readUnsignedByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported LAN advertisement version: " + version);
        }
        String name = readString(buffer);
        String level = readString(buffer);
        int gameType = readSignedVarInt(buffer);
        int players = buffer.readIntLE();
        int maxPlayers = buffer.readIntLE();
        boolean editor = readBoolean(buffer);
        boolean hardcore = readBoolean(buffer);
        boolean onlineAuth = readBoolean(buffer);
        boolean selfSignedAuth = readBoolean(buffer);
        String nonce = readString(buffer);
        int transport = readSignedVarInt(buffer);
        int connection = readSignedVarInt(buffer);
        if (buffer.isReadable()) {
            throw new IllegalArgumentException("Trailing bytes in LAN advertisement");
        }
        return new PongData(name, level, gameType, players, maxPlayers, editor, hardcore,
                transport, connection, onlineAuth, selfSignedAuth, nonce);
    }

    private static void writeString(ByteBuf buffer, String value) {
        int length = ByteBufUtil.utf8Bytes(value);
        writeUnsignedVarInt(buffer, length);
        ByteBufUtil.reserveAndWriteUtf8(buffer, value, length);
    }

    private static void writeSignedVarInt(ByteBuf buffer, int value) {
        writeUnsignedVarInt(buffer, (value << 1) ^ (value >> 31));
    }

    private static void writeUnsignedVarInt(ByteBuf buffer, int value) {
        while ((value & ~0x7f) != 0) {
            buffer.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    private static String readString(ByteBuf buffer) {
        int length = readUnsignedVarInt(buffer);
        if (length < 0 || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid LAN advertisement string length");
        }
        return buffer.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    private static boolean readBoolean(ByteBuf buffer) {
        int value = buffer.readUnsignedByte();
        if (value > 1) {
            throw new IllegalArgumentException("Invalid LAN advertisement boolean: " + value);
        }
        return value != 0;
    }

    private static int readSignedVarInt(ByteBuf buffer) {
        int encoded = readUnsignedVarInt(buffer);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static int readUnsignedVarInt(ByteBuf buffer) {
        int value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            if (!buffer.isReadable()) {
                throw new IllegalArgumentException("Truncated LAN advertisement varint");
            }
            int next = buffer.readUnsignedByte();
            if (shift == 28 && (next & 0xf0) != 0) {
                throw new IllegalArgumentException("LAN advertisement varint exceeds 32 bits");
            }
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("LAN advertisement varint exceeds 32 bits");
    }
}
