package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.Inet6Address;
import java.net.InetSocketAddress;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

@Sharable
public class RakServerOnlineInitialHandler extends SimpleChannelInboundHandler<EncapsulatedPacket> {

    public static final String NAME = "rak-server-online-initial-handler";

    private final Channel channel;

    public RakServerOnlineInitialHandler(Channel channel) {
        this.channel = channel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket message) throws Exception {
        ByteBuf buf = message.getBuffer();
        int packetId = buf.getUnsignedByte(buf.readerIndex());

        switch (packetId) {
            case ID_CONNECTION_REQUEST:
                this.onConnectionRequest(ctx, buf);
                break;
            case ID_NEW_INCOMING_CONNECTION:
                buf.skipBytes(1);
                System.out.println("Received new incoming connection");
                // We have connected and no longer need this handler
                ctx.pipeline().remove(this);
                channel.pipeline().fireChannelActive();
//                ctx.fireChannelActive();
//                ctx.fireUserEventTriggered(RakEvent.NEW_INCOMING_CONNECTION);
                break;
            default:
                ctx.fireChannelRead(message);
                break;
        }
    }

    private void onConnectionRequest(ChannelHandlerContext ctx, ByteBuf buffer) {
        buffer.skipBytes(1);
        System.out.println("Received connection request");
        long guid = ((RakChannelConfig) this.channel.config()).getGuid();
        long serverGuid = buffer.readLong();
        long timestamp = buffer.readLong();
        boolean security = buffer.readBoolean();

        if (serverGuid != guid || security) {
            this.sendConnectionRequestFailed(ctx, guid);
            System.out.println("Sending connection request failed");
        } else {
            this.sendConnectionRequestAccepted(ctx, timestamp);
            System.out.println("Sending connection request accepted");
        }
    }

    private void sendConnectionRequestAccepted(ChannelHandlerContext ctx, long time) {
        InetSocketAddress address = ((InetSocketAddress) this.channel.remoteAddress());
        boolean ipv6 = address.getAddress() instanceof Inet6Address;
        ByteBuf outBuf = ctx.alloc().ioBuffer(ipv6 ? 628 : 166);

        outBuf.writeByte(ID_CONNECTION_REQUEST_ACCEPTED);
        RakUtils.writeAddress(outBuf, address);
        System.out.println("Address: " + address);
        outBuf.writeShort(0); // System index
        for (InetSocketAddress socketAddress : ipv6 ? LOCAL_IP_ADDRESSES_V6 : LOCAL_IP_ADDRESSES_V4) {
            RakUtils.writeAddress(outBuf, socketAddress);
        }
        outBuf.writeLong(time);
        outBuf.writeLong(System.currentTimeMillis());

        ctx.writeAndFlush(new RakMessage(outBuf, RakReliability.RELIABLE, RakPriority.IMMEDIATE));
    }

    private void sendConnectionRequestFailed(ChannelHandlerContext ctx, long guid) {
        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        int length = 9 + magicBuf.readableBytes();

        ByteBuf reply = ctx.alloc().ioBuffer(length);
        reply.writeByte(ID_CONNECTION_REQUEST_FAILED);
        reply.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        reply.writeLong(guid);
        ctx.writeAndFlush(reply);
        ctx.fireUserEventTriggered(RakDisconnectReason.CONNECTION_REQUEST_FAILED).close();
    }
}
