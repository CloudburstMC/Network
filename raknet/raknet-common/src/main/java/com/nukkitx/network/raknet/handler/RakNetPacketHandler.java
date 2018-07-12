package com.nukkitx.network.raknet.handler;

import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public abstract class RakNetPacketHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DirectAddressedRakNetPacket) {
            DirectAddressedRakNetPacket packet = (DirectAddressedRakNetPacket) msg;
            try {
                messageReceived(ctx, packet);
            } finally {
                packet.release();
            }
        }
    }

    protected abstract void messageReceived(ChannelHandlerContext ctx, DirectAddressedRakNetPacket packet) throws Exception;
}
