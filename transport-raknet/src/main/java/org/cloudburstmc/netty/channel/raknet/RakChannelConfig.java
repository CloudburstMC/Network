package org.cloudburstmc.netty.channel.raknet;

public interface RakChannelConfig {

    int getMaxChannels();

    RakChannelConfig setMaxChannels(int maxChannels);

    long getGuid();

    RakChannelConfig setGuid(long guid);

    int getMtu();

    RakChannelConfig setMtu(int mtu);

    int getProtocolVersion();

    RakChannelConfig setProtocolVersion(int protocolVersion);
}
