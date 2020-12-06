package org.cloudburstmc.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakChannelOption;

import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakServerOfflineMessageHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        if (buf.isReadable(1)) return; // Empty packet?

        buf.markReaderIndex();
        short packetId = buf.readUnsignedByte();

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                if (!buf.isReadable(4)) return;
                long pingTime = buf.readLong();
                if (!verifyUnconnectedMagic(buf, ctx.channel())) return;

                break;
            case ID_OPEN_CONNECTION_REQUEST_1:

                break;
            case ID_OPEN_CONNECTION_REQUEST_2:
                break;
        }
    }

    private static boolean verifyUnconnectedMagic(ByteBuf buf, Channel channel) {
        ByteBuf magicBuf = channel.config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);

        if (!buf.isReadable(magicBuf.readableBytes())) return false;
        return ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
    }
}
