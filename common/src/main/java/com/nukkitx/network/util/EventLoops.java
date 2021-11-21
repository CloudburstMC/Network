package com.nukkitx.network.util;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.*;
import io.netty.channel.kqueue.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;

import java.util.concurrent.ThreadFactory;
import java.util.function.Function;

@UtilityClass
public final class EventLoops {
    private static final ChannelType CHANNEL_TYPE;
    private static EventLoopGroup EVENT_LOOP_GROUP;
    private static final ThreadFactory EVENT_LOOP_FACTORY = NetworkThreadFactory.builder().format("Network Listener - #%d")
            .daemon(true).build();

    static {
        boolean disableNative = System.getProperties().contains("disableNativeEventLoop");

        if (!disableNative && Epoll.isAvailable()) {
            CHANNEL_TYPE = ChannelType.EPOLL;
        } else if (!disableNative && KQueue.isAvailable()) {
            CHANNEL_TYPE = ChannelType.KQUEUE;
        } else {
            CHANNEL_TYPE = ChannelType.NIO;
        }
    }

    public static EventLoopGroup commonGroup() {
        if (EVENT_LOOP_GROUP == null) {
            EVENT_LOOP_GROUP = newEventLoopGroup(0);
        }
        return EVENT_LOOP_GROUP;
    }

    public static EventLoopGroup newEventLoopGroup(int threads) {
        return CHANNEL_TYPE.eventLoopGroupFactory.apply(threads);
    }

    public static ChannelType getChannelType() {
        return CHANNEL_TYPE;
    }

    @Getter
    @RequiredArgsConstructor
    public enum ChannelType {
        EPOLL(EpollDatagramChannel.class, EpollSocketChannel.class, EpollServerSocketChannel.class,
                threads -> new EpollEventLoopGroup(threads, EVENT_LOOP_FACTORY), Epoll.isAvailable()),
        KQUEUE(KQueueDatagramChannel.class, KQueueSocketChannel.class, KQueueServerSocketChannel.class,
                threads -> new KQueueEventLoopGroup(threads, EVENT_LOOP_FACTORY), KQueue.isAvailable()),
        NIO(NioDatagramChannel.class, NioSocketChannel.class, NioServerSocketChannel.class,
                threads -> new NioEventLoopGroup(threads, EVENT_LOOP_FACTORY), true);

        private final Class<? extends DatagramChannel> datagramChannel;
        private final Class<? extends SocketChannel> socketChannel;
        private final Class<? extends ServerSocketChannel> serverSocketChannel;
        private final Function<Integer, EventLoopGroup> eventLoopGroupFactory;
        private final boolean available;
    }
}
