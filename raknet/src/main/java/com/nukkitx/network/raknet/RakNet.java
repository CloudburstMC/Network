package com.nukkitx.network.raknet;

import com.nukkitx.network.*;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.Preconditions;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

@Getter
public abstract class RakNet<T extends NetworkSession<RakNetSession>> extends ChannelInitializer<DatagramChannel> {
    private final SessionManager<T> sessionManager;
    private final RakNetPacketRegistry<T> packetRegistry;
    private final SessionFactory<T, RakNetSession> sessionFactory;
    private final Bootstrap bootstrap;
    private final long id;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private DatagramChannel channel;

    protected RakNet(SessionManager<T> sessionManager, RakNetPacketRegistry<T> packetRegistry, SessionFactory<T, RakNetSession> sessionFactory, long id,
                     Map<ChannelOption, Object> channelOptions, ScheduledExecutorService scheduler, Executor executor) {
        this.sessionManager = sessionManager;
        this.packetRegistry = packetRegistry;
        this.sessionFactory = sessionFactory;
        this.id = id;
        this.scheduler = scheduler;
        this.executor = executor;

        this.bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        BootstrapUtils.setupBootstrap(bootstrap, true);

        channelOptions.forEach(bootstrap::option);

        scheduler.scheduleAtFixedRate(this::onTick, 50, 50, TimeUnit.MILLISECONDS);
    }

    Bootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    protected final void initChannel(DatagramChannel channel) throws Exception {
        this.channel = channel;

        initPipeline(channel.pipeline());
    }

    protected abstract void initPipeline(ChannelPipeline pipeline) throws Exception;

    private void onTick() {
        for (T session : sessionManager.all()) {
            executor.execute(() -> session.getConnection().onTick());
        }
    }

    protected abstract static class Builder<T extends NetworkSession<RakNetSession>> {
        final TIntObjectMap<PacketFactory<CustomRakNetPacket<T>>> packets = new TIntObjectHashMap<>();
        final Map<ChannelOption, Object> channelOptions = new HashMap<>();
        SessionFactory<T, RakNetSession> sessionFactory;
        SessionManager<T> sessionManager;
        long id;
        ScheduledExecutorService scheduler;
        Executor executor;

        void setSessionFactory(SessionFactory<T, RakNetSession> sessionFactory) {
            this.sessionFactory = Preconditions.checkNotNull(sessionFactory, "sessionFactory");
        }

        void setSessionManager(SessionManager<T> sessionManager) {
            this.sessionManager = Preconditions.checkNotNull(sessionManager, "sessionManager");
        }

        void addPacket(PacketFactory<CustomRakNetPacket<T>> factory, int id) {
            Preconditions.checkNotNull(factory, "factory");
            Preconditions.checkArgument(id >= 0 && id < 256, "Invalid ID");
            packets.put(id, factory);
        }

        void setId(long id) {
            this.id = id;
        }

        <O> void addChannelOption(ChannelOption<O> option, O value) {
            Preconditions.checkNotNull(option, "option");
            Preconditions.checkNotNull(value, "value");
            channelOptions.put(option, value);
        }

        void setScheduler(ScheduledExecutorService scheduler) {
            Preconditions.checkNotNull(scheduler, "scheduler");
            this.scheduler = scheduler;
        }

        void setExecutor(Executor executor) {
            Preconditions.checkNotNull(executor, "executor");
            this.executor = executor;
        }

        RakNetPacketRegistry<T> checkCommonComponents() {
            Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            if (scheduler == null) {
                scheduler = Executors.newSingleThreadScheduledExecutor();
            }
            if (executor == null) {
                executor = scheduler;
            }
            if (sessionManager == null) {
                sessionManager = new SessionManager<>(executor);
            }
            if (id == 0) {
                id = ThreadLocalRandom.current().nextLong();
            }
            return new RakNetPacketRegistry<>(packets);
        }

        public abstract RakNet<T> build();
    }
}
