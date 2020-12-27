package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;
import org.cloudburstmc.netty.handler.codec.RakDatagramCodec;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakServerOfflineHandler extends AdvancedChannelInboundHandler<DatagramPacket> {
    public static String NAME = "rak-offline-handler";

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)){
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        return this.isRakNet(ctx, packet);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        if (buf.isReadable()){
            return; // Empty packet?
        }

        short packetId = buf.readUnsignedByte();
        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();

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
        ByteBuf advertBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedAdvert();
        long pingTime = packet.content().readLong();
        packet.content().skipBytes(magicBuf.readableBytes()); // We have already verified this

        ByteBuf pongBuffer = ctx.alloc().ioBuffer(magicBuf.readableBytes() + 19 + advertBuf.readableBytes());
        pongBuffer.writeByte(ID_UNCONNECTED_PONG);
        pongBuffer.writeLong(pingTime);
        pongBuffer.writeLong(guid);
        pongBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        pongBuffer.writeShort(advertBuf.readableBytes());
        pongBuffer.writeBytes(advertBuf, advertBuf.readerIndex(), advertBuf.readableBytes());
        ctx.writeAndFlush(pongBuffer);
    }

    private void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        // Skin already verified magic
        buffer.skipBytes(magicBuf.readableBytes());
        int protocolVersion = buffer.readUnsignedByte();
        // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header)
        int mtu = buffer.readableBytes() + 1 + 16 + 1 + (packet.sender().getAddress() instanceof Inet6Address ? 40 : 20) + UDP_HEADER_SIZE;

        int[] supportedProtocols = ((RakServerChannelConfig) ctx.channel().config()).getSupportedProtocols();
        if (Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
            this.sendIncompatibleVersion(ctx, protocolVersion, magicBuf, guid);
            return;
        }

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(28, 28);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        replyBuffer.writeBoolean(false); // Security
        replyBuffer.writeShort(RakNetUtils.clamp(mtu, MINIMUM_MTU_SIZE, MAXIMUM_MTU_SIZE));
        ctx.writeAndFlush(replyBuffer);
    }

    private void onOpenConnectionRequest2(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = packet.content();
        // Skin already verified magic
        buffer.skipBytes(magicBuf.readableBytes());

        // TODO: Verify address matches?
        InetSocketAddress serverAddress = RakNetUtils.readAddress(buffer);
        int mtu = buffer.readUnsignedShort();
        long clientGuid = buffer.readLong();


        ByteBuf replyBuffer = ctx.alloc().ioBuffer(31);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_2);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        RakNetUtils.writeAddress(replyBuffer, packet.recipient());
        replyBuffer.writeShort(mtu);
        replyBuffer.writeBoolean(false); // Security
        ctx.writeAndFlush(replyBuffer);

        // Setup session
        Channel channel = ctx.channel();
        channel.pipeline().addLast(RakDatagramCodec.NAME, RakDatagramCodec.INSTANCE);
        // TODO: Add the extra handlers
    }

    private void sendIncompatibleVersion(ChannelHandlerContext ctx, int protocolVersion, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(protocolVersion);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(buffer);
    }

    private boolean isRakNet(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();
        if (!buf.isReadable()){
            return false; // No packet ID
        }

        int startIndex = buf.readerIndex();
        try {
            int packetId = buf.readUnsignedByte();
            if (packetId == ID_UNCONNECTED_PING && buf.isReadable(8)) {
                buf.readLong(); // Ping time
            }

            ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
            return buf.isReadable(magicBuf.readableBytes()) && ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
        } finally {
            buf.readerIndex(startIndex);
        }
    }
}
