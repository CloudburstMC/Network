package org.cloudburstmc.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;

import java.net.SocketAddress;

public abstract class ProxyChannel<T extends Channel> implements Channel {

    protected final T channel;

    protected ProxyChannel(T channel) {
        ObjectUtil.checkNotNull(channel, "channel");
        this.channel = channel;
    }

    @Override
    public ChannelId id() {
        return channel.id();
    }

    @Override
    public EventLoop eventLoop() {
        return channel.eventLoop();
    }

    @Override
    public Channel parent() {
        return channel.parent();
    }

    @Override
    public ChannelConfig config() {
        return channel.config();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public boolean isRegistered() {
        return channel.isRegistered();
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public ChannelMetadata metadata() {
        return channel.metadata();
    }

    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public ChannelFuture closeFuture() {
        return channel.closeFuture();
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        return channel.bytesBeforeUnwritable();
    }

    @Override
    public long bytesBeforeWritable() {
        return channel.bytesBeforeWritable();
    }

    @Override
    public Unsafe unsafe() {
        return channel.unsafe();
    }

    @Override
    public ChannelPipeline pipeline() {
        return channel.pipeline();
    }

    @Override
    public ByteBufAllocator alloc() {
        return channel.alloc();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return channel.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return channel.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return channel.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return channel.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return channel.close();
    }

    @Override
    public ChannelFuture deregister() {
        return channel.deregister();
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return channel.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return channel.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return channel.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return channel.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return channel.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return channel.deregister(promise);
    }

    @Override
    public Channel read() {
        return channel.read();
    }

    @Override
    public ChannelFuture write(Object msg) {
        return channel.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return channel.write(msg, promise);
    }

    @Override
    public Channel flush() {
        return channel.flush();
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return channel.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return channel.writeAndFlush(msg);
    }

    @Override
    public ChannelPromise newPromise() {
        return channel.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return channel.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return channel.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return channel.newFailedFuture(cause);
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel.voidPromise();
    }

    @Override
    public <U> Attribute<U> attr(AttributeKey<U> key) {
        return channel.attr(key);
    }

    @Override
    public <U> boolean hasAttr(AttributeKey<U> key) {
        return channel.hasAttr(key);
    }

    @Override
    public int compareTo(Channel o) {
        return channel.compareTo(o);
    }
}
