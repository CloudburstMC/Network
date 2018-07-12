package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class ConnectPacket implements SynapsePacket {
    private int protocolVersion;
    private String jwe;

    @Override
    public void encode(ByteBuf buffer) {
        SynapseUtils.writeMagic(buffer);
        buffer.writeInt(protocolVersion);
        SynapseUtils.writeString(buffer, jwe);
    }

    @Override
    public void decode(ByteBuf buffer) {
        SynapseUtils.readMagic(buffer);
        protocolVersion = buffer.readInt();
        jwe = SynapseUtils.readString(buffer);
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
