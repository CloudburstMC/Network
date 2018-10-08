package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.raknet.RakNetPacket;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class ConnectionRequestPacket implements RakNetPacket {
    private long clientId;
    private long timestamp;
    private boolean serverSecurity;

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeLong(clientId);
        buffer.writeLong(timestamp);
        buffer.writeBoolean(serverSecurity);
    }

    @Override
    public void decode(ByteBuf buffer) {
        clientId = buffer.readLong();
        timestamp = buffer.readLong();
        serverSecurity = buffer.readBoolean();
    }
}
