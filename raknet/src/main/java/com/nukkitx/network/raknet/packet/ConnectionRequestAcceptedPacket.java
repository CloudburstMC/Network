package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class ConnectionRequestAcceptedPacket implements RakNetPacket {
    private InetSocketAddress systemAddress;
    private short systemIndex;
    private InetSocketAddress[] systemAddresses;
    private long incomingTimestamp;
    private long systemTimestamp;

    @Override
    public void encode(ByteBuf buffer) {
        NetworkUtils.writeAddress(buffer, systemAddress);
        buffer.writeShort(systemIndex);
        for (InetSocketAddress address : systemAddresses) {
            NetworkUtils.writeAddress(buffer, address);
        }
        buffer.writeLong(incomingTimestamp);
        buffer.writeLong(systemTimestamp);
    }

    @Override
    public void decode(ByteBuf buffer) {
        systemAddress = NetworkUtils.readAddress(buffer);
        systemIndex = buffer.readShort();
        systemAddresses = new InetSocketAddress[20];
        for (int i = 0; i < 20; i++) {
            if (buffer.readableBytes() > 16) {
                systemAddresses[i] = NetworkUtils.readAddress(buffer);
            } else {
                systemAddresses[i] = RakNetUtil.JUNK_ADDRESS;
            }
        }
        incomingTimestamp = buffer.readLong();
        systemTimestamp = buffer.readLong();
    }
}
