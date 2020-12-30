package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakServerChannelConfig;

import static org.cloudburstmc.netty.RakNetConstants.ID_UNCONNECTED_PONG;

@Sharable
public class RakServerPingHandler extends SimpleChannelInboundHandler<RakPing> {
    public static String NAME = "rak-ping-handler";


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakPing ping) throws Exception {
        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();

        // TODO: ByteBuf advertBuf = this.server.onUnconnectedPing(ping);
        // Server method may return cached advert if there won't be provided any pong data related to current ping

        ByteBuf pongBuffer = ctx.alloc().ioBuffer(magicBuf.readableBytes() + 19 + advertBuf.readableBytes());
        pongBuffer.writeByte(ID_UNCONNECTED_PONG);
        pongBuffer.writeLong(ping.getPingTime());
        pongBuffer.writeLong(guid);
        pongBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        pongBuffer.writeShort(advertBuf.readableBytes());
        pongBuffer.writeBytes(advertBuf, advertBuf.readerIndex(), advertBuf.readableBytes());
        ctx.writeAndFlush(pongBuffer);
    }
}
