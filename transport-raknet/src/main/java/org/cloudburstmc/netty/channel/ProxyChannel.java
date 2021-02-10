package org.cloudburstmc.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import org.cloudburstmc.netty.handler.codec.ProxyInboundRouter;
import org.cloudburstmc.netty.handler.codec.ProxyOutboundRouter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public abstract class ProxyChannel<T extends Channel> implements Channel {

    /**
     * TODO: consider config mixing with parent
     */

    protected final T channel;
    protected final ChannelPipeline pipeline;

    protected ProxyChannel(T parent) {
        ObjectUtil.checkNotNull(parent, "parent");
        this.channel = parent;
        this.pipeline = new DefaultChannelPipeline(this){};
        this.pipeline.addLast(ProxyInboundRouter.NAME, new ProxyInboundRouter(this));
        this.pipeline.addLast(ProxyOutboundRouter.NAME, new ProxyOutboundRouter(this));
    }

    public void onCloseTriggered(ChannelPromise promise) {
        this.channel.close(this.correctPromise(promise));
    }

    public ChannelPromise correctPromise(ChannelPromise remotePromise) {
        ChannelPromise localPromise = this.channel.newPromise();
        localPromise.addListener(future -> {
           if (future.isSuccess()) {
               remotePromise.trySuccess();
           } else {
               remotePromise.tryFailure(future.cause());
           }
        });
        return localPromise;
    }

    @Override
    public ChannelId id() {
        return this.channel.id();
    }

    @Override
    public EventLoop eventLoop() {
        return this.channel.eventLoop();
    }

    @Override
    public Channel parent() {
        return this.channel;
    }

    @Override
    public ChannelConfig config() {
        return this.channel.config();
    }

    @Override
    public boolean isOpen() {
        return this.channel.isOpen();
    }

    @Override
    public boolean isRegistered() {
        return this.channel.isRegistered();
    }

    @Override
    public boolean isActive() {
        return this.channel.isActive();
    }

    @Override
    public ChannelMetadata metadata() {
        return this.channel.metadata();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) this.channel.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) this.channel.remoteAddress();
    }

    @Override
    public ChannelFuture closeFuture() {
        return this.channel.closeFuture();
    }

    @Override
    public boolean isWritable() {
        return this.channel.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        return this.channel.bytesBeforeUnwritable();
    }

    @Override
    public long bytesBeforeWritable() {
        return this.channel.bytesBeforeWritable();
    }

    @Override
    public Unsafe unsafe() {
        return this.channel.unsafe();
    }

    @Override
    public ChannelPipeline pipeline() {
        return this.pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return this.config().getAllocator();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return this.pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return this.pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return this.pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return this.pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return this.pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return this.pipeline.deregister();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return this.pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return this.pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return this.pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return this.pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return this.pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return this.pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        return this.channel.read();
    }

    @Override
    public ChannelFuture write(Object msg) {
        return this.pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return this.pipeline.write(msg, promise);
    }

    @Override
    public Channel flush() {
        this.pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return this.pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return this.pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelPromise newPromise() {
        return this.pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return this.pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return this.pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return this.pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelPromise voidPromise() {
        return this.pipeline.voidPromise();
    }

    @Override
    public <U> Attribute<U> attr(AttributeKey<U> key) {
        return this.channel.attr(key);
    }

    @Override
    public <U> boolean hasAttr(AttributeKey<U> key) {
        return this.channel.hasAttr(key);
    }

    @Override
    public int compareTo(Channel o) {
        return this.channel.compareTo(o);
    }
}
