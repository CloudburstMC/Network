package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_CONNECTED_PING;

public class RakSessionCodecCompatible extends RakSessionCodec {
    public static final String NAME = "rak-session-codec";

    public RakSessionCodecCompatible(RakChannel channel) {
        super(channel);
    }

    @Override
    EncapsulatedPacket createEncapsulatedPacket() {
        return EncapsulatedPacket.newInstance();
    }

    @Override
    void writePing(ChannelHandlerContext ctx, long curTime) {
        if (this.currentPingTime + 2000L < curTime && this.datagramWriteIndex > 1) {
            ByteBuf buffer = ctx.alloc().ioBuffer(9);
            buffer.writeByte(ID_CONNECTED_PING);
            buffer.writeLong(System.nanoTime() / 1_000_000L);
            this.currentPingTime = curTime;
            this.write(ctx, new RakMessage(buffer, RakReliability.UNRELIABLE, RakPriority.IMMEDIATE), ctx.voidPromise());
        }
    }
}
