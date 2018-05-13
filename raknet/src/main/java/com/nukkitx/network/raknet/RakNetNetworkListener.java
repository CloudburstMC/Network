package com.nukkitx.network.raknet;

import com.nukkitx.network.NativeUtils;
import com.nukkitx.network.NetworkListener;
import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.codec.DatagramRakNetDatagramCodec;
import com.nukkitx.network.raknet.codec.DatagramRakNetPacketCodec;
import com.nukkitx.network.raknet.handler.ExceptionHandler;
import com.nukkitx.network.raknet.handler.RakNetDatagramHandler;
import com.nukkitx.network.raknet.handler.RakNetPacketHandler;
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
import io.netty.channel.unix.UnixChannelOption;
import lombok.extern.log4j.Log4j2;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;

@Log4j2
public class RakNetNetworkListener<T extends NetworkSession> extends ChannelInitializer<DatagramChannel> implements NetworkListener {
    private final RakNetServer<T> server;
    private final InetSocketAddress address;
    private final Bootstrap bootstrap;
    private final int maxThreads;
    private DatagramChannel channel;

    RakNetNetworkListener(RakNetServer<T> server, InetSocketAddress address, int maxThreads) {
        this.server = server;
        this.address = address;
        this.maxThreads = maxThreads;

        ThreadFactory listenerThreadFactory = NetworkThreadFactory.builder().format("RakNet Listener - #%d")
                .daemon(true).build();
        bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        if (Epoll.isAvailable()) {
            bootstrap.channel(EpollDatagramChannel.class)
                    .group(new EpollEventLoopGroup(0, listenerThreadFactory))
                    .option(EpollChannelOption.EPOLL_MODE, EpollMode.EDGE_TRIGGERED);
        } else if (KQueue.isAvailable()) {
            bootstrap.channel(KQueueDatagramChannel.class)
                    .group(new KQueueEventLoopGroup(0, listenerThreadFactory));
        } else {
            bootstrap.channel(NioDatagramChannel.class)
                    .group(new NioEventLoopGroup(0, listenerThreadFactory));
        }

        if (NativeUtils.isReusePortAvailable()) {
            bootstrap.option(UnixChannelOption.SO_REUSEPORT, true);
        }
    }

    @Override
    public boolean bind() {
        int threads = NativeUtils.isReusePortAvailable() ? maxThreads : 1;

        boolean success = false;

        for (int i = 0; i < threads; i++) {
            try {
                ChannelFuture future = bootstrap.bind(address).await();
                if (future.isSuccess()) {
                    log.debug("Bound RakNet listener #{} to {}", i, address);
                    success = true;
                } else {
                    log.warn("Was unable to bind RakNet listener #{} to {}", i, address, future.cause());
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted whilst binding RakNet listener #" + i);
            }
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
    protected void initChannel(DatagramChannel datagramChannel) throws Exception {
        this.channel = datagramChannel;
        channel.pipeline()
                .addLast("datagramRakNetPacketCodec", new DatagramRakNetPacketCodec(server))
                .addLast("raknetPacketHandler", new RakNetPacketHandler(server))
                .addLast("datagramRakNetDatagramCodec", new DatagramRakNetDatagramCodec(server))
                .addLast("raknetDatagramHandler", new RakNetDatagramHandler<>(server))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    @Override
    public InetSocketAddress getAddress() {
        return null;
    }
}
