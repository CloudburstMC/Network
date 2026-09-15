package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscovery;
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
                if (payload.readableBytes() < 4) {
                    return;
                }

                int length = payload.readIntLE();
                if (payload.readableBytes() < length) {
                    return;
                }

                String hexString = payload.readCharSequence(length, StandardCharsets.UTF_8).toString();

                byte[] binaryData = ByteBufUtil.decodeHexDump(hexString);
                ByteBuf data = Unpooled.wrappedBuffer(binaryData);

                try {
                    int version = data.readUnsignedByte();
                    String serverName = readString(data);
                    String levelName = readString(data);
                    int gameType = data.readUnsignedByte() >> 1;
                    int playerCount = data.readIntLE();
                    int maxPlayers = data.readIntLE();
                    boolean isEditor = data.readBoolean();
                    boolean isHardcore = data.readBoolean();

                    System.out.println("--------------------------------");
                    System.out.println("Found Server: " + senderId);
                    System.out.println("MOTD: " + serverName);
                    System.out.println("Level: " + levelName);
                    System.out.println("Players: " + playerCount + "/" + maxPlayers);
                    System.out.println("Game Mode: " + gameType);
                    System.out.println("Editor World: " + isEditor);
                    System.out.println("Hardcore: " + isHardcore);
                    System.out.println("Version: " + version);
                    System.out.println("--------------------------------");

                } finally {
                    data.release();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                payload.release();
            }
        });

        Thread.sleep(10000);
        discovery.close();
    }

    private static String readString(ByteBuf buf) {
        if (!buf.isReadable()) {
            return "";
        }
        int len = buf.readUnsignedByte();
        if (buf.readableBytes() < len) {
            return "";
        }
        return buf.readCharSequence(len, StandardCharsets.UTF_8).toString();
    }
}
