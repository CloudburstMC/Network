package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class UnconnectedPongPacket implements RakNetPacket {
    private long pingId;
    private long serverId;
    private String advertisement;

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeLong(pingId);
        buffer.writeLong(serverId);
        RakNetUtil.writeUnconnectedMagic(buffer);
        RakNetUtil.writeString(buffer, advertisement);
    }

    @Override
    public void decode(ByteBuf buffer) {
        pingId = buffer.readLong();
        serverId = buffer.readLong();
        RakNetUtil.verifyUnconnectedMagic(buffer);
        advertisement = RakNetUtil.readString(buffer);
    }
}
