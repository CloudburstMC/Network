package com.nukkitx.network.synapse;

import com.nukkitx.network.BootstrapUtils;
import com.nukkitx.network.SessionFactory;
import com.nukkitx.network.SessionManager;
import com.nukkitx.network.synapse.session.SynapseSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import lombok.Getter;

@Getter
public abstract class Synapse extends ChannelInitializer<SocketChannel> {
    private final SessionManager<SynapseSession> sessionManager;
    private final SynapsePacketCodec packetCodec;
    private final SessionFactory<SynapseSession, SynapseSession> sessionFactory;
    private final Bootstrap bootstrap;
    private SocketChannel channel;

    public Synapse(SessionManager<SynapseSession> sessionManager, SynapsePacketCodec packetCodec,
                   SessionFactory<SynapseSession, SynapseSession> sessionFactory) {
        this.sessionManager = sessionManager;
        this.packetCodec = packetCodec;
        this.sessionFactory = sessionFactory;

        this.bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT).handler(this);

        BootstrapUtils.setupBootstrap(bootstrap, false);
    }

    protected Bootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    protected final void initChannel(SocketChannel channel) throws Exception {
        this.channel = channel;

        initPipeline(channel.pipeline());
    }

    protected abstract void initPipeline(ChannelPipeline pipeline) throws Exception;

    protected abstract static class Builder {
        protected SessionFactory<SynapseSession, SynapseSession> sessionFactory;
        protected SessionManager<SynapseSession> sessionManager;

        public Builder sessionFactory(SessionFactory<SynapseSession, SynapseSession> sessionFactory) {
            this.sessionFactory = Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            return this;
        }

        public Builder sessionManager(SessionManager<SynapseSession> sessionManager) {
            this.sessionManager = Preconditions.checkNotNull(sessionManager, "sessionManager");
            return this;
        }

        public abstract Synapse build();
    }
}
