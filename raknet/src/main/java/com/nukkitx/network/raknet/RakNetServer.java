package com.nukkitx.network.raknet;

import com.nukkitx.network.*;
import com.nukkitx.network.handler.ExceptionHandler;
import com.nukkitx.network.raknet.codec.DatagramRakNetDatagramCodec;
import com.nukkitx.network.raknet.codec.DatagramRakNetPacketCodec;
import com.nukkitx.network.raknet.handler.RakNetDatagramServerHandler;
import com.nukkitx.network.raknet.handler.RakNetPacketServerHandler;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class RakNetServer<T extends NetworkSession<RakNetSession>> extends RakNet<T> implements NetworkListener {
    private final AtomicInteger maximumPlayers = new AtomicInteger(20);
    private final InetSocketAddress address;
    private final int maxThreads;
    private final RakNetServerEventListener eventListener;

    public RakNetServer(SessionManager<T> sessionManager, RakNetPacketRegistry<T> registry,
                        SessionFactory<T, RakNetSession> factory, InetSocketAddress address, long id,
                        RakNetServerEventListener eventListener, int maxThreads) {
        super(sessionManager, registry, factory, id);
        this.address = address;
        this.eventListener = eventListener;
        this.maxThreads = maxThreads;
    }

    public static <T extends NetworkSession<RakNetSession>> Builder<T> builder() {
        return new Builder<>();
    }

    public int getMaximumPlayers() {
        return maximumPlayers.get();
    }

    public void createSession(RakNetSession connection) {
        T session = getSessionFactory().createSession(connection);
        getSessionManager().add(connection.getRemoteAddress().orElseThrow(() -> new IllegalStateException("Connection has no remote address")), session);
    }

    public void setMaximumPlayer(int maximumPlayers) {
        this.maximumPlayers.set(maximumPlayers);
    }

    public RakNetServerEventListener getEventListener() {
        return eventListener;
    }

    @Override
    public boolean bind() {
        Preconditions.checkState(getChannel() == null, "RakNet server already bound");
        int threads = BootstrapUtils.isReusePortAvailable() ? maxThreads : 1;

        boolean success = false;

        for (int i = 0; i < threads; i++) {
            try {
                ChannelFuture future = getBootstrap().bind(address).await();
                if (future.isSuccess()) {
                    success = true;
                }
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        return success;
    }

    @Override
    public void close() {
        if (getChannel() != null) {
            getChannel().close().awaitUninterruptibly();
        }
    }

    @Override
    public InetSocketAddress getAddress() {
        return address;
    }

    @Override
    protected void initPipeline(ChannelPipeline pipeline) throws Exception {
        pipeline.addLast("datagramRakNetPacketCodec", new DatagramRakNetPacketCodec(getPacketRegistry()))
                .addLast("raknetPacketHandler", new RakNetPacketServerHandler(this))
                .addLast("datagramRakNetDatagramCodec", new DatagramRakNetDatagramCodec(this))
                .addLast("raknetDatagramHandler", new RakNetDatagramServerHandler<>(this))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    public static class Builder<T extends NetworkSession<RakNetSession>> extends RakNet.Builder<T> {
        private InetSocketAddress address;
        private RakNetServerEventListener eventListener;
        private int maximumThreads = 1;

        public Builder<T> address(InetSocketAddress address) {
            Preconditions.checkNotNull(address, "address");
            this.address = address;
            return this;
        }

        public Builder<T> address(String host, int port) {
            Preconditions.checkNotNull(host, "host");
            Preconditions.checkArgument(port >= 0 && port <= 65535, "Invalid port");
            this.address = new InetSocketAddress(host, port);
            return this;
        }

        public Builder<T> sessionFactory(SessionFactory<T, RakNetSession> sessionFactory) {
            setSessionFactory(sessionFactory);
            return this;
        }

        public Builder<T> sessionManager(SessionManager<T> sessionManager) {
            setSessionManager(sessionManager);
            return this;
        }

        public Builder<T> packet(PacketFactory<CustomRakNetPacket<T>> factory, int id) {
            addPacket(factory, id);
            return this;
        }

        public Builder<T> id(long id) {
            setId(id);
            return this;
        }

        public Builder<T> eventListener(RakNetServerEventListener eventListener) {
            this.eventListener = Preconditions.checkNotNull(eventListener, "listener");
            return this;
        }

        public Builder<T> maximumThreads(int maximumThreads) {
            Preconditions.checkArgument(maximumThreads > 0, "Maximum threads must be larger than zero");
            this.maximumThreads = maximumThreads;
            return this;
        }

        public RakNetServer<T> build() {
            Preconditions.checkNotNull(address, "address");
            Preconditions.checkNotNull(eventListener, "eventListener");
            RakNetPacketRegistry<T> registry = checkAndGetRegistry();
            return new RakNetServer<>(sessionManager, registry, sessionFactory, address, id, eventListener, maximumThreads);
        }
    }
}
