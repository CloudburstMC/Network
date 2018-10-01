package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class UnconnectedPingPacket implements RakNetPacket {
    private long timestamp;
    private long clientId;

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeLong(timestamp);
        RakNetUtil.writeUnconnectedMagic(buffer);
        buffer.writeLong(clientId);
    }

    @Override
    public void decode(ByteBuf buffer) {
        timestamp = buffer.readLong();
        RakNetUtil.verifyUnconnectedMagic(buffer);
        if (buffer.isReadable(8)) { // Server lists don't write this which causes errors. Please fix.
            clientId = buffer.readLong();
        }
    }
}
