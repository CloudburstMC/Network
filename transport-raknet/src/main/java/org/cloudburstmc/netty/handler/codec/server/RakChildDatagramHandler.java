package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakMetrics;

import java.nio.channels.ClosedChannelException;

public class RakChildDatagramHandler extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "rak-child-datagram-handler";
    private final RakChildChannel channel;
    private volatile boolean canFlush = false;

    public RakChildDatagramHandler(RakChildChannel channel) {
        this.channel = channel;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean isDatagram = msg instanceof DatagramPacket;
        if (!isDatagram && !(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }

        this.canFlush = true;
        promise.trySuccess();
        DatagramPacket datagram = isDatagram ? (DatagramPacket) msg : new DatagramPacket((ByteBuf) msg, this.channel.remoteAddress());

        RakMetrics metrics = this.channel.config().getMetrics();
        if (metrics != null) {
            metrics.bytesOut(datagram.content().readableBytes());
        }

        ChannelFuture channelFuture = this.channel.parent().write(datagram);
        channelFuture.addListener((ChannelFuture future) -> {
            if (!future.isSuccess() && !(future.cause() instanceof ClosedChannelException)) {
                future.channel().pipeline().fireExceptionCaught(future.cause());
                future.channel().close();
            }
        });
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (this.canFlush) {
            this.canFlush = false;
            this.channel.parent().flush();
        }
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        // Ignore
    }
}
