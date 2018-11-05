package com.nukkitx.network.raknet.session;

import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.packet.ConnectedPingPacket;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;

public class ClientRakNetSession extends RakNetSession {

    public ClientRakNetSession(InetSocketAddress remoteAddress, int mtu, Channel channel, RakNet rakNet) {
        super(remoteAddress, mtu, channel, rakNet);
    }

    @Override
    public void onPingTick() {
        ConnectedPingPacket connectedPing = new ConnectedPingPacket();
        connectedPing.setPingTime(System.currentTimeMillis());
        sendPacket(connectedPing);
    }
}
