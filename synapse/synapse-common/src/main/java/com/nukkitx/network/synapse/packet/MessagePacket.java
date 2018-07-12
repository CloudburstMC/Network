package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class MessagePacket implements SynapsePacket {
    private String channel;
    private byte[] payload;

    @Override
    public void encode(ByteBuf buffer) {
        SynapseUtils.writeString(buffer, channel);
        SynapseUtils.writeByteArray(buffer, payload);
    }

    @Override
    public void decode(ByteBuf buffer) {
        SynapseUtils.readString(buffer);
        payload = SynapseUtils.readByteArray(buffer);
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
