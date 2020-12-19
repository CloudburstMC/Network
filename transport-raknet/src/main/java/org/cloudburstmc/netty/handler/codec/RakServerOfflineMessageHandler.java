package org.cloudburstmc.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.RakServerChannelConfig;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakServerOfflineMessageHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    public static String NAME = "rak-offline-handler";

    private static boolean verifyOfflineMagic(ByteBuf buf, ByteBuf magicBuf) {
        if (!buf.isReadable(magicBuf.readableBytes())) return false;
        return ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        if (buf.isReadable()) return; // Empty packet?
        short packetId = buf.readUnsignedByte();

        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();

        switch (packetId) {
            case ID_UNCONNECTED_PING:
                if (!buf.isReadable(8)) return;
                long pingTime = buf.readLong();
                if (!verifyOfflineMagic(buf, magicBuf)) return;

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
                if (!verifyOfflineMagic(buf, magicBuf)) return;
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
                if (!verifyOfflineMagic(buf, magicBuf)) return;
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
                        .remove(this)
                        .addLast(RakDatagramDecoder.INSTANCE, RakDatagramEncoder.INSTANCE);
                // TODO: Add the extra handlers
                break;
        }
    }
}
