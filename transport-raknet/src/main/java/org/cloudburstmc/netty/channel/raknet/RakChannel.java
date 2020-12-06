package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import org.cloudburstmc.netty.channel.ProxyChannel;

public class RakChannel extends ProxyChannel<DatagramChannel> implements Channel {

    public RakChannel(DatagramChannel channel) {
        super(channel);
    }
}
