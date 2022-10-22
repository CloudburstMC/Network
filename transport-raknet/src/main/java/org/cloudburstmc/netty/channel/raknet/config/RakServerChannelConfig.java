package org.cloudburstmc.netty.channel.raknet.config;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelConfig;

public interface RakServerChannelConfig extends ChannelConfig {

    int getMaxChannels();

    RakServerChannelConfig setMaxChannels(int maxChannels);

    long getGuid();

    RakServerChannelConfig setGuid(long guid);

    int[] getSupportedProtocols();

    RakServerChannelConfig setSupportedProtocols(int[] supportedProtocols);

    int getMaxConnections();

    RakServerChannelConfig setMaxConnections(int maxConnections);

    ByteBuf getUnconnectedMagic();

    RakServerChannelConfig setUnconnectedMagic(ByteBuf unconnectedMagic);

    ByteBuf getAdvertisement();

    RakServerChannelConfig setAdvertisement(ByteBuf advertisement);
}
