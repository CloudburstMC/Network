package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.codec.NetherNetServerDataCodec;
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
                System.out.println("Accepts Online Auth: " + data.acceptsOnlineAuth());
                System.out.println("Accepts Self-Signed Auth: " + data.acceptsSelfSignedAuth());
                System.out.println("Nonce: " + data.nonce());
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
            return new ServerInfo(NetherNetServerDataCodec.VERSION, NetherNetServerDataCodec.decode(data));
        } finally {
            data.release();
        }
    }

    record ServerInfo(int version, PongData data) { }
}
