package org.cloudburstmc.netty.handler.codec.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;


import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakClientPongHandler extends AdvancedChannelInboundHandler<DatagramPacket> {

    public static final String NAME = "rak-client-pong-handler";
    public static final RakClientPongHandler INSTANCE = new RakClientPongHandler();

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)){
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        return buf.isReadable() && buf.getUnsignedByte(buf.readerIndex()) == ID_UNCONNECTED_PONG;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        buf.readUnsignedByte(); // Packet ID

        long pingTime  = buf.readLong();
        long guid = buf.readLong();

        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        if (!buf.isReadable(magicBuf.readableBytes()) || !ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf)) {
            // Magic does not match
            return;
        }

        byte[] pongData = null;
        if (buf.isReadable(2)) { // length
            pongData = new byte[buf.readUnsignedShort()];
            buf.readBytes(pongData);
        }

        long pongTime = System.currentTimeMillis();
        ctx.fireChannelRead(new RakPong(pingTime, pongTime, guid,pongData, packet.sender()));
    }
}
