package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.Cleanup;

public abstract class RakNetPacketHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DirectAddressedRakNetPacket) {
            @Cleanup("release") DirectAddressedRakNetPacket packet = (DirectAddressedRakNetPacket) msg;
            messageReceived(ctx, packet);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    protected abstract void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception;
}
