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
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

public class RakNetServer<T extends NetworkSession<RakNetSession>> extends RakNet<T> implements NetworkListener {
    private final InetSocketAddress address;
    private final int maxThreads;
    private final RakNetServerEventListener eventListener;

    private RakNetServer(SessionManager<T> sessionManager, RakNetPacketRegistry<T> registry,
                         SessionFactory<T, RakNetSession> factory, InetSocketAddress address, long id,
                         RakNetServerEventListener eventListener, int maxThreads,
                         Map<ChannelOption, Object> channelOptions, ScheduledExecutorService scheduler, Executor executor) {
        super(sessionManager, registry, factory, id, channelOptions, scheduler, executor);
        this.address = address;
        this.eventListener = eventListener;
        this.maxThreads = maxThreads;
    }

    public static <T extends NetworkSession<RakNetSession>> Builder<T> builder() {
        return new Builder<>();
    }

    public void createSession(RakNetSession connection) {
        T session = getSessionFactory().createSession(connection);
        getSessionManager().add(connection.getRemoteAddress()
                .orElseThrow(() -> new IllegalStateException("Connection has no remote address")), session);
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
        pipeline.addLast("raknetPacketCodec", new DatagramRakNetPacketCodec(getPacketRegistry()))
                .addLast("raknetPacketHandler", new RakNetPacketServerHandler<>(this))
                .addLast("raknetDatagramCodec", new DatagramRakNetDatagramCodec(this))
                .addLast("raknetDatagramHandler", new RakNetDatagramServerHandler<>(this))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    public static class Builder<T extends NetworkSession<RakNetSession>> extends RakNet.Builder<T> {
        private SessionManager<T> sessionManager;
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
            this.address = new InetSocketAddress(host, port);
            return this;
        }

        public Builder<T> sessionFactory(SessionFactory<T, RakNetSession> sessionFactory) {
            setSessionFactory(sessionFactory);
            return this;
        }

        public Builder<T> sessionManager(SessionManager<T> sessionManager) {
            this.sessionManager = Preconditions.checkNotNull(sessionManager, "sessionManager");
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

        public <O> Builder<T> channelOption(ChannelOption<O> option, O value) {
            addChannelOption(option, value);
            return this;
        }

        public Builder<T> scheduler(ScheduledExecutorService scheduler) {
            setScheduler(scheduler);
            return this;
        }

        public Builder<T> executor(Executor executor) {
            setExecutor(executor);
            return this;
        }

        public RakNetServer<T> build() {
            Preconditions.checkNotNull(address, "address");
            Preconditions.checkNotNull(eventListener, "eventListener");
            Preconditions.checkNotNull(sessionManager, "sessionManager");
            RakNetPacketRegistry<T> registry = checkCommonComponents();
            return new RakNetServer<>(sessionManager, registry, sessionFactory, address, id, eventListener,
                    maximumThreads, channelOptions, scheduler, executor);
        }
    }
}
