package com.nukkitx.network.query;

import com.nukkitx.network.NetworkListener;
import com.nukkitx.network.query.codec.QueryPacketCodec;
import com.nukkitx.network.query.handler.QueryPacketHandler;
import com.nukkitx.network.util.Bootstraps;
import com.nukkitx.network.util.EventLoops;
import com.nukkitx.network.util.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.DatagramChannel;

import java.net.InetSocketAddress;

public class QueryNetworkListener extends ChannelInitializer<DatagramChannel> implements NetworkListener {
    private final InetSocketAddress address;
    private final QueryEventListener eventListener;
    private final Bootstrap bootstrap;
    private DatagramChannel channel;

    public QueryNetworkListener(InetSocketAddress address, QueryEventListener eventListener) {
        this.address = address;
        this.eventListener = eventListener;

        bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        Bootstraps.setupBootstrap(bootstrap, true);
        this.bootstrap.group(EventLoops.commonGroup());
    }

    @Override
    public boolean bind() {
        Preconditions.checkState(channel == null, "Channel already initialized");

        ChannelFuture future = bootstrap.bind(address).awaitUninterruptibly();

        return future.isSuccess();
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    public InetSocketAddress getAddress() {
        return address;
    }

    @Override
    protected void initChannel(DatagramChannel datagramChannel) throws Exception {
        this.channel = datagramChannel;
        channel.pipeline()
                .addLast("queryPacketCodec", new QueryPacketCodec())
                .addLast("queryPacketHandler", new QueryPacketHandler(eventListener));
    }
}
