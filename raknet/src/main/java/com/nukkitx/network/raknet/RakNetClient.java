package com.nukkitx.network.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;

import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.*;

@ParametersAreNonnullByDefault
public class RakNetClient extends RakNet {
    private final ClientDatagramHandler handler = new ClientDatagramHandler();
    private final ConcurrentMap<InetSocketAddress, PingEntry> pings = new ConcurrentHashMap<>();
    private RakNetSession session;
    private Channel channel;

    public RakNetClient(InetSocketAddress bindAddress) {
        this(bindAddress, Executors.newSingleThreadScheduledExecutor());
    }

    public RakNetClient(InetSocketAddress bindAddress, ScheduledExecutorService scheduler) {
        this(bindAddress, scheduler, scheduler);
    }

    public RakNetClient(InetSocketAddress bindAddress, ScheduledExecutorService scheduler, Executor executor) {
        super(bindAddress, scheduler, executor);
    }

    @Override
    protected CompletableFuture<Void> bindInternal() {
        ChannelFuture channelFuture = this.bootstrap.handler(this.handler).bind(this.bindAddress);

        CompletableFuture<Void> future = new CompletableFuture<>();
        channelFuture.addListener(future1 -> {
            if (future1.cause() != null) {
                future.completeExceptionally(future1.cause());
            }
            future.complete(null);
        });
        return future;
    }

    public RakNetSession connect(InetSocketAddress address) {
        if (!this.isRunning()) {
            throw new IllegalStateException("RakNet has not been started");
        }
        if (session != null) {
            throw new IllegalStateException("Session has already been created");
        }

        this.session = new RakNetClientSession(address, this.channel, this, RakNetConstants.MAXIMUM_MTU_SIZE);
        return this.session;
    }

    public CompletableFuture<RakNetPong> ping(InetSocketAddress address, long timeout, TimeUnit unit) {
        if (!this.isRunning()) {
            throw new IllegalStateException("RakNet has not been started");
        }

        if (session != null && session.address.equals(address)) {
            throw new IllegalArgumentException("Cannot ping connected address");
        }
        if (pings.containsKey(address)) {
            return pings.get(address).future;
        }

        CompletableFuture<RakNetPong> pongFuture = new CompletableFuture<>();

        PingEntry entry = new PingEntry(pongFuture, System.currentTimeMillis() + unit.toMillis(timeout));
        pings.put(address, entry);
        this.sendUnconnectedPing(address);

        return pongFuture;
    }

    @Override
    protected void onTick() {
        if (session != null) {
            this.executor.execute(session::onTick);
        }
        long currentTime = System.currentTimeMillis();
        Iterator<PingEntry> iterator = this.pings.values().iterator();
        while (iterator.hasNext()) {
            PingEntry entry = iterator.next();
            if (currentTime >= entry.timeout) {
                entry.future.completeExceptionally(new TimeoutException());
                iterator.remove();
            }
        }
    }

    private void onUnconnectedPong(DatagramPacket packet) {
        PingEntry entry = this.pings.get(packet.sender());
        if (entry == null) {
            return;
        }

        ByteBuf content = packet.content();
        long pingTime = content.readLong();
        long guid = content.readLong();
        if (!RakNetUtils.verifyUnconnectedMagic(content)) {
            return;
        }

        byte[] userData = null;
        if (content.isReadable()) {
            userData = new byte[content.readableBytes()];
            content.readBytes(userData);
        }

        entry.future.complete(new RakNetPong(pingTime, System.currentTimeMillis(), guid, userData));
    }

    private void sendUnconnectedPing(InetSocketAddress recipient) {
        ByteBuf buffer = this.channel.alloc().directBuffer(9);
        buffer.writeByte(RakNetConstants.ID_UNCONNECTED_PING);
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

    private class ClientDatagramHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) {
                return;
            }

            @Cleanup("release") DatagramPacket packet = (DatagramPacket) msg;
            ByteBuf content = packet.content();
            int packetId = content.readUnsignedByte();

            if (packetId == RakNetConstants.ID_UNCONNECTED_PONG) {
                RakNetClient.this.onUnconnectedPong(packet);
            } else if (session != null) {
                content.readerIndex(0);
                session.onDatagram(packet);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            RakNetClient.this.channel = ctx.channel();
        }
    }
}
