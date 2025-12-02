package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Promise;

public class RakClientFirstPacketHandler extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "rak-client-first-packet-handler";

    private final Promise<Object> packetPromise;

    public RakClientFirstPacketHandler(Promise<Object> packetPromise) {
        this.packetPromise = packetPromise;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ctx.channel().pipeline().remove(RakClientFirstPacketHandler.NAME);
        this.packetPromise.setSuccess(msg);
    }
}
