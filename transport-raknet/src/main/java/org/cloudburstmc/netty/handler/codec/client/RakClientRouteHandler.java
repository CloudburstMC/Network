package org.cloudburstmc.netty.handler.codec.client;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;

import java.net.SocketAddress;

public class RakClientRouteHandler extends ChannelDuplexHandler {

    public static final String NAME = "rak-client-route-handler";
    private final RakClientChannel channel;

    public RakClientRouteHandler(RakClientChannel channel) {
        this.channel = channel;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        // TODO:
        // - parent active check
        // - parent.connect() handle future
        // finish original promise
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        this.channel.write(msg, this.channel.correctPromise(promise));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        try {
            if (packet.sender() == null || packet.sender() == this.channel.remoteAddress()) {
                ctx.fireChannelRead(packet.content().retain());
            }
        } finally {
            packet.release();
        }
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        // Ignore
    }
}
