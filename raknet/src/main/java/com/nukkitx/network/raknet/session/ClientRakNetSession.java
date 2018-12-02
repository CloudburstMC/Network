package com.nukkitx.network.raknet.session;

import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.packet.ConnectedPingPacket;
import io.netty.channel.Channel;
import lombok.Getter;

import java.net.InetSocketAddress;

@Getter
public class ClientRakNetSession extends RakNetSession {

    public ClientRakNetSession(InetSocketAddress remoteAddress, InetSocketAddress localAddress, int mtu, Channel channel, RakNetClient rakNet, long remoteId) {
        super(remoteAddress, localAddress, mtu, channel, rakNet, remoteId);
    }

    @Override
    public void onTick() {
        super.onTick();
        if (!isClosed() && isStale()) {
            ConnectedPingPacket connectedPing = new ConnectedPingPacket();
            connectedPing.setPingTime(getRakNet().getTimestamp());
            sendPacket(connectedPing);
        }
    }
}
