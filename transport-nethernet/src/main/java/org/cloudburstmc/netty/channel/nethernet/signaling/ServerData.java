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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * The server data a LAN discovery reply carries, hex encoded behind its length. Clients read one of several layouts
 * by its version, and a request does not say which, so a server answers once in each of {@link #VERSIONS}.
 */
public final class ServerData {
    /**
     * Newest last, so a client that keeps the last reply it can read ends on the newest it knows. BDS 1.26.40 to
     * 1.26.45 send 6, and 1.26.50 to 1.26.60 send 7. A 1.26.51 client lists a server answering in an older version,
     * but never sends it an offer.
     */
    static final int[] VERSIONS = {6, 7};

    private ServerData() {
    }

    /**
     * @return The server data as a reply carries it
     * @throws IllegalArgumentException If the version is not one of {@link #VERSIONS}
     */
    static byte[] encode(PongData data, int version) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(version);
            switch (version) {
                case 6 -> {
                    writeString(buf, data.serverName());
                    writeString(buf, data.levelName());
                    writeVarInt(buf, data.gameType());
                    buf.writeIntLE(data.playerCount());
                    buf.writeIntLE(data.maxPlayerCount());
                    buf.writeBoolean(data.isEditorWorld());
                    buf.writeBoolean(data.isHardcore());
                    buf.writeBoolean(data.onlineAuth());
                    buf.writeBoolean(data.selfSignedAuth());
                    writeString(buf, data.nonce());
                    writeVarInt(buf, data.transportLayer());
                    writeVarInt(buf, data.connectionType());
                }
                case 7 -> {
                    writeString(buf, data.serverName());
                    writeVarInt(buf, data.protocol());
                    writeString(buf, data.version());
                    writeString(buf, data.levelName());
                    writeVarInt(buf, data.playerCount());
                    writeVarInt(buf, data.maxPlayerCount());
                    writeVarInt(buf, data.gameType());
                    buf.writeBoolean(data.isEditorWorld());
                    buf.writeBoolean(data.isHardcore());
                    buf.writeBoolean(data.onlineAuth());
                    buf.writeBoolean(data.selfSignedAuth());
                    writeString(buf, data.nonce());
                    writeVarInt(buf, data.connectionType());
                }
                default -> throw new IllegalArgumentException("No server data version " + version);
            }
            byte[] hex = ByteBufUtil.hexDump(buf).getBytes(StandardCharsets.US_ASCII);

            byte[] encoded = new byte[Integer.BYTES + hex.length];
            Unpooled.wrappedBuffer(encoded).setIntLE(0, hex.length).setBytes(Integer.BYTES, hex);
            return encoded;
        } finally {
            buf.release();
        }
    }

    /**
     * Reads the server data a reply carries, in any of {@link #VERSIONS}. A field a version leaves out keeps its
     * {@link PongData.Builder} default.
     *
     * @param payload The reply after its header: the length, then the hex
     * @return The status, or null for a version this does not know
     * @throws IndexOutOfBoundsException If the data is cut short
     * @throws IllegalArgumentException  If the hex is malformed
     */
    public static @Nullable PongData decode(ByteBuf payload) {
        int length = payload.readIntLE();
        ByteBuf data = Unpooled.wrappedBuffer(
                ByteBufUtil.decodeHexDump(payload.readCharSequence(length, StandardCharsets.US_ASCII)));
        int version = data.readUnsignedByte();
        PongData.Builder status = new PongData.Builder().setDataVersion(version);
        switch (version) {
            case 6 -> status.setServerName(readString(data))
                    .setLevelName(readString(data))
                    .setGameType(readVarInt(data))
                    .setPlayerCount(data.readIntLE())
                    .setMaxPlayerCount(data.readIntLE())
                    .setIsEditorWorld(data.readBoolean())
                    .setIsHardcore(data.readBoolean())
                    .setOnlineAuth(data.readBoolean())
                    .setSelfSignedAuth(data.readBoolean())
                    .setNonce(readString(data))
                    .setTransportLayer(readVarInt(data))
                    .setConnectionType(readVarInt(data));
            case 7 -> status.setServerName(readString(data))
                    .setProtocol(readVarInt(data))
                    .setVersion(readString(data))
                    .setLevelName(readString(data))
                    .setPlayerCount(readVarInt(data))
                    .setMaxPlayerCount(readVarInt(data))
                    .setGameType(readVarInt(data))
                    .setIsEditorWorld(data.readBoolean())
                    .setIsHardcore(data.readBoolean())
                    .setOnlineAuth(data.readBoolean())
                    .setSelfSignedAuth(data.readBoolean())
                    .setNonce(readString(data))
                    .setConnectionType(readVarInt(data));
            default -> {
                return null;
            }
        }
        return status.build();
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        return buf.readCharSequence(readUnsignedVarInt(buf), StandardCharsets.UTF_8).toString();
    }

    /** Zigzag encoded, as the server data writes every number but the fixed width player counts of 6. */
    private static void writeVarInt(ByteBuf buf, int value) {
        writeUnsignedVarInt(buf, (value << 1) ^ (value >> 31));
    }

    private static int readVarInt(ByteBuf buf) {
        int zigzag = readUnsignedVarInt(buf);
        return (zigzag >>> 1) ^ -(zigzag & 1);
    }

    private static void writeUnsignedVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int readUnsignedVarInt(ByteBuf buf) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << shift;
            if (b >= 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("A varint runs past 5 bytes");
    }
}
