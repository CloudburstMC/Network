package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.net.InetSocketAddress;

@Data
public class PlayerLoginPacket implements SynapsePacket {
    boolean firstTime;
    private InetSocketAddress address;
    private String authData;
    private String clientData;

    @Override
    public void encode(ByteBuf buffer) {
        NetworkUtils.writeAddress(buffer, address);
        SynapseUtils.writeString(buffer, authData);
        SynapseUtils.writeString(buffer, clientData);
        buffer.writeBoolean(firstTime);
    }

    @Override
    public void decode(ByteBuf buffer) {
        address = NetworkUtils.readAddress(buffer);
        authData = SynapseUtils.readString(buffer);
        clientData = SynapseUtils.readString(buffer);
        firstTime = buffer.readBoolean();
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
