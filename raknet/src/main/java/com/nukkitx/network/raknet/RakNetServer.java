package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.pipeline.ProxyServerHandler;
import com.nukkitx.network.raknet.pipeline.RakOutboundHandler;
import com.nukkitx.network.raknet.pipeline.ServerDatagramHandler;
import com.nukkitx.network.raknet.pipeline.ServerMessageHandler;
import com.nukkitx.network.raknet.util.RoundRobinIterator;
import com.nukkitx.network.util.Bootstraps;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.EventLoops;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public class RakNetServer extends RakNet {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetServer.class);

    private final ConcurrentMap<InetAddress, Long> blockAddresses = new ConcurrentHashMap<>();
    final ConcurrentMap<InetSocketAddress, RakNetServerSession> sessionsByAddress = new ConcurrentHashMap<>();
    final ExpiringMap<InetSocketAddress, InetSocketAddress> proxiedAddresses;

    private final InetSocketAddress bindAddress;
    private final int bindThreads;
    private final boolean useProxyProtocol;
    private int maxConnections = 1024;

    private final Set<Channel> channels = new HashSet<>();
    private final Iterator<Channel> channelIterator = new RoundRobinIterator<>(channels);

    private final RakOutboundHandler rakOutboundHandler = new RakOutboundHandler(this);
    private final ServerChannelInitializer initializer = new ServerChannelInitializer();
    private final ServerMessageHandler messageHandler = new ServerMessageHandler(this);
    private final ProxyServerHandler proxyServerHandler;
    private final ServerDatagramHandler serverDatagramHandler = new ServerDatagramHandler(this);

    private volatile RakNetServerListener listener = null;
    private final Map<String, Consumer<Throwable>> exceptionHandlers = new HashMap<>();

    public RakNetServer(InetSocketAddress bindAddress) {
        this(bindAddress, 1);
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads) {
        this(bindAddress, bindThreads, EventLoops.commonGroup());
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads, EventLoopGroup eventLoopGroup) {
        this(bindAddress, bindThreads, eventLoopGroup, false);
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads, EventLoopGroup eventLoopGroup, boolean useProxyProtocol) {
        super(eventLoopGroup);
        this.bindThreads = bindThreads;
        this.bindAddress = bindAddress;
        this.useProxyProtocol = useProxyProtocol;
        this.proxiedAddresses = ExpiringMap.builder().expiration(30 + 1, TimeUnit.MINUTES).expirationPolicy(ExpirationPolicy.ACCESSED).build();
        this.proxyServerHandler = useProxyProtocol ? new ProxyServerHandler(this) : null;
    }

    @Override
    protected CompletableFuture<Void> bindInternal() {
        int bindThreads = Bootstraps.isReusePortAvailable() ? this.bindThreads : 1;
        ChannelFuture[] channelFutures = new ChannelFuture[bindThreads];

        for (int i = 0; i < bindThreads; i++) {
            channelFutures[i] = this.bootstrap.handler(this.initializer).bind(this.bindAddress);
        }
        return Bootstraps.allOf(channelFutures);
    }

    public void send(InetSocketAddress address, ByteBuf buffer) {
        this.channelIterator.next().writeAndFlush(new DatagramPacket(buffer, address));
    }

    @Override
    public void close(boolean force) {
        super.close(force);
        for (RakNetServerSession session : this.sessionsByAddress.values()) {
            session.disconnect(DisconnectReason.SHUTTING_DOWN);
        }
        for (Channel channel : this.channels) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    protected void onTick() {
        final long curTime = System.currentTimeMillis();
        for (RakNetServerSession session : this.sessionsByAddress.values()) {
            session.eventLoop.execute(() -> session.onTick(curTime));
        }
        Iterator<Long> blockedAddresses = this.blockAddresses.values().iterator();
        long timeout;
        while (blockedAddresses.hasNext()) {
            timeout = blockedAddresses.next();
            if (timeout > 0 && timeout < curTime) {
                blockedAddresses.remove();
            }
        }
    }

    public void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet) {
        if (!packet.content().isReadable(16)) {
            return;
        }
        // We want to do as many checks as possible before creating a session so memory is not wasted.
        ByteBuf buffer = packet.content();
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return;
        }
        int protocolVersion = buffer.readUnsignedByte();
        int mtu = buffer.readableBytes() + 1 + 16 + 1 + (packet.sender().getAddress() instanceof Inet6Address ? 40 : 20)
                + UDP_HEADER_SIZE; // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header)

        RakNetServerSession session = this.sessionsByAddress.get(packet.sender());
        final InetSocketAddress clientAddress;
        final InetSocketAddress proxiedAddress;
        if (useProxyProtocol && (proxiedAddress = this.proxiedAddresses.get(packet.sender())) != null) {
            clientAddress = proxiedAddress;
        } else {
            clientAddress = packet.sender();
        }

        if (session != null && session.getState() == RakNetState.CONNECTED) {
            this.sendAlreadyConnected(ctx, packet.sender());
        } else if (this.protocolVersion >= 0 && this.protocolVersion != protocolVersion) {
            this.sendIncompatibleProtocolVersion(ctx, packet.sender());
        } else if (this.maxConnections >= 0 && this.maxConnections <= getSessionCount()) {
            this.sendNoFreeIncomingConnections(ctx, packet.sender());
        } else if (this.listener != null && !this.listener.onConnectionRequest(packet.sender(), clientAddress)) {
            this.sendConnectionBanned(ctx, packet.sender());
        } else if (session == null) {
            // Passed all checks. Now create the session and send the first reply.
            session = new RakNetServerSession(this, packet.sender(), ctx.channel(),
                    ctx.channel().eventLoop().next(), mtu, protocolVersion);
            if (this.sessionsByAddress.putIfAbsent(packet.sender(), session) == null) {
                session.setState(RakNetState.INITIALIZING);
                session.proxiedAddress = this.proxiedAddresses.get(packet.sender());
                session.sendOpenConnectionReply1();
                if (listener != null) {
                    listener.onSessionCreation(session);
                }
            }
        } else {
            session.sendOpenConnectionReply1(); // probably a packet loss occurred, send the reply again
        }
    }

    public void block(InetAddress address) {
        Objects.requireNonNull(address, "address");
        this.blockAddresses.put(address, -1L);
    }

    public void block(InetAddress address, long timeout, TimeUnit timeUnit) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(address, "timeUnit");
        this.blockAddresses.put(address, System.currentTimeMillis() + timeUnit.toMillis(timeout));
    }

    public boolean unblock(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return this.blockAddresses.remove(address) != null;
    }

    public boolean isBlocked(InetAddress address) {
        return this.blockAddresses.containsKey(address);
    }

    public void addProxiedAddress(InetSocketAddress address, InetSocketAddress presentAddress) {
        this.proxiedAddresses.put(address, presentAddress);
    }

    public InetSocketAddress getProxiedAddress(InetSocketAddress address) {
        return this.proxiedAddresses.get(address);
    }

    public int getProxiedAddressSize() {
        return this.proxiedAddresses.size();
    }

    public int getSessionCount() {
        return this.sessionsByAddress.size();
    }

    @Nullable
    public RakNetServerSession getSession(InetSocketAddress address) {
        return this.sessionsByAddress.get(address);
    }

    @Nonnegative
    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(@Nonnegative int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public InetSocketAddress getBindAddress() {
        return this.bindAddress;
    }

    public RakNetServerListener getListener() {
        return listener;
    }

    public void setListener(RakNetServerListener listener) {
        this.listener = listener;
    }

    public boolean useProxyProtocol() {
        return this.useProxyProtocol;
    }

    public void addExceptionHandler(String handlerId, Consumer<Throwable> handler) {
        Objects.requireNonNull(handlerId, "handlerId is null (server)");
        Objects.requireNonNull(handler, "exceptionHandler");
        this.exceptionHandlers.put(handlerId, handler);
    }

    public void removeExceptionHandler(String handlerId) {
        this.exceptionHandlers.remove(handlerId);
    }

    public void clearExceptionHandlers() {
        this.exceptionHandlers.clear();
    }

    public Collection<Consumer<Throwable>> getExceptionHandlers() {
        return this.exceptionHandlers.values();
    }

    /*
     * Packet Dispatchers
     */

    private void sendAlreadyConnected(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_ALREADY_CONNECTED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendConnectionBanned(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_CONNECTION_BANNED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendIncompatibleProtocolVersion(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(this.protocolVersion);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendNoFreeIncomingConnections(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_NO_FREE_INCOMING_CONNECTIONS);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    @ChannelHandler.Sharable
    private class ServerChannelInitializer extends ChannelInitializer<Channel> {

        @Override
        protected void initChannel(Channel channel) throws Exception {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(RakOutboundHandler.NAME, RakNetServer.this.rakOutboundHandler);
            pipeline.addLast(ServerMessageHandler.NAME, RakNetServer.this.messageHandler);
            if (RakNetServer.this.useProxyProtocol()) {
                pipeline.addLast(ProxyServerHandler.NAME, RakNetServer.this.proxyServerHandler);
            }
            pipeline.addLast(ServerDatagramHandler.NAME, RakNetServer.this.serverDatagramHandler);
            RakNetServer.this.channels.add(channel);
        }
    }
}
