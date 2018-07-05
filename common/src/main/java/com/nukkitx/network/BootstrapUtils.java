package com.nukkitx.network;

import com.nukkitx.network.util.NetworkThreadFactory;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.*;
import io.netty.channel.epoll.Native;
import io.netty.channel.kqueue.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.UnixChannelOption;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.concurrent.ThreadFactory;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@UtilityClass
public final class BootstrapUtils {
    private static final Optional<int[]> KERNEL_VERSION;
    private static final int[] REUSEPORT_VERSION = new int[]{3, 9, 0};
    private static final boolean REUSEPORT_AVAILABLE;
    private static final ChannelType CHANNEL_TYPE;
    private static final EventLoopGroup EVENT_LOOP_GROUP;

    static {
        String kernelVersion;
        try {
            kernelVersion = Native.KERNEL_VERSION;
        } catch (Throwable e) {
            kernelVersion = null;
        }
        if (kernelVersion != null && kernelVersion.contains("-")) {
            int index = kernelVersion.indexOf('-');
            if (index > -1) {
                kernelVersion = kernelVersion.substring(0, index);
            }
            int[] kernalVer = fromString(kernelVersion);
            KERNEL_VERSION = Optional.of(kernalVer);
            REUSEPORT_AVAILABLE = checkVersion(kernalVer, 0);
        } else {
            KERNEL_VERSION = Optional.empty();
            REUSEPORT_AVAILABLE = false;
        }

        ThreadFactory listenerThreadFactory = NetworkThreadFactory.builder().format("Network Listener - #%d")
                .daemon(true).build();

        if (Epoll.isAvailable()) {
            CHANNEL_TYPE = ChannelType.EPOLL;
            EVENT_LOOP_GROUP = new EpollEventLoopGroup(0, listenerThreadFactory);
        } else if (KQueue.isAvailable()) {
            CHANNEL_TYPE = ChannelType.KQUEUE;
            EVENT_LOOP_GROUP = new KQueueEventLoopGroup(0, listenerThreadFactory);
        } else {
            CHANNEL_TYPE = ChannelType.NIO;
            EVENT_LOOP_GROUP = new NioEventLoopGroup(0, listenerThreadFactory);
        }
    }

    public static Optional<int[]> getKernelVersion() {
        return KERNEL_VERSION;
    }

    public static boolean isReusePortAvailable() {
        return REUSEPORT_AVAILABLE;
    }

    public static EventLoopGroup getEventLoopGroup() {
        return EVENT_LOOP_GROUP;
    }

    public static void setupBootstrap(Bootstrap bootstrap, boolean datagram) {
        Class<? extends Channel> channel = datagram ? CHANNEL_TYPE.datagramChannel : CHANNEL_TYPE.socketChannel;
        bootstrap.channel(channel);

        setupAbstractBootstrap(bootstrap);
    }

    public static void setupServerBootstrap(ServerBootstrap bootstrap) {
        Class<? extends ServerSocketChannel> channel = CHANNEL_TYPE.serverSocketChannel;
        bootstrap.channel(channel);

        setupAbstractBootstrap(bootstrap);
    }

    private static void setupAbstractBootstrap(AbstractBootstrap bootstrap) {
        bootstrap.group(EVENT_LOOP_GROUP);

        if (REUSEPORT_AVAILABLE) {
            bootstrap.option(UnixChannelOption.SO_REUSEPORT, true);
        }
    }

    private static int[] fromString(String ver) {
        String[] parts = ver.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("At least 2 version numbers required");
        }

        return new int[]{
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                parts.length == 2 ? 0 : Integer.parseInt(parts[2])
        };
    }

    private static boolean checkVersion(int[] ver, int i) {
        if (ver[i] > REUSEPORT_VERSION[i]) {
            return true;
        } else if (ver[i] == REUSEPORT_VERSION[i]) {
            if (ver.length == (i + 1)) {
                return true;
            } else {
                return checkVersion(ver, i + 1);
            }
        }
        return false;
    }

    private enum ChannelType {
        EPOLL(EpollDatagramChannel.class, EpollSocketChannel.class, EpollServerSocketChannel.class),
        KQUEUE(KQueueDatagramChannel.class, KQueueSocketChannel.class, KQueueServerSocketChannel.class),
        NIO(NioDatagramChannel.class, NioSocketChannel.class, NioServerSocketChannel.class);

        private final Class<? extends DatagramChannel> datagramChannel;
        private final Class<? extends SocketChannel> socketChannel;
        private final Class<? extends ServerSocketChannel> serverSocketChannel;

        ChannelType(Class<? extends DatagramChannel> datagramChannel, Class<? extends SocketChannel> socketChannel,
                    Class<? extends ServerSocketChannel> serverSocketChannel) {
            this.datagramChannel = datagramChannel;
            this.socketChannel = socketChannel;
            this.serverSocketChannel = serverSocketChannel;
        }
    }
}
