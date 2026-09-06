package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscovery;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simple scanner example for discovering NetherNet servers on the local network.
 */
public class NetherNetScanner {
    public static void main(String[] args) throws Exception {
        long myNetworkId = ThreadLocalRandom.current().nextLong();
        NetherNetDiscovery discovery = new NetherNetDiscovery(myNetworkId);

        discovery.bind(new InetSocketAddress("::", 0));

        System.out.println("Scanning for NetherNet servers on port 7551...");

        InetSocketAddress broadcastTarget = new InetSocketAddress("255.255.255.255", NetherNetConstants.DISCOVERY_PORT);

        discovery.sendDiscoveryRequest(broadcastTarget, (senderId, payload) -> {
            try {
                ServerInfo info = readResponse(payload);
                PongData data = info.data();
                System.out.println("--------------------------------");
                System.out.println("Found Server: " + senderId);
                System.out.println("MOTD: " + data.serverName());
                System.out.println("Level: " + data.levelName());
                System.out.println("Players: " + data.playerCount() + "/" + data.maxPlayerCount());
                System.out.println("Game Mode: " + data.gameType());
                System.out.println("Editor World: " + data.isEditorWorld());
                System.out.println("Hardcore: " + data.isHardcore());
                System.out.println("Version: " + info.version());
                System.out.println("--------------------------------");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                payload.release();
            }
        });

        Thread.sleep(10000);
        discovery.close();
    }

    static ServerInfo readResponse(ByteBuf payload) {
        if (!payload.isReadable(4)) {
            throw new IllegalArgumentException("Missing discovery response length");
        }
        int length = payload.readIntLE();
        if (length < 0 || length != payload.readableBytes()) {
            throw new IllegalArgumentException("Invalid discovery response length");
        }
        String hex = payload.readCharSequence(length, StandardCharsets.US_ASCII).toString();
        ByteBuf data = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex));
        try {
            int version = data.readUnsignedByte();
            PongData pong = new PongData(readString(data), readString(data), readSignedVarInt(data),
                    data.readIntLE(), data.readIntLE(), data.readBoolean(), data.readBoolean(),
                    readSignedVarInt(data), readSignedVarInt(data));
            return new ServerInfo(version, pong);
        } finally {
            data.release();
        }
    }

    private static String readString(ByteBuf buf) {
        int len = readUnsignedVarInt(buf);
        if (len < 0 || buf.readableBytes() < len) {
            throw new IllegalArgumentException("Invalid discovery string length");
        }
        return buf.readCharSequence(len, StandardCharsets.UTF_8).toString();
    }

    private static int readSignedVarInt(ByteBuf buf) {
        int encoded = readUnsignedVarInt(buf);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static int readUnsignedVarInt(ByteBuf buf) {
        int value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            if (!buf.isReadable()) {
                throw new IllegalArgumentException("Truncated discovery varint");
            }
            int next = buf.readUnsignedByte();
            if (shift == 28 && (next & 0xf0) != 0) {
                throw new IllegalArgumentException("Discovery varint exceeds 32 bits");
            }
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("Discovery varint exceeds 32 bits");
    }

    record ServerInfo(int version, PongData data) { }
}
