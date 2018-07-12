package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.util.UUID;

@Data
public class PlayerLogoutPacket implements SynapsePacket {
    private UUID uuid;
    private String reason;

    @Override
    public void encode(ByteBuf buffer) {
        SynapseUtils.writeUuid(buffer, uuid);
        SynapseUtils.writeString(buffer, reason);
    }

    @Override
    public void decode(ByteBuf buffer) {
        uuid = SynapseUtils.readUuid(buffer);
        reason = SynapseUtils.readString(buffer);
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
