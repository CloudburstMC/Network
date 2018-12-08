package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import io.netty.buffer.ByteBuf;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AlreadyConnectedPacket implements RakNetPacket {
    public static final AlreadyConnectedPacket INSTANCE = new AlreadyConnectedPacket();

    @Override
    public void encode(ByteBuf buffer) {
    }

    @Override
    public void decode(ByteBuf buffer) {
    }
}
