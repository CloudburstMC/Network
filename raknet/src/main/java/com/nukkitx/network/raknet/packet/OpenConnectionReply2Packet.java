package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class OpenConnectionReply2Packet implements RakNetPacket {
    private long serverId;
    private InetSocketAddress clientAddress;
    private int mtuSize;
    private boolean serverSecurity;

    @Override
    public void encode(ByteBuf buffer) {
        RakNetUtil.writeUnconnectedMagic(buffer);
        buffer.writeLong(serverId);
        NetworkUtils.writeAddress(buffer, clientAddress);
        buffer.writeShort(mtuSize);
        buffer.writeBoolean(serverSecurity);
    }

    @Override
    public void decode(ByteBuf buffer) {
        RakNetUtil.verifyUnconnectedMagic(buffer);
        serverId = buffer.readLong();
        clientAddress = NetworkUtils.readAddress(buffer);
        mtuSize = buffer.readUnsignedShort();
        serverSecurity = buffer.readBoolean();
    }
}
