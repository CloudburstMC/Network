package com.nukkitx.network.synapse;

import com.nukkitx.network.BootstrapUtils;
import com.nukkitx.network.NetworkListener;
import com.nukkitx.network.SessionFactory;
import com.nukkitx.network.SessionManager;
import com.nukkitx.network.handler.ExceptionHandler;
import com.nukkitx.network.synapse.codec.ByteToSynapsePacketCodec;
import com.nukkitx.network.synapse.session.SynapseSession;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;

public class SynapseServer extends Synapse implements NetworkListener {
    private final InetSocketAddress address;
    private final int maxThreads;
    private final SecretKey key;

    public SynapseServer(SessionManager<SynapseSession> sessionManager, SynapsePacketCodec packetCodec,
                         SessionFactory<SynapseSession, SynapseSession> sessionFactory, InetSocketAddress address,
                         int maxThreads, SecretKey key) {
        super(sessionManager, packetCodec, sessionFactory);
        this.address = address;
        this.maxThreads = maxThreads;
        this.key = key;
    }

    @Override
    public boolean bind() {
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
    protected void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("synapsePacketCodec", new ByteToSynapsePacketCodec(getPacketCodec()))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    SecretKey getKey() {
        return key;
    }
}
