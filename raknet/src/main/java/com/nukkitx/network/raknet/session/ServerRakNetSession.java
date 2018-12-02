package com.nukkitx.network.raknet.session;

import com.nukkitx.network.raknet.RakNet;
import io.netty.channel.Channel;

import java.net.InetSocketAddress;

public class ServerRakNetSession extends RakNetSession {
    public ServerRakNetSession(InetSocketAddress remoteAddress, InetSocketAddress localAddress, int mtu, Channel channel, RakNet rakNet, long remoteId) {
        super(remoteAddress, localAddress, mtu, channel, rakNet, remoteId);
    }
}
