package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import org.cloudburstmc.netty.channel.ProxyChannel;

public class RakServerChannel extends ProxyChannel<DatagramChannel> implements ServerChannel {

    public RakServerChannel(DatagramChannel channel) {
        super(channel);
    }
}
