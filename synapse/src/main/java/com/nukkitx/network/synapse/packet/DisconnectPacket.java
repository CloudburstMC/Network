package com.nukkitx.network.synapse.packet;

import com.nukkitx.network.synapse.SynapsePacket;
import com.nukkitx.network.synapse.SynapsePacketHandler;
import com.nukkitx.network.synapse.SynapseUtils;
import io.netty.buffer.ByteBuf;

public class DisconnectPacket implements SynapsePacket {
    private Reason reason;
    private String message;

    @Override
    public void encode(ByteBuf buffer) {
        buffer.writeByte(reason.ordinal());
        SynapseUtils.writeString(buffer, message);
    }

    @Override
    public void decode(ByteBuf buffer) {
        reason = Reason.byId(buffer.readByte());
        message = SynapseUtils.readString(buffer);
    }

    @Override
    public void handle(SynapsePacketHandler handler) {
        handler.handle(this);
    }

    public enum Reason {
        GENERIC,
        INCOMPATIBLE_PROTOCOL;

        public static Reason byId(byte id) {
            try {
                return values()[id];
            } catch (Exception e) {
                return GENERIC;
            }
        }
    }
}
