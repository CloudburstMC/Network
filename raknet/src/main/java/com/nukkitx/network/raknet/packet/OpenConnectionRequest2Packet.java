package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class OpenConnectionRequest2Packet implements RakNetPacket {
    private InetSocketAddress serverAddress;
    private short mtuSize;
    private long clientId;

    @Override
    public void encode(ByteBuf buffer) {
        RakNetUtil.writeUnconnectedMagic(buffer);
        NetworkUtils.writeAddress(buffer, serverAddress);
        buffer.writeShort(mtuSize);
        buffer.writeLong(clientId);
    }

    @Override
    public void decode(ByteBuf buffer) {
        RakNetUtil.verifyUnconnectedMagic(buffer);
        serverAddress = NetworkUtils.readAddress(buffer);
        mtuSize = buffer.readShort();
        clientId = buffer.readLong();
    }
}
