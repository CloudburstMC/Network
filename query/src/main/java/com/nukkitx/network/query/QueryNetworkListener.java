package com.nukkitx.network.query;

import com.nukkitx.network.NetworkListener;
import com.nukkitx.network.query.codec.QueryPacketCodec;
import com.nukkitx.network.query.handler.QueryPacketHandler;
import com.nukkitx.network.util.NetworkThreadFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.*;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.log4j.Log4j2;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

@Log4j2
public class QueryNetworkListener extends ChannelInitializer<DatagramChannel> implements NetworkListener {
    private final InetSocketAddress address;
    private final QueryEventListener eventListener;
    private final Bootstrap bootstrap;
    private DatagramChannel channel;

    public QueryNetworkListener(InetSocketAddress address, QueryEventListener eventListener) {
        this.address = address;
        this.eventListener = eventListener;

        ThreadFactory factory = NetworkThreadFactory.builder().daemon(true).format("Query Listener").build();
        bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        if (Epoll.isAvailable()) {
            bootstrap.channel(EpollDatagramChannel.class)
                    .group(new EpollEventLoopGroup(0, factory))
                    .option(EpollChannelOption.EPOLL_MODE, EpollMode.EDGE_TRIGGERED);
        } else if (KQueue.isAvailable()) {
            bootstrap.channel(KQueueDatagramChannel.class)
                    .group(new KQueueEventLoopGroup(0, factory));
        } else {
            bootstrap.channel(NioDatagramChannel.class)
                    .group(new NioEventLoopGroup(0, factory));
        }
    }

    @Override
    public boolean bind() {
        boolean success = false;
        try {
            ChannelFuture future = bootstrap.bind(address).await();
            if (future.isSuccess()) {
                log.debug("Bound Query listener to {}", address);
                success = true;
            } else {
                log.error("Was unable to bind Query listener to {}", address, future.cause());
            }
        } catch (InterruptedException e) {
            log.error("Interrupted whilst binding Query listener");
        }
        return success;
    }

    @Override
    public void close() {
        bootstrap.config().group().shutdownGracefully();
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
