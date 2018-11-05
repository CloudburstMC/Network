package com.nukkitx.network.raknet.session;

import com.nukkitx.network.raknet.RakNetPong;
import com.nukkitx.network.raknet.packet.UnconnectedPongPacket;
import io.netty.channel.Channel;
import lombok.Value;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

@Value
public class RakNetPingSession {
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final Channel channel;
    private final CompletableFuture<RakNetPong> pongFuture;
    private final long ping;

    public void onPong(UnconnectedPongPacket packet) {
        if (!pongFuture.isDone()) {
            RakNetPong pong = new RakNetPong(packet.getAdvertisement(), packet.getTimestamp() - ping, packet.getServerId());
            pongFuture.complete(pong);
        }
    }
}
