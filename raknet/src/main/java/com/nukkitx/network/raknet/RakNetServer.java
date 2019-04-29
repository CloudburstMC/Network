package com.nukkitx.network.raknet;

import com.nukkitx.network.BootstrapUtils;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import lombok.Cleanup;

import javax.annotation.Nonnegative;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;

@SuppressWarnings("ResultOfMethodCallIgnored")
@ParametersAreNonnullByDefault
public class RakNetServer extends RakNet {
    private final ConcurrentMap<InetSocketAddress, RakNetServerSession> sessionsByAddress = new ConcurrentHashMap<>();
    final ConcurrentMap<Long, RakNetServerSession> sessionsByGuid = new ConcurrentHashMap<>();
    private final ServerDatagramHandler datagramHandler = new ServerDatagramHandler();
    private final Set<InetAddress> blockAddresses = new HashSet<>();
    private final Set<Channel> channels = new HashSet<>();
    private RakNetServerListener listener = null;
    private final int maxThreads;
    private int maxConnections = 1024;

    public RakNetServer(InetSocketAddress bindAddress) {
        this(bindAddress, 1);
    }

    public RakNetServer(InetSocketAddress bindAddress, int maxThreads) {
        this(bindAddress, maxThreads, Executors.newSingleThreadScheduledExecutor());
    }

    public RakNetServer(InetSocketAddress bindAddress, int maxThreads, ScheduledExecutorService scheduler) {
        this(bindAddress, maxThreads, scheduler, scheduler);
    }

    public RakNetServer(InetSocketAddress bindAddress, int maxThreads, ScheduledExecutorService scheduler, Executor executor) {
        super(bindAddress, scheduler, executor);
        this.maxThreads = maxThreads;
    }

    @Override
    protected CompletableFuture<Void> bindInternal() {
        int threads = BootstrapUtils.isReusePortAvailable() ? this.maxThreads : 1;

        ChannelFuture[] channelFutures = new ChannelFuture[threads];

        for (int i = 0; i < threads; i++) {
            channelFutures[i] = this.bootstrap.handler(datagramHandler).bind(this.bindAddress);
        }

        return BootstrapUtils.allOf(channelFutures);
    }

    public boolean block(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return this.blockAddresses.add(address);
    }

    public boolean unblock(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return this.blockAddresses.remove(address);
    }

    public int getSessionCount() {
        return this.sessionsByAddress.size();
    }

    @Nonnegative
    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(@Nonnegative int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void close() {
        super.close();
        for (RakNetServerSession session : this.sessionsByAddress.values()) {
            session.disconnect(DisconnectReason.SHUTTING_DOWN);
        }
        for (Channel channel : this.channels) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    protected void onTick() {
        for (RakNetServerSession session : this.sessionsByAddress.values()) {
            this.executor.execute(session::onTick);
        }
    }

    private void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet) {
        // We want to do as many checks as possible before creating a session so memory is not wasted.
        ByteBuf buffer = packet.content();
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return;
        }
        int protocolVersion = buffer.readUnsignedByte();
        int mtu = RakNetUtils.clamp(buffer.readableBytes() + 18, RakNetConstants.MINIMUM_MTU_SIZE,
                RakNetConstants.MAXIMUM_MTU_SIZE);

        RakNetServerSession session = this.sessionsByAddress.get(packet.sender());

        if (session != null) {
            this.sendAlreadyConnected(ctx, packet.sender());
        } else if (this.protocolVersion >= 0 && this.protocolVersion != protocolVersion) {
            this.sendIncompatibleProtocolVersion(ctx, packet.sender());
        } else if (this.maxConnections <= getSessionCount()) {
            this.sendNoFreeIncomingConnections(ctx, packet.sender());
        } else if (this.listener != null && !this.listener.onConnectionRequest(packet.sender())) {
            this.sendConnectionBanned(ctx, packet.sender());
        } else {
            // Passed all checks. Now create the session and send the first reply.
            session = new RakNetServerSession(packet.sender(), ctx.channel(), this, mtu);
            if (this.sessionsByAddress.putIfAbsent(packet.sender(), session) == null) {
                session.sendOpenConnectionReply1();
                if (listener != null) {
                    listener.onSessionCreation(packet.sender(), session);
                }
            }
        }
    }

    private void onUnconnectedPing(ChannelHandlerContext ctx, DatagramPacket packet) {
        long pingTime = packet.content().readLong();

        byte[] userData = null;

        if (this.listener != null) {
            userData = this.listener.onQuery(packet.sender());
        }
        if (userData == null) {
            userData = new byte[0];
        }

        int packetLength = 35 + userData.length;

        ByteBuf buffer = ctx.alloc().directBuffer(packetLength, packetLength);

        buffer.writeByte(RakNetConstants.ID_UNCONNECTED_PONG);
        buffer.writeLong(pingTime);
        buffer.writeLong(this.guid);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeShort(userData.length);
        buffer.writeBytes(userData);

        RakNet.send(ctx, packet.sender(), buffer);
    }

    /*
    Packet Dispatchers
     */

    private void sendAlreadyConnected(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().directBuffer(25, 25);
        buffer.writeByte(RakNetConstants.ID_ALREADY_CONNECTED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);

        RakNet.send(ctx, recipient, buffer);
    }

    private void sendConnectionBanned(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().directBuffer(25, 25);
        buffer.writeByte(RakNetConstants.ID_CONNECTION_BANNED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);

        RakNet.send(ctx, recipient, buffer);
    }

    private void sendIncompatibleProtocolVersion(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().directBuffer(26, 26);
        buffer.writeByte(RakNetConstants.ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(this.protocolVersion);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);

        RakNet.send(ctx, recipient, buffer);
    }

    private void sendNoFreeIncomingConnections(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().directBuffer(25, 25);
        buffer.writeByte(RakNetConstants.ID_NO_FREE_INCOMING_CONNECTIONS);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);

        RakNet.send(ctx, recipient, buffer);
    }

    @ChannelHandler.Sharable
    private class ServerDatagramHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) {
                return;
            }

            @Cleanup("release") DatagramPacket packet = (DatagramPacket) msg;

            if (blockAddresses.contains(packet.sender().getAddress())) {
                // Ignore these addresses altogether.
                return;
            }

            ByteBuf content = packet.content();
            byte packetId = content.readByte();

            // These packets don't require a session
            switch (packetId) {
                case RakNetConstants.ID_UNCONNECTED_PING:
                    RakNetServer.this.onUnconnectedPing(ctx, packet);
                    return;
                case RakNetConstants.ID_OPEN_CONNECTION_REQUEST_1:
                    RakNetServer.this.onOpenConnectionRequest1(ctx, packet);
                    return;
            }
            content.readerIndex(0);

            RakNetServerSession session = RakNetServer.this.sessionsByAddress.get(packet.sender());

            if (session != null) {
                session.onDatagram(packet);
            }
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            if (ctx.channel().isRegistered()) {
                RakNetServer.this.channels.add(ctx.channel());
            }
        }
    }
}
