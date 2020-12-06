package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.ByteBuf;

public interface RakServerChannelConfig {

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
}
