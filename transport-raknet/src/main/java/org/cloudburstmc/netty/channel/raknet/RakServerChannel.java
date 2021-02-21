package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import org.cloudburstmc.netty.channel.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.server.RakServerOfflineHandler;
import org.cloudburstmc.netty.handler.codec.server.RakServerRouteHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RakServerChannel extends ProxyChannel<DatagramChannel> implements ServerChannel {

    private final RakServerChannelConfig config;
    private final Map<SocketAddress, RakChildChannel> childChannelMap = new ConcurrentHashMap<>();

    public RakServerChannel(DatagramChannel channel) {
        super(channel);
        this.config = new DefaultRakServerConfig(this);
        this.pipeline().addLast(RakServerRouteHandler.NAME, new RakServerRouteHandler(this));
        // In case of proxied connections (fe. HAProxy) customized handler should be injected before.
        // Default common handler of offline phase. Handles only raknet packets, forwards rest.
        this.pipeline.addLast(RakServerOfflineHandler.NAME, RakServerOfflineHandler.INSTANCE);
    }

    /**
     * Create new child channel assigned to remote address.
     * @param address remote address of new connection.
     * @return RakChildChannel instance of new channel.
     */
    public RakChildChannel createChildChannel(InetSocketAddress address) {
       if (this.childChannelMap.containsKey(address)) {
           return null;
       }

       RakChildChannel channel = new RakChildChannel(address, this);
       channel.closeFuture().addListener((GenericFutureListener<ChannelFuture>) this::onChildClosed);

       // Register channel to event loop and fully initialize channel
       this.pipeline().fireChannelRead(channel).fireChannelReadComplete();
       this.childChannelMap.put(address, channel);
       return channel;
    }

    public RakChildChannel getChildChannel(SocketAddress address) {
        return this.childChannelMap.get(address);
    }

    private void onChildClosed(ChannelFuture channelFuture) {
        Channel channel = channelFuture.channel();
        this.childChannelMap.remove(channel.remoteAddress());
    }

    @Override
    public void onCloseTriggered(ChannelPromise promise) {
        PromiseCombiner combiner = new PromiseCombiner(this.eventLoop());
        this.childChannelMap.values().forEach(channel -> combiner.add(channel.close()));

        ChannelPromise combinedPromise = this.newPromise();
        combinedPromise.addListener(future -> super.onCloseTriggered(promise));
        combiner.finish(combinedPromise);
    }

    @Override
    public RakServerChannelConfig config() {
        return this.config;
    }
}
