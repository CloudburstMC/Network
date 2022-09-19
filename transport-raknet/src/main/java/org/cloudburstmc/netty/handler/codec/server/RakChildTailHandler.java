package org.cloudburstmc.netty.handler.codec.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;

public class RakChildTailHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "rak-child-tail-handler";

    private final RakChildChannel channel;

    public RakChildTailHandler(RakChildChannel channel) {
        this.channel = channel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        System.out.println("Reached the tail?\n" + ByteBufUtil.prettyHexDump((ByteBuf) msg));
        final Object message = msg instanceof EncapsulatedPacket ? ((EncapsulatedPacket) msg).toMessage() : ReferenceCountUtil.retain(msg);
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.pipeline().fireChannelRead(message).fireChannelReadComplete();
        } else {
            this.channel.eventLoop().execute(() -> this.channel.pipeline().fireChannelRead(message).fireChannelReadComplete());
        }
    }
}
