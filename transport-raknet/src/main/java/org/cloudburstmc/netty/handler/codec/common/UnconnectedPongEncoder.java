package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_UNCONNECTED_PONG;

@ChannelHandler.Sharable
public class UnconnectedPongEncoder extends ChannelOutboundHandlerAdapter {
    public static final UnconnectedPongEncoder INSTANCE = new UnconnectedPongEncoder();
    public static final String NAME = "rak-unconnected-pong-encoder";

    public UnconnectedPongEncoder() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof RakPong)) {
            ctx.write(msg, promise);
            return;
        }

        RakPong pong = (RakPong) msg;
        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        long guid = ctx.channel().config().getOption(RakChannelOption.RAK_GUID);

        ByteBuf pongBuffer = ctx.alloc().ioBuffer(magicBuf.readableBytes() + 19 + pong.getPongData().length);
        pongBuffer.writeByte(ID_UNCONNECTED_PONG);
        pongBuffer.writeLong(pong.getPingTime());
        pongBuffer.writeLong(guid);
        pongBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        pongBuffer.writeShort(pong.getPongData().length);
        pongBuffer.writeBytes(pong.getPongData());
        ctx.write(new DatagramPacket(pongBuffer, pong.getSender()));
    }
}
