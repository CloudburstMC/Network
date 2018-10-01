package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class RedirectPacket implements SynapsePacket {
    private final List<ByteBuf> packets = new ArrayList<>();
    private UUID uuid;
    private boolean direct;

    @Override
    public void encode(ByteBuf buffer) {
        SynapseUtils.writeUuid(buffer, uuid);
        buffer.writeBoolean(direct);

        buffer.writeInt(packets.size());
        for (ByteBuf packet : packets) {
            buffer.writeInt(packet.readableBytes());
            buffer.writeBytes(packet);
            packet.release();
        }
    }

    @Override
    public void decode(ByteBuf buffer) {
        uuid = SynapseUtils.readUuid(buffer);
        direct = buffer.readBoolean();

        int length = buffer.readInt();
        for (int i = length; i >= 0; i--) {
            packets.add(buffer.readSlice(buffer.readInt()));
            // We sliced the buffer so we need to retain.
            buffer.retain();
        }
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }
}
