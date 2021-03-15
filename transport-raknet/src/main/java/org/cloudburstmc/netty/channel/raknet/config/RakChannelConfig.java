package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.channel.ChannelConfig;

public interface RakChannelConfig extends ChannelConfig {

    long getGuid();

    RakChannelConfig setGuid(long guid);

    int getMtu();

    RakChannelConfig setMtu(int mtu);

    int getProtocolVersion();

    RakChannelConfig setProtocolVersion(int protocolVersion);

    int getOrderingChannels();

    RakChannelConfig setOrderingChannels(int orderingChannels);
}
