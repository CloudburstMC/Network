package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;

@ChannelHandler.Sharable
public class EncapsulatedToMessageHandler extends SimpleChannelInboundHandler<EncapsulatedPacket> {
    public static final String NAME = "encapsulated-to-message";
    public static final EncapsulatedToMessageHandler INSTANCE = new EncapsulatedToMessageHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket packet) throws Exception {
        ctx.fireChannelRead(packet.toMessage().retain());
    }
}
