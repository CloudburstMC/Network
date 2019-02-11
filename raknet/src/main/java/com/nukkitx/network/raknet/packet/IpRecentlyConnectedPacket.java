package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import io.netty.buffer.ByteBuf;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class IpRecentlyConnectedPacket implements RakNetPacket {
    public static final IpRecentlyConnectedPacket INSTANCE = new IpRecentlyConnectedPacket();

    @Override
    public void encode(ByteBuf buffer) {

    }

    @Override
    public void decode(ByteBuf buffer) {

    }
}
