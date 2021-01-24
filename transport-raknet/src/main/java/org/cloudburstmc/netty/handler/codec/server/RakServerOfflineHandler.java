package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.RakDatagramCodec;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakServerOfflineHandler extends ChannelInboundHandlerAdapter {

    public static String NAME = "rak-offline-handler";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean release = true;
        try {
            if (msg instanceof DatagramPacket && isRakNet(ctx, (DatagramPacket) msg)) {
                channelRead0(ctx, (DatagramPacket) msg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private boolean isRakNet(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();
        int startIndex = buf.readerIndex();
        try {

            if (!buf.isReadable()) return false; // No packet ID
            int packetId = buf.readUnsignedByte();

            if (packetId == ID_UNCONNECTED_PING && buf.isReadable(8)) {
                buf.readLong();
            }

            ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();

            if (!buf.isReadable(magicBuf.readableBytes())) return false; // Invalid magic

            return ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
        } finally {
            buf.readerIndex(startIndex);
        }
    }

    private void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        if (buf.isReadable()) return; // Empty packet?
        short packetId = buf.readUnsignedByte();

        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                long pingTime = buf.readLong();
                buf.skipBytes(magicBuf.readableBytes()); // We have already verified this

                // Send ping
                ByteBuf advertBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedAdvert();
                ByteBuf outBuf = ctx.alloc().ioBuffer(magicBuf.readableBytes() + 19 + advertBuf.readableBytes());
                outBuf.writeByte(ID_UNCONNECTED_PONG);
                outBuf.writeLong(pingTime);
                outBuf.writeLong(guid);
                outBuf.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
                outBuf.writeShort(advertBuf.readableBytes());
                outBuf.writeBytes(advertBuf, advertBuf.readerIndex(), advertBuf.readableBytes());

                ctx.writeAndFlush(outBuf);
                break;
            case ID_OPEN_CONNECTION_REQUEST_1:
                buf.skipBytes(magicBuf.readableBytes()); // We have already verified this
                int protocolVersion = buf.readUnsignedByte();
                int mtu = buf.readableBytes() + 1 + 16 + 1 + (packet.sender().getAddress() instanceof Inet6Address ? 40 : 20)
                        + UDP_HEADER_SIZE; // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header)

                int[] supportedProtocols = ((RakServerChannelConfig) ctx.channel().config()).getSupportedProtocols();
                if (Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
                    outBuf = ctx.alloc().ioBuffer(26, 26);
                    outBuf.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
                    outBuf.writeByte(protocolVersion);
                    outBuf.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
                    outBuf.writeLong(guid);
                } else {
                    outBuf = ctx.alloc().ioBuffer(28, 28);
                    outBuf.writeByte(ID_OPEN_CONNECTION_REPLY_1);
                    outBuf.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
                    outBuf.writeLong(guid);
                    outBuf.writeBoolean(false); // Security
                    outBuf.writeShort(RakNetUtils.clamp(mtu, MINIMUM_MTU_SIZE, MAXIMUM_MTU_SIZE));
                }
                ctx.writeAndFlush(outBuf);
                break;
            case ID_OPEN_CONNECTION_REQUEST_2:
                buf.skipBytes(magicBuf.readableBytes()); // We have already verified this
                // TODO: Verify address matches?
                InetSocketAddress serverAddress = RakNetUtils.readAddress(buf);
                mtu = buf.readUnsignedShort();
                long clientGuid = buf.readLong();

                // Send reply
                outBuf = ctx.alloc().ioBuffer(31);
                outBuf.writeByte(ID_OPEN_CONNECTION_REPLY_2);
                outBuf.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
                outBuf.writeLong(guid);
                RakNetUtils.writeAddress(outBuf, packet.recipient());
                outBuf.writeShort(mtu);
                outBuf.writeBoolean(false); // Security
                ctx.writeAndFlush(outBuf);

                // Setup session
                Channel channel = ctx.channel();
                channel.pipeline()
                        .addLast(RakDatagramCodec.NAME, RakDatagramCodec.INSTANCE);
                // TODO: Add the extra handlers
                break;
        }
    }
}
