package com.nukkitx.network.synapse.handler;

import com.nukkitx.network.synapse.SynapsePacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public abstract class SynapseHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof SynapsePacket) {
            onPacket(ctx, (SynapsePacket) msg);
        }
    }

    protected abstract void onPacket(ChannelHandlerContext ctx, SynapsePacket packet);
}
