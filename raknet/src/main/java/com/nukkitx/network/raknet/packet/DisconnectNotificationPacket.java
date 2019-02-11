package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import io.netty.buffer.ByteBuf;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DisconnectNotificationPacket implements RakNetPacket {
    public static final DisconnectNotificationPacket INSTANCE = new DisconnectNotificationPacket();

    @Override
    public void encode(ByteBuf buffer) {
        // No payload
    }

    @Override
    public void decode(ByteBuf buffer) {
        // No payload
    }
}
