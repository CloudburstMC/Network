package org.cloudburstmc.netty.channel.raknet.packet;

import io.netty.buffer.ByteBuf;

public interface RakCodecPacket {

    void decode(ByteBuf buffer);
    void encode(ByteBuf buffer);
}
