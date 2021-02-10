package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import org.cloudburstmc.netty.channel.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakSessionConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.client.RakClientRouteHandler;

public class RakClientChannel extends ProxyChannel<DatagramChannel> implements Channel {

    private final RakChannelConfig config;

    public RakClientChannel(DatagramChannel channel) {
        super(channel);
        this.config = new DefaultRakSessionConfig(this);
        this.pipeline().addLast(RakClientRouteHandler.NAME, new RakClientRouteHandler(this));
    }

    @Override
    public RakChannelConfig config() {
        return this.config;
    }

}
