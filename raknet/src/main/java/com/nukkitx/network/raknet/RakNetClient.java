package com.nukkitx.network.raknet;

import com.nukkitx.network.*;
import com.nukkitx.network.handler.ExceptionHandler;
import com.nukkitx.network.raknet.codec.DatagramRakNetDatagramCodec;
import com.nukkitx.network.raknet.codec.DatagramRakNetPacketCodec;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.handler.RakNetDatagramClientHandler;
import com.nukkitx.network.raknet.handler.RakNetPacketClientHandler;
import com.nukkitx.network.raknet.packet.OpenConnectionRequest1Packet;
import com.nukkitx.network.raknet.packet.UnconnectedPingPacket;
import com.nukkitx.network.raknet.session.RakNetConnectingSession;
import com.nukkitx.network.raknet.session.RakNetPingSession;
import com.nukkitx.network.raknet.session.RakNetSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;

public class RakNetClient<T extends NetworkSession<RakNetSession>> extends RakNet<T> implements NetworkClient<T, RakNetSession> {
    private final ConcurrentMap<InetSocketAddress, RakNetConnectingSession<T>> connectingSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetSocketAddress, RakNetPingSession> pingSessions = new ConcurrentHashMap<>();

    private RakNetClient(SessionManager<T> sessionManager, RakNetPacketRegistry<T> packetRegistry,
                         SessionFactory<T, RakNetSession> sessionFactory, long id,
                         Map<ChannelOption, Object> channelOptions, ScheduledExecutorService scheduler, Executor executor) {
        super(sessionManager, packetRegistry, sessionFactory, id, channelOptions, scheduler, executor);
    }

    public static <T extends NetworkSession<RakNetSession>> Builder<T> builder() {
        return new Builder<>();
    }

    @Override
    protected void initPipeline(ChannelPipeline pipeline) throws Exception {
        pipeline.addLast("datagramRakNetPacketCodec", new DatagramRakNetPacketCodec(getPacketRegistry()))
                .addLast("raknetPacketHandler", new RakNetPacketClientHandler<>(this))
                .addLast("datagramRakNetDatagramCodec", new DatagramRakNetDatagramCodec(this))
                .addLast("raknetDatagramHandler", new RakNetDatagramClientHandler<>(this))
                .addLast("exceptionHandler", new ExceptionHandler());
    }

    @Override
    public CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress) {
        return connect(remoteAddress, null);
    }

    @Override
    public CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress, @Nullable InetSocketAddress localAddress) {
        Preconditions.checkNotNull(remoteAddress, "remoteAddress");

        final CompletableFuture<T> future = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                ChannelFuture channelFuture;
                if (localAddress != null) {
                    channelFuture = getBootstrap().connect(remoteAddress, localAddress);
                } else {
                    channelFuture = getBootstrap().connect(remoteAddress);
                }
                if (!channelFuture.awaitUninterruptibly().isSuccess()) {
                    future.completeExceptionally(channelFuture.cause());
                    return;
                }
                InetSocketAddress localInetAddress = (InetSocketAddress) channelFuture.channel().localAddress();
                RakNetConnectingSession<T> session = new RakNetConnectingSession<>(localInetAddress, remoteAddress, channelFuture.channel(),
                        this, future);
                connectingSessions.put(localInetAddress, session);

                OpenConnectionRequest1Packet connectionRequest = new OpenConnectionRequest1Packet();
                connectionRequest.setMtu(session.getMtu() - 28);
                connectionRequest.setProtocolVersion(RakNetUtil.RAKNET_PROTOCOL_VERSION);
                session.getChannel().writeAndFlush(new DirectAddressedRakNetPacket(connectionRequest, remoteAddress));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<RakNetPong> ping(@Nonnull InetSocketAddress remoteAddress) {
        return ping(remoteAddress, null);
    }

    public CompletableFuture<RakNetPong> ping(@Nonnull InetSocketAddress remoteAddress, @Nullable InetSocketAddress localAddress) {
        Preconditions.checkNotNull(remoteAddress, "remoteAddress");

        final CompletableFuture<RakNetPong> future = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                ChannelFuture channelFuture;
                InetSocketAddress localInetAddress;
                if (localAddress != null) {
                    channelFuture = getBootstrap().connect(remoteAddress, localAddress).awaitUninterruptibly();
                    localInetAddress = localAddress;
                } else {
                    channelFuture = getBootstrap().connect(remoteAddress).awaitUninterruptibly();
                    localInetAddress = (InetSocketAddress) channelFuture.channel().localAddress();
                }
                RakNetPingSession session = new RakNetPingSession(localInetAddress, remoteAddress, channelFuture.channel(),
                        future, System.currentTimeMillis());
                pingSessions.put(localInetAddress, session);

                UnconnectedPingPacket unconnectedPing = new UnconnectedPingPacket();
                unconnectedPing.setTimestamp(session.getPing());
                unconnectedPing.setClientId(getId());
                session.getChannel().writeAndFlush(new DirectAddressedRakNetPacket(unconnectedPing, remoteAddress));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    @Override
    public void close() {
        getSessionManager().all().forEach(NetworkSession::disconnect);
        if (getChannel() != null) {
            getChannel().close().awaitUninterruptibly();
        }
    }

    public RakNetConnectingSession<T> getConnectingSession(InetSocketAddress localAddress) {
        Preconditions.checkNotNull(localAddress, "localAddress");
        return connectingSessions.get(localAddress);
    }

    public void removeConnectingSession(RakNetConnectingSession<T> session) {
        Preconditions.checkNotNull(session, "session");
        connectingSessions.values().remove(session);
    }

    public RakNetPingSession getPingSession(InetSocketAddress localAddress) {
        return pingSessions.get(localAddress);
    }

    @Override
    public T getSession(AddressedEnvelope<?, InetSocketAddress> packet) {
        return getSessionManager().get(packet.recipient());
    }

    public static class Builder<T extends NetworkSession<RakNetSession>> extends RakNet.Builder<T> {
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

        @Override
        public RakNetClient<T> build() {
            RakNetPacketRegistry<T> registry = checkCommonComponents();
            return new RakNetClient<>(sessionManager, registry, sessionFactory, id, channelOptions, scheduler, executor);
        }
    }
}
