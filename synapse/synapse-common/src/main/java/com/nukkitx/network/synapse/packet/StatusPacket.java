package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class StatusPacket implements SynapsePacket {
    private Status status;

    @Override
    public void encode(ByteBuf buffer) {
        SynapseUtils.writeMagic(buffer);
        buffer.writeByte(status.ordinal());
    }

    @Override
    public void decode(ByteBuf buffer) {
        SynapseUtils.readMagic(buffer);
        status = Status.values()[buffer.readByte()];
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }

    public enum Status {
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        OUTDATED_CLIENT,
        OUTDATED_SERVER
    }
}
