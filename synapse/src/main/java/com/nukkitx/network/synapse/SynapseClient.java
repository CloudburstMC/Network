package com.nukkitx.network.synapse;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.SessionFactory;
import com.nukkitx.network.SessionManager;
import com.nukkitx.network.handler.ExceptionHandler;
import com.nukkitx.network.synapse.codec.ByteToSynapsePacketCodec;
import com.nukkitx.network.synapse.handler.SynapseClientHandler;
import com.nukkitx.network.synapse.packet.ConnectPacket;
import com.nukkitx.network.synapse.session.ConnectingSynapseSession;
import com.nukkitx.network.synapse.session.SynapseSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import net.minidev.json.JSONObject;

import javax.annotation.Nonnull;
import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

public class SynapseClient extends Synapse {
    private final Map<InetSocketAddress, ConnectingSynapseSession> connectingSessions = new ConcurrentHashMap<>();

    protected SynapseClient(SessionManager<SynapseSession> sessionManager, SynapsePacketCodec packetCodec, SessionFactory<SynapseSession, SynapseSession> sessionFactory) {
        super(sessionManager, packetCodec, sessionFactory);
    }

    @Nonnull
    public CompletableFuture<SynapseSession> connect(@Nonnull InetSocketAddress remoteAddress, String password, JSONObject loginData) {
        Preconditions.checkNotNull(remoteAddress, "remoteAddress");
        Preconditions.checkNotNull(password, "password");
        Preconditions.checkNotNull(loginData, "loginData");

        CompletableFuture<SynapseSession> sessionFuture = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                // Attempt connection
                ChannelFuture future = getBootstrap().connect(remoteAddress).awaitUninterruptibly();

                // Check success
                if (!future.isSuccess()) {
                    sessionFuture.completeExceptionally(future.cause());
                    return;
                }

                Channel channel = future.channel();
                InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
                connectingSessions.put(localAddress,
                        new ConnectingSynapseSession(channel, sessionFuture, remoteAddress, loginData));

                // Generate login details and send packet
                SecretKey key = SynapseUtils.generateSecretKey(password);
                String jwe = SynapseUtils.encryptConnect(key, loginData);

                ConnectPacket connect = new ConnectPacket();
                connect.setProtocolVersion(SynapsePacketCodec.SYNAPSE_PROTOCOL_VERSION);
                connect.setJwe(jwe);
                channel.writeAndFlush(connect);
            } catch (Exception e) {
                sessionFuture.completeExceptionally(e);
            }
        });

        return sessionFuture;
    }

    public void close() {
        getSessionManager().all().forEach(NetworkSession::close);
    }

    @Override
    protected void initPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("synapsePacketCodec", new ByteToSynapsePacketCodec(getPacketCodec()))
                .addLast("synapseClientHandler", new SynapseClientHandler(getSessionManager(), connectingSessions))
                .addLast("exceptionHandler", new ExceptionHandler());
    }
}
