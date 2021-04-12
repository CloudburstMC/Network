package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetServer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

@ChannelHandler.Sharable
public class ServerMessageHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-server-message-handler";
    private final RakNetServer server;

    public ServerMessageHandler(RakNetServer server) {
        this.server = server;
    }

    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        if (!super.acceptInboundMessage(msg)) {
            return false;
        }

        // Drop incoming traffic from blocked address
        DatagramPacket packet = (DatagramPacket) msg;
        return !this.server.isBlocked(packet.sender().getAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buffer = packet.content();
        if (!buffer.isReadable()) {
            return;
        }

        if (this.server.getMetrics() != null) {
            this.server.getMetrics().bytesIn(buffer.readableBytes());
        }
        ctx.fireChannelRead(packet.retain());
    }
}
