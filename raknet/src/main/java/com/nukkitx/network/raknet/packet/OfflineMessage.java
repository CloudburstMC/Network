package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class OfflineMessage implements RakNetPacket {

    @Override
    public void encode(ByteBuf buffer) {
        RakNetUtil.writeUnconnectedMagic(buffer);
    }

    @Override
    public void decode(ByteBuf buffer) {
        RakNetUtil.verifyUnconnectedMagic(buffer);
    }
}
