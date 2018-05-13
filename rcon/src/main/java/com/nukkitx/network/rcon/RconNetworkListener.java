package com.nukkitx.network.rcon;

import com.nukkitx.network.NetworkListener;
import com.nukkitx.network.rcon.codec.RconCodec;
import com.nukkitx.network.rcon.handler.RconHandler;
import com.nukkitx.network.util.NetworkThreadFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.*;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import lombok.Getter;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class RconNetworkListener extends ChannelInitializer<SocketChannel> implements NetworkListener {
    private final RconEventListener eventListener;
    private final InetSocketAddress address;
    private final ServerBootstrap bootstrap;
    @Getter
    private final ExecutorService commandExecutionService = Executors.newSingleThreadExecutor(
            NetworkThreadFactory.builder().daemon(true).format("RCON Command Executor").build());
    private final byte[] password;
    private SocketChannel channel;

    public RconNetworkListener(RconEventListener eventListener, byte[] password, String address, int port) {
        this.eventListener = eventListener;
        this.password = password;
        this.address = new InetSocketAddress(address, port);

        bootstrap = new ServerBootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        ThreadFactory factory = NetworkThreadFactory.builder().daemon(true).format("RCON Listener").build();
        if (Epoll.isAvailable()) {
            bootstrap.channel(EpollServerSocketChannel.class)
                    .group(new EpollEventLoopGroup(0, factory))
                    .option(EpollChannelOption.EPOLL_MODE, EpollMode.EDGE_TRIGGERED);
        } else if (KQueue.isAvailable()) {
            bootstrap.channel(KQueueServerSocketChannel.class)
                    .group(new KQueueEventLoopGroup(0, factory));
        } else {
            bootstrap.channel(NioServerSocketChannel.class)
                    .group(new NioEventLoopGroup(0, factory));
        }
    }

    @Override
    public boolean bind() {
        return bootstrap.bind(address).awaitUninterruptibly().isSuccess();
    }

    @Override
    public void close() {
        commandExecutionService.shutdown();
        try {
            commandExecutionService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        bootstrap.config().group().shutdownGracefully();
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    public InetSocketAddress getAddress() {
        return null;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        this.channel = socketChannel;

        channel.pipeline().addLast("lengthDecoder", new LengthFieldBasedFrameDecoder(ByteOrder.LITTLE_ENDIAN, 4096, 0, 4, 0, 4, true));
        channel.pipeline().addLast("rconDecoder", new RconCodec());
        channel.pipeline().addLast("rconHandler", new RconHandler(eventListener, password));
        channel.pipeline().addLast("lengthPrepender", new LengthFieldPrepender(ByteOrder.LITTLE_ENDIAN, 4, 0, false));
    }
}
