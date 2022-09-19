package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_CONNECTED_PING;
import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_CONNECTED_PONG;

public class ConnectedPingHandler extends AdvancedChannelInboundHandler<EncapsulatedPacket> {
    public static final String NAME = "rak-connected-ping-handler";

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        ByteBuf buf = ((EncapsulatedPacket) msg).getBuffer();
        System.out.println("Inbound 0x" + Integer.toHexString(buf.getUnsignedByte(buf.readerIndex())));
        return buf.getUnsignedByte(buf.readerIndex()) == ID_CONNECTED_PING;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket packet) throws Exception {
        ByteBuf buf = packet.getBuffer();
        buf.readUnsignedByte(); // Packet ID
        long pingTime = buf.readLong();

        System.out.println("Sending pong");

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(17);
        replyBuffer.writeByte(ID_CONNECTED_PONG);
        replyBuffer.writeLong(pingTime);
        replyBuffer.writeLong(System.currentTimeMillis());
        ctx.writeAndFlush(new RakMessage(replyBuffer, RakReliability.UNRELIABLE, RakPriority.IMMEDIATE));
    }
}
