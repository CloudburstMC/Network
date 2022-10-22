package org.cloudburstmc.netty.handler.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.netty.channel.ProxyChannel;

import java.net.PortUnreachableException;
import java.net.SocketAddress;

public class ProxyOutboundRouter implements ChannelOutboundHandler {

    public static final String NAME = "rak-proxy-outbound-router";
    private final ProxyChannel<?> proxiedChannel;

    public ProxyOutboundRouter(ProxyChannel<?> proxiedChannel) {
        this.proxiedChannel = proxiedChannel;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        // Ignore
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        // Ignore
    }

    @Override
    public void bind(ChannelHandlerContext ctx, SocketAddress address, ChannelPromise promise) throws Exception {
        this.proxiedChannel.parent().bind(address, this.proxiedChannel.correctPromise(promise));
    }

    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
        this.proxiedChannel.parent().connect(remoteAddress, localAddress, this.proxiedChannel.correctPromise(promise));
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
       this.proxiedChannel.onCloseTriggered(promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        this.proxiedChannel.parent().disconnect(this.proxiedChannel.correctPromise(promise));
    }

    @Override
    public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        this.proxiedChannel.parent().deregister(this.proxiedChannel.correctPromise(promise));
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        this.proxiedChannel.parent().read();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        this.proxiedChannel.parent().write(msg, this.proxiedChannel.correctPromise(promise));
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        this.proxiedChannel.parent().flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) throws Exception {
        if (!(throwable instanceof PortUnreachableException)) {
            ctx.fireExceptionCaught(throwable);
        }
    }
}
