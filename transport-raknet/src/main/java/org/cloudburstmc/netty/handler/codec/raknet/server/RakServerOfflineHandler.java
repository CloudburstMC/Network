/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.handler.codec.raknet.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.AdvancedChannelInboundHandler;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakServerOfflineHandler extends AdvancedChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-offline-handler";
    private static final int MAX_PACKETS_PER_SECOND = 10;

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerOfflineHandler.class);

    private final ExpiringMap<InetSocketAddress, Integer> pendingConnections = ExpiringMap.builder()
            .expiration(10, TimeUnit.SECONDS)
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener((key, value) -> ReferenceCountUtil.release(value))
            .build();

    private final ExpiringMap<InetAddress, AtomicInteger> packetsCounter = ExpiringMap.builder()
            .expiration(1, TimeUnit.SECONDS)
            .expirationPolicy(ExpirationPolicy.CREATED)
            .build();

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        if (!buf.isReadable()) {
            return false; // No packet ID
        }

        int startIndex = buf.readerIndex();
        try {
            int packetId = buf.readUnsignedByte();
            switch (packetId) {
                case ID_UNCONNECTED_PING:
                    if (buf.isReadable(8)) {
                        buf.readLong(); // Ping time
                    }
                case ID_OPEN_CONNECTION_REQUEST_1:
                case ID_OPEN_CONNECTION_REQUEST_2:
                    ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
                    return buf.isReadable(magicBuf.readableBytes()) && ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
                default:
                    return false;
            }
        } finally {
            buf.readerIndex(startIndex);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        short packetId = buf.readUnsignedByte();

        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        long guid = ctx.channel().config().getOption(RakChannelOption.RAK_GUID);

        AtomicInteger counter = this.packetsCounter.computeIfAbsent(packet.sender().getAddress(), s -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_PACKETS_PER_SECOND) {
            log.warn("[{}] Sent too many packets per second", packet.sender());
            return;
        }

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                this.onUnconnectedPing(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_1:
                this.onOpenConnectionRequest1(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_2:
                this.onOpenConnectionRequest2(ctx, packet, magicBuf, guid);
                break;
        }
    }

    private void onUnconnectedPing(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        long pingTime = packet.content().readLong();

        boolean handlePing = ctx.channel().config().getOption(RakChannelOption.RAK_HANDLE_PING);
        if (handlePing) {
            ctx.fireChannelRead(new RakPing(pingTime, packet.sender()));
            return;
        }

        ByteBuf advertisement = ctx.channel().config().getOption(RakChannelOption.RAK_ADVERTISEMENT);

        int packetLength = 35 + (advertisement != null ? advertisement.readableBytes() : -2);

        ByteBuf out = ctx.alloc().ioBuffer(packetLength);
        out.writeByte(ID_UNCONNECTED_PONG);
        out.writeLong(pingTime);
        out.writeLong(guid);
        out.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        if (advertisement != null) {
            out.writeShort(advertisement.readableBytes());
            out.writeBytes(advertisement, advertisement.readerIndex(), advertisement.readableBytes());
        }

        ctx.writeAndFlush(new DatagramPacket(out, packet.sender()));
    }

    private void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();

        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());
        int protocolVersion = buffer.readUnsignedByte();

        // 1 (Packet ID), (Magic), 1 (Protocol Version), 20/40 (IP Header)
        int mtu = buffer.readableBytes() + 1 + magicBuf.readableBytes() + 1 + (sender.getAddress() instanceof Inet6Address ? 40 : 20) + UDP_HEADER_SIZE;

        int[] supportedProtocols = ctx.channel().config().getOption(RakChannelOption.RAK_SUPPORTED_PROTOCOLS);
        if (supportedProtocols != null && Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
            int latestVersion = supportedProtocols[supportedProtocols.length - 1];
            this.sendIncompatibleVersion(ctx, packet.sender(), latestVersion, magicBuf, guid);
            return;
        }

        // TODO: banned address check?
        // TODO: max connections check?

        Integer version = this.pendingConnections.put(sender, protocolVersion);
        if (version != null && log.isTraceEnabled()) {
            log.trace("Received duplicate open connection request 1 from {}", sender);
        }

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(28, 28);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        replyBuffer.writeBoolean(false); // Security
        replyBuffer.writeShort(RakUtils.clamp(mtu, ctx.channel().config().getOption(RakChannelOption.RAK_MIN_MTU), ctx.channel().config().getOption(RakChannelOption.RAK_MAX_MTU)));
        ctx.writeAndFlush(new DatagramPacket(replyBuffer, sender));
    }

    private void onOpenConnectionRequest2(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();
        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());

        Integer version = this.pendingConnections.remove(sender);
        if (version == null) {
            // We can't determine the version without the previous request, so assume it's the wrong version.
            if (log.isTraceEnabled()) {
                log.trace("Received open connection request 2 from {} without open connection request 1", sender);
            }
            int[] supportedProtocols = ctx.channel().config().getOption(RakChannelOption.RAK_SUPPORTED_PROTOCOLS);
            int latestVersion = supportedProtocols == null ? RakConstants.RAKNET_PROTOCOL_VERSION : supportedProtocols[supportedProtocols.length - 1];
            this.sendIncompatibleVersion(ctx, sender, latestVersion, magicBuf, guid);
            return;
        }

        // TODO: Verify serverAddress matches?
        InetSocketAddress serverAddress = RakUtils.readAddress(buffer);
        int mtu = buffer.readUnsignedShort();
        long clientGuid = buffer.readLong();

        if (mtu < ctx.channel().config().getOption(RakChannelOption.RAK_MIN_MTU) || mtu > ctx.channel().config().getOption(RakChannelOption.RAK_MAX_MTU)) {
            // The client should have already negotiated a valid MTU
            this.sendAlreadyConnected(ctx, sender, magicBuf, guid);
            return;
        }

        RakServerChannel serverChannel = (RakServerChannel) ctx.channel();
        RakChildChannel channel = serverChannel.createChildChannel(sender, clientGuid, version, mtu);
        if (channel == null) {
            // Already connected
            this.sendAlreadyConnected(ctx, sender, magicBuf, guid);
            return;
        }

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(31);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_2);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        RakUtils.writeAddress(replyBuffer, packet.sender());
        replyBuffer.writeShort(mtu);
        replyBuffer.writeBoolean(false); // Security
        ctx.writeAndFlush(new DatagramPacket(replyBuffer, packet.sender()));
    }

    private void sendIncompatibleVersion(ChannelHandlerContext ctx, InetSocketAddress sender, int protocolVersion, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(protocolVersion);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, sender));
    }

    private void sendAlreadyConnected(ChannelHandlerContext ctx, InetSocketAddress sender, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_ALREADY_CONNECTED);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, sender));
    }
}
