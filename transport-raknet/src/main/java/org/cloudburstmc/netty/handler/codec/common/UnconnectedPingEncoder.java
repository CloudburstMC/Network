package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_UNCONNECTED_PING;

@ChannelHandler.Sharable
public class UnconnectedPingEncoder extends ChannelOutboundHandlerAdapter {
    public static final UnconnectedPingEncoder INSTANCE = new UnconnectedPingEncoder();
    public static final String NAME = "rak-unconnected-ping-encoder";

    public UnconnectedPingEncoder() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof RakPing)) {
            ctx.write(msg, promise);
            return;
        }

        RakPing ping = (RakPing) msg;
        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        long guid = ctx.channel().config().getOption(RakChannelOption.RAK_GUID);

        ByteBuf pingBuffer = ctx.alloc().ioBuffer(magicBuf.readableBytes() + 17);
        pingBuffer.writeByte(ID_UNCONNECTED_PING);
        pingBuffer.writeLong(ping.getPingTime());
        pingBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        pingBuffer.writeLong(guid);
        ctx.write(new DatagramPacket(pingBuffer, ping.getSender()));
    }
}
