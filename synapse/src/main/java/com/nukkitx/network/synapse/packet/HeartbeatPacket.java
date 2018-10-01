package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import io.netty.buffer.ByteBuf;
import lombok.Data;

@Data
public class HeartbeatPacket implements SynapsePacket {
    private float tps;
    private float load;
    private long upTime;

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeFloat(tps);
        buffer.writeFloat(load);
        buffer.writeLong(upTime);
    }

    @Override
    public void decode(ByteBuf buffer) {
        tps = buffer.readFloat();
        load = buffer.readFloat();
        upTime = buffer.readLong();
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
