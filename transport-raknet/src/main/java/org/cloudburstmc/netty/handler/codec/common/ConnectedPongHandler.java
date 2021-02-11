package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.cloudburstmc.netty.EncapsulatedPacket;
import org.cloudburstmc.netty.RakNetConstants;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;
import org.cloudburstmc.netty.handler.codec.RakSessionCodec;

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

        ByteBuf buf = ((EncapsulatedPacket) msg).buffer;
        return buf.getUnsignedByte(buf.readerIndex()) == RakNetConstants.ID_CONNECTED_PONG;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket packet) throws Exception {
        ByteBuf buf = packet.buffer;
        buf.readUnsignedByte(); // Packet ID
        long pingTime = buf.readLong();

        // TODO: notify session about pong received
        // this.sessionCodec.recalculatePingTime();
    }
}
