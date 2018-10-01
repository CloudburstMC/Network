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

import java.util.concurrent.ThreadLocalRandom;

@Getter
public abstract class RakNet<T extends NetworkSession<RakNetSession>> extends ChannelInitializer<DatagramChannel> {
    private final SessionManager<T> sessionManager;
    private final RakNetPacketRegistry<T> packetRegistry;
    private final SessionFactory<T, RakNetSession> sessionFactory;
    private final Bootstrap bootstrap;
    private final long id;
    private DatagramChannel channel;

    protected RakNet(SessionManager<T> sessionManager, RakNetPacketRegistry<T> packetRegistry,
                     SessionFactory<T, RakNetSession> sessionFactory, long id) {
        this.sessionManager = sessionManager;
        this.packetRegistry = packetRegistry;
        this.sessionFactory = sessionFactory;
        this.id = id;

        this.bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        BootstrapUtils.setupBootstrap(bootstrap, true);
    }

    protected Bootstrap getBootstrap() {
        return bootstrap;
    }

    protected final void initChannel(DatagramChannel channel) throws Exception {
        this.channel = channel;

        initPipeline(channel.pipeline());
    }

    protected abstract void initPipeline(ChannelPipeline pipeline) throws Exception;

    protected abstract static class Builder<T extends NetworkSession<RakNetSession>> {
        final TIntObjectMap<PacketFactory<CustomRakNetPacket<T>>> packets = new TIntObjectHashMap<>();
        SessionFactory<T, RakNetSession> sessionFactory;
        SessionManager<T> sessionManager;
        long id;

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

        RakNetPacketRegistry<T> checkAndGetRegistry() {
            Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            Preconditions.checkNotNull(sessionManager, "sessionManager");
            if (id == 0) {
                id = ThreadLocalRandom.current().nextLong();
            }
            return new RakNetPacketRegistry<>(packets);
        }

        public abstract RakNet<T> build();
    }
}
