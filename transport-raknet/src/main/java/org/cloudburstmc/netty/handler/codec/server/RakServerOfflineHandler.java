package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.util.RakUtils;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

@Sharable
public class RakServerOfflineHandler extends AdvancedChannelInboundHandler<DatagramPacket> {

    public static final String NAME = "rak-offline-handler";
    public static final RakServerOfflineHandler INSTANCE = new RakServerOfflineHandler();

    private final ExpiringMap<InetSocketAddress, RakPendingConnection> pendingConnections = ExpiringMap.builder()
            .expiration(30, TimeUnit.SECONDS)
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener((key, value) -> ReferenceCountUtil.release(value))
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

        ByteBuf magicBuf =  ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        long guid =  ctx.channel().config().getOption(RakChannelOption.RAK_GUID);

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                this.onUnconnectedPong(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_1:
                this.onOpenConnectionRequest1(ctx, packet, magicBuf, guid);
                break;
            case ID_OPEN_CONNECTION_REQUEST_2:
                this.onOpenConnectionRequest2(ctx, packet, magicBuf, guid);
                break;
        }
    }

    private void onUnconnectedPong(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        long pingTime = packet.content().readLong();

        // We have already verified this
        packet.content().skipBytes(magicBuf.readableBytes());
        ctx.fireChannelRead(RakPing.newInstance(pingTime, packet.sender()));
    }

    private void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();

        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());
        int protocolVersion = buffer.readUnsignedByte();

        // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header)
        int mtu = buffer.readableBytes() + 1 + 16 + 1 + (sender.getAddress() instanceof Inet6Address ? 40 : 20) + UDP_HEADER_SIZE;

        int[] supportedProtocols = ctx.channel().config().getOption(RakChannelOption.RAK_SUPPORTED_PROTOCOLS);
        if (Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
            this.sendIncompatibleVersion(ctx, packet.sender(), protocolVersion, magicBuf, guid);
            return;
        }

        // TODO: banned address check?
        // TODO: max connections check?

        RakPendingConnection pendingConnection = this.pendingConnections.get(sender);
        if (pendingConnection != null) {
            // Already received onOpenConnectionRequest2
            this.sendAlreadyConnected(ctx, sender, magicBuf, guid);
            return;
        }

        this.pendingConnections.put(sender, RakPendingConnection.newInstance(protocolVersion));

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(28, 28);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        replyBuffer.writeBoolean(false); // Security
        replyBuffer.writeShort(RakUtils.clamp(mtu, MINIMUM_MTU_SIZE, MAXIMUM_MTU_SIZE));
        ctx.writeAndFlush(new DatagramPacket(replyBuffer, sender));
    }

    private void onOpenConnectionRequest2(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();
        // Skip already verified magic
        buffer.skipBytes(magicBuf.readableBytes());

        RakPendingConnection pendingConnection = this.pendingConnections.remove(sender);
        if (pendingConnection == null) {
            // No incoming connection, ignore
            return;
        }

        // TODO: Verify serveAddress matches?
        InetSocketAddress serverAddress = RakUtils.readAddress(buffer);
        int mtu = buffer.readUnsignedShort();
        long clientGuid = buffer.readLong();

        RakServerChannel serverChannel = (RakServerChannel) ctx.channel();
        RakChildChannel channel = serverChannel.createChildChannel(sender);
        if (channel == null) {
            // Already connected
            this.sendAlreadyConnected(ctx, sender, magicBuf, guid);
            return;
        }

        channel.config().setMtu(mtu);
        channel.config().setGuid(clientGuid);
        channel.config().setProtocolVersion(pendingConnection.getProtocolVersion());

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(31);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_2);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        RakUtils.writeAddress(replyBuffer, packet.recipient());
        replyBuffer.writeShort(mtu);
        replyBuffer.writeBoolean(false); // Security
        channel.writeAndFlush(replyBuffer); // Send first packet thought child channel
    }

    private void sendIncompatibleVersion(ChannelHandlerContext ctx, InetSocketAddress sender, int protocolVersion, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(protocolVersion);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, sender));
    }

    private void sendAlreadyConnected(ChannelHandlerContext ctx, InetSocketAddress sender,  ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_ALREADY_CONNECTED);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, sender));
    }
}