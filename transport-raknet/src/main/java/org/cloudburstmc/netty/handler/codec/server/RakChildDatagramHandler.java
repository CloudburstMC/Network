package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
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

        this.channel.parent().parent().write(datagram).addListener((ChannelFuture future) -> {
            if (!future.isSuccess() && !(future.cause() instanceof ClosedChannelException)) {
                this.channel.pipeline().fireExceptionCaught(future.cause());
                this.channel.close();
            }
        });
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (this.canFlush) {
            this.canFlush = false;
            ctx.flush();
        }
    }
}
