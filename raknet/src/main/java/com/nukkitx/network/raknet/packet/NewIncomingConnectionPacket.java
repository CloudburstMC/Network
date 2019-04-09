package com.nukkitx.network.raknet.packet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.RakNetUtil;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class NewIncomingConnectionPacket implements RakNetPacket {
    private InetSocketAddress serverAddress;
    private InetSocketAddress[] systemAddresses;
    private long clientTimestamp;
    private long serverTimestamp;

    @Override
    public void encode(ByteBuf buffer) {
        NetworkUtils.writeAddress(buffer, serverAddress);
        for (InetSocketAddress address : systemAddresses) {
            NetworkUtils.writeAddress(buffer, address);
        }
        buffer.writeLong(clientTimestamp);
        buffer.writeLong(serverTimestamp);
    }

    @Override
    public void decode(ByteBuf buffer) {
        serverAddress = NetworkUtils.readAddress(buffer);
        systemAddresses = new InetSocketAddress[20];
        for (int i = 0; i < 20; i++) {
            if (buffer.readableBytes() > 16) {
                systemAddresses[i] = NetworkUtils.readAddress(buffer);
            } else {
                systemAddresses[i] = RakNetUtil.JUNK_ADDRESS;
            }
        }
        clientTimestamp = buffer.readLong();
        serverTimestamp = buffer.readLong();
    }
}
