package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.PromiseCombiner;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakMetrics;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class RakClientRouteHandler extends ChannelDuplexHandler {

    public static final String NAME = "rak-client-route-handler";
    private final RakClientChannel channel;

    public RakClientRouteHandler(RakClientChannel channel) {
        this.channel = channel;
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        if (!(remoteAddress instanceof InetSocketAddress)) {
            promise.tryFailure(new IllegalArgumentException("Provided remote address must be InetSocketAddress"));
            return;
        }

        if (this.channel.parent().isActive()) {
            throw new IllegalStateException("Channel is already bound!");
        }

        ChannelFuture parentFuture = this.channel.parent().connect(remoteAddress, localAddress);
        parentFuture.addListener(future -> {
           if (future.isSuccess()) {
               this.channel.pipeline().addLast(RakClientOfflineHandler.NAME, new RakClientOfflineHandler(this.channel.getConnectPromise()));
           }
        });

        PromiseCombiner combiner = new PromiseCombiner(this.channel.eventLoop());
        combiner.add(parentFuture);
        combiner.add((ChannelFuture) this.channel.getConnectPromise());
        combiner.finish(promise);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean isDatagram = msg instanceof DatagramPacket;
        if (!isDatagram && !(msg instanceof ByteBuf)) {
            this.channel.parent().write(msg, this.channel.correctPromise(promise));
            return;
        }

        DatagramPacket datagram = isDatagram ? (DatagramPacket) msg : new DatagramPacket((ByteBuf) msg, this.channel.remoteAddress());
        RakMetrics metrics = this.channel.config().getMetrics();
        if (metrics != null) {
            metrics.bytesOut(datagram.content().readableBytes());
        }

        this.channel.parent().write(datagram, this.channel.correctPromise(promise));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            ctx.fireChannelRead(msg);
            return;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        RakMetrics metrics = this.channel.config().getMetrics();
        if (metrics != null) {
            metrics.bytesIn(packet.content().readableBytes());
        }

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
