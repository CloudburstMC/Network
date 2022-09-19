package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;

public class ConnectedPongHandler extends AdvancedChannelInboundHandler<EncapsulatedPacket> {
    public static final String NAME = "rak-connected-pong-handler";

    private final RakSessionCodec sessionCodec;

    public ConnectedPongHandler(RakSessionCodec sessionCodec) {
        this.sessionCodec = sessionCodec;
    }

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        ByteBuf buf = ((EncapsulatedPacket) msg).getBuffer();
        return buf.getUnsignedByte(buf.readerIndex()) == RakConstants.ID_CONNECTED_PONG;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket packet) throws Exception {
        System.out.println("Pong inbound");
        ByteBuf buf = packet.getBuffer();
        buf.readUnsignedByte(); // Packet ID
        long pingTime = buf.readLong();
        this.sessionCodec.recalculatePongTime(pingTime);
    }
}
