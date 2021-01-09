package com.nukkitx.network.raknet;

import com.nukkitx.network.util.EventLoops;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public class RakNetClient extends RakNet {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetClient.class);
    private final ClientDatagramHandler handler = new ClientDatagramHandler();
    private final Queue<PongEntry> inboundPongs = PlatformDependent.newMpscQueue();
    private final Map<InetSocketAddress, PingEntry> pings = new HashMap<>();
    private final Map<String, Consumer<Throwable>> exceptionHandlers = new HashMap<>();

    protected InetSocketAddress bindAddress;
    protected RakNetClientSession session;
    private Channel channel;

    public RakNetClient() {
        this(null, EventLoops.commonGroup());
    }

    public RakNetClient(InetSocketAddress bindAddress) {
        this(bindAddress, EventLoops.commonGroup());
    }

    public RakNetClient(@Nullable InetSocketAddress bindAddress, EventLoopGroup eventLoopGroup) {
        super(eventLoopGroup);
        this.bindAddress = bindAddress;
        this.exceptionHandlers.put("DEFAULT", (t) -> log.error("An exception occurred in RakNet (Client)", t));
    }

    @Override
    protected CompletableFuture<Void> bindInternal() {
        this.bootstrap.handler(this.handler);
        ChannelFuture channelFuture = this.bindAddress == null? this.bootstrap.bind() : this.bootstrap.bind(this.bindAddress);

        CompletableFuture<Void> future = new CompletableFuture<>();
        channelFuture.addListener((ChannelFuture promise) -> {
            if (promise.cause() != null) {
                future.completeExceptionally(promise.cause());
                return;
            }

            SocketAddress address = promise.channel().localAddress();
            if (!(address instanceof InetSocketAddress)) {
                future.completeExceptionally(new IllegalArgumentException("Excepted InetSocketAddress but got "+address.getClass().getSimpleName()));
                return;
            }
            this.bindAddress = (InetSocketAddress) address;
            future.complete(null);
        });
        return future;
    }

    public RakNetClientSession connect(InetSocketAddress address) {
        if (!this.isRunning()) {
            throw new IllegalStateException("RakNet has not been started");
        }
        if (session != null) {
            throw new IllegalStateException("Session has already been created");
        }

        this.session = new RakNetClientSession(this, address, this.channel, this.channel.eventLoop(),
                MAXIMUM_MTU_SIZE, this.protocolVersion);
        return this.session;
    }

    public CompletableFuture<RakNetPong> ping(InetSocketAddress address, long timeout, TimeUnit unit) {
        if (!this.isRunning()) {
            throw new IllegalStateException("RakNet has not been started");
        }

        if (this.session != null && this.session.address.equals(address)) {
            throw new IllegalArgumentException("Cannot ping connected address");
        }
        if (this.pings.containsKey(address)) {
            return this.pings.get(address).future;
        }

        CompletableFuture<RakNetPong> pongFuture = new CompletableFuture<>();

        PingEntry entry = new PingEntry(pongFuture, System.currentTimeMillis() + unit.toMillis(timeout));
        this.pings.put(address, entry);
        this.sendUnconnectedPing(address);

        return pongFuture;
    }

    public void addExceptionHandler(String handlerId, Consumer<Throwable> handler) {
        Objects.requireNonNull(handlerId, "handlerId is empty (client)");
        Objects.requireNonNull(handler, "clientExceptionHandler (handler is null)");
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

    @Override
    public InetSocketAddress getBindAddress() {
        return this.bindAddress;
    }

    @Override
    protected void onTick() {
        final long curTime = System.currentTimeMillis();
        final RakNetClientSession session = this.session;
        if (session != null && !session.isClosed()) {
            session.eventLoop.execute(() -> session.onTick(curTime));
        }

        PongEntry pong;
        while ((pong = this.inboundPongs.poll()) != null) {
            PingEntry ping = this.pings.remove(pong.address);
            if (ping == null) {
                continue;
            }

            ping.future.complete(new RakNetPong(pong.pingTime, curTime, pong.guid, pong.userData));
        }

        Iterator<PingEntry> iterator = this.pings.values().iterator();
        while (iterator.hasNext()) {
            PingEntry entry = iterator.next();
            if (curTime >= entry.timeout) {
                entry.future.completeExceptionally(new TimeoutException());
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        super.close();
        if (this.session != null && !this.session.isClosed()) {
            this.session.close();
        }

        if (this.channel != null) {
            this.channel.close().syncUninterruptibly();
        }
    }

    private void onUnconnectedPong(DatagramPacket packet) {
        ByteBuf content = packet.content();
        long pingTime = content.readLong();
        long guid = content.readLong();
        if (!RakNetUtils.verifyUnconnectedMagic(content)) {
            return;
        }

        byte[] userData = null;
        if (content.isReadable()) {
            userData = new byte[content.readUnsignedShort()];
            content.readBytes(userData);
        }

        this.inboundPongs.offer(new PongEntry(packet.sender(), pingTime, guid, userData));
    }

    private void sendUnconnectedPing(InetSocketAddress recipient) {
        ByteBuf buffer = this.channel.alloc().ioBuffer(23);
        buffer.writeByte(ID_UNCONNECTED_PING);
        buffer.writeLong(System.currentTimeMillis());
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);

        this.channel.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    @RequiredArgsConstructor
    private static class PingEntry {
        private final CompletableFuture<RakNetPong> future;
        private final long timeout;
    }

    @RequiredArgsConstructor
    private static class PongEntry {
        private final InetSocketAddress address;
        private final long pingTime;
        private final long guid;
        private final byte[] userData;
    }

    private class ClientDatagramHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) {
                return;
            }

            DatagramPacket packet = (DatagramPacket) msg;
            try {
                ByteBuf content = packet.content();
                int packetId = content.readUnsignedByte();

                if (packetId == ID_UNCONNECTED_PONG) {
                    RakNetClient.this.onUnconnectedPong(packet);
                } else if (session != null && session.address.equals(packet.sender())) {
                    content.readerIndex(0);
                    if (session.eventLoop.inEventLoop()) {
                        session.onDatagram(content);
                    } else {
                        session.eventLoop.execute(() -> session.onDatagram(content));
                    }
                }
            } finally {
                packet.release();
            }
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            if (ctx.channel().isRegistered()) {
                RakNetClient.this.channel = ctx.channel();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            for (Consumer<Throwable> handler : RakNetClient.this.getExceptionHandlers()) {
                handler.accept(cause);
            }
        }
    }
}
