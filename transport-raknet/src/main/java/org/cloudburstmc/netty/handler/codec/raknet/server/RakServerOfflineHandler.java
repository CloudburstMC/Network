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
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.channel.raknet.config.RakServerMetrics;
import org.cloudburstmc.netty.handler.codec.raknet.AdvancedChannelInboundHandler;
import org.cloudburstmc.netty.util.RakUtils;
import org.cloudburstmc.netty.util.SipHash;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakServerOfflineHandler extends AdvancedChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-offline-handler";

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerOfflineHandler.class);

    private final RakServerChannel channel;

    public RakServerOfflineHandler(RakServerChannel channel) {
        this.channel = channel;
    }

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
                    ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
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

        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();

        RakServerMetrics metrics = this.channel.config().getMetrics();

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                if (metrics != null) metrics.unconnectedPing(packet.sender());
                this.onUnconnectedPing(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_1:
                if (metrics != null) metrics.connectionInitPacket(packet.sender(), ID_OPEN_CONNECTION_REQUEST_1);
                this.onOpenConnectionRequest1(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_2:
                if (metrics != null) metrics.connectionInitPacket(packet.sender(), ID_OPEN_CONNECTION_REQUEST_2);
                this.onOpenConnectionRequest2(ctx, packet, magicBuf, guid);
                break;
        }
    }

    private void onUnconnectedPing(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        long pingTime = packet.content().readLong();

        boolean handlePing = ((RakServerChannelConfig) ctx.channel().config()).getHandlePing();
        if (handlePing) {
            ctx.fireChannelRead(new RakPing(pingTime, packet.sender()));
            return;
        }

        ByteBuf advertisement = ((RakServerChannelConfig) ctx.channel().config()).getAdvertisement();

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

        ctx.writeAndFlush(RakUtils.datagramReply(out, packet));
    }

    private void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        RakServerChannelConfig config = (RakServerChannelConfig) ctx.channel().config();

        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();

        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());
        int protocolVersion = buffer.readUnsignedByte();

        // 1 (Packet ID), (Magic), 1 (Protocol Version), 20/40 (IP Header)
        int mtu = buffer.readableBytes() + 1 + magicBuf.readableBytes() + 1 + (sender.getAddress() instanceof Inet6Address ? 40 : 20) + UDP_HEADER_SIZE;

        int[] supportedProtocols = config.getSupportedProtocols();
        if (supportedProtocols != null && Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
            int latestVersion = supportedProtocols[supportedProtocols.length - 1];
            this.sendIncompatibleVersion(ctx, packet, latestVersion, magicBuf, guid);
            return;
        }

        // TODO: banned address check?
        // TODO: max connections check?

        boolean sendCookie = config.getCookieMode() == RakServerCookieMode.ACTIVE;

        int bufferCapacity = sendCookie ? 32 : 28; // 4 byte cookie

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(bufferCapacity, bufferCapacity);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        replyBuffer.writeBoolean(sendCookie); // Security
        if (sendCookie) {
            int cookie = config.getSipHash().generateStatelessCookie(sender, protocolVersion);
            replyBuffer.writeInt(cookie);
        }
        replyBuffer.writeShort(RakUtils.clamp(mtu, config.getMinMtu(), config.getMaxMtu()));
        ctx.writeAndFlush(RakUtils.datagramReply(replyBuffer, packet));
    }

    private void onOpenConnectionRequest2(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        RakServerChannelConfig config = (RakServerChannelConfig) ctx.channel().config();
        RakServerCookieMode mode = config.getCookieMode();

        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();
        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());

        boolean expectCookie = config.getCookieMode() != RakServerCookieMode.INVALID;
        int cookie = 0;
        if (expectCookie) {
            cookie = buffer.readInt();
            if (!config.getSipHash().validateCookie(cookie, sender, mode)) {
                if (log.isTraceEnabled()) {
                    log.trace("[{}] Received ID_OPEN_CONNECTION_REQUEST_2 with invalid cookie (Mode: {})", sender, mode);
                }

                RakServerMetrics metrics = this.channel.config().getMetrics();
                if (metrics != null) {
                    metrics.invalidCookie(sender);
                }

                // Incorrect/invalid cookie provided
                // This is likely source IP spoofing so we will not reply
                return;
            }
                buffer.readBoolean(); // Client wrote challenge
        }

        // TODO: Verify serverAddress matches?
        RakUtils.readAddress(buffer); // serverAddress
        int mtu = buffer.readUnsignedShort();
        long clientGuid = buffer.readLong();

        int minMtu = config.getMinMtu();
        int maxMtu = config.getMaxMtu();
        if (mtu < minMtu || mtu > maxMtu) {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Received ID_OPEN_CONNECTION_REQUEST_2, but the MTU was {}. Expecting a value between {} - {}",
                        sender, mtu, minMtu, maxMtu);
            }
            // The client should have already negotiated a valid MTU
            this.sendAlreadyConnected(ctx, packet, magicBuf, guid);
            return;
        }

        RakServerChannel serverChannel = (RakServerChannel) ctx.channel();
        RakChildChannel channel = serverChannel.createChildChannel(sender, packet.recipient(), clientGuid, mtu);
        if (channel == null) {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Received ID_OPEN_CONNECTION_REQUEST_2, but a channel already exists for this socket address",
                        sender);
            }
            // Already connected
            this.sendAlreadyConnected(ctx, packet, magicBuf, guid);
            return;
        }

        if (mode == RakServerCookieMode.ACTIVE
                || mode == RakServerCookieMode.OFFLOADED
                || mode == RakServerCookieMode.OFFLOADED_PSK) {
            int protocolVersion = SipHash.getProtocolVersion(cookie);
            channel.config().setOption(RakChannelOption.RAK_PROTOCOL_VERSION, protocolVersion);
        }

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(31);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_2);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        RakUtils.writeAddress(replyBuffer, packet.sender());
        replyBuffer.writeShort(mtu);
        replyBuffer.writeBoolean(false); // Security
        ctx.writeAndFlush(RakUtils.datagramReply(replyBuffer, packet));
    }

    private void sendIncompatibleVersion(ChannelHandlerContext ctx, DatagramPacket request, int protocolVersion, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(protocolVersion);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(RakUtils.datagramReply(buffer, request));
    }

    private void sendAlreadyConnected(ChannelHandlerContext ctx, DatagramPacket request, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_ALREADY_CONNECTED);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(RakUtils.datagramReply(buffer, request));
    }
}
