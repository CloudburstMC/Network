package org.cloudburstmc.netty.handler.codec.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.net.Inet6Address;
import java.net.InetSocketAddress;

import static org.cloudburstmc.netty.RakNetConstants.*;

@Sharable
public class RakServerOnlineInitialHandler extends SimpleChannelInboundHandler<RakMessage> {

    public static final RakServerOnlineInitialHandler INSTANCE = new RakServerOnlineInitialHandler();
    public static final String NAME = "rak-server-online-initial-handler";

    private RakServerOnlineInitialHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) throws Exception {
        ByteBuf buf = msg.content();
        int packetId = buf.readUnsignedByte();

        switch (packetId) {
            case ID_CONNECTION_REQUEST:
                this.onConnectionRequest(ctx, buf);
                break;
            case ID_NEW_INCOMING_CONNECTION:
                // We have connected and no longer need this handler
                ctx.pipeline().remove(this);
                ctx.fireUserEventTriggered(RakEvent.NEW_INCOMING_CONNECTION);
                break;
        }
    }

    private void onConnectionRequest(ChannelHandlerContext ctx, ByteBuf buffer) {
        long guid = ((RakServerChannelConfig) ctx.channel().config()).getGuid();
        long serverGuid = buffer.readLong();
        long timestamp = buffer.readLong();
        boolean security = buffer.readBoolean();

        if (serverGuid != guid || security) {
            this.sendConnectionRequestFailed(ctx, guid);
        } else {
            this.sendConnectionRequestAccepted(ctx, timestamp);
        }
    }

    private void sendConnectionRequestAccepted(ChannelHandlerContext ctx, long time) {
        InetSocketAddress address = ((InetSocketAddress) ctx.channel().remoteAddress());
        boolean ipv6 = address.getAddress() instanceof Inet6Address;
        ByteBuf outBuf = ctx.alloc().ioBuffer(ipv6 ? 628 : 166);

        outBuf.writeByte(ID_CONNECTION_REQUEST_ACCEPTED);
        RakNetUtils.writeAddress(outBuf, address);
        outBuf.writeShort(0); // System index
        for (InetSocketAddress socketAddress : ipv6 ? LOCAL_IP_ADDRESSES_V6 : LOCAL_IP_ADDRESSES_V4) {
            RakNetUtils.writeAddress(outBuf, socketAddress);
        }
        outBuf.writeLong(time);
        outBuf.writeLong(System.currentTimeMillis());

        ctx.writeAndFlush(new RakMessage(outBuf, RakReliability.RELIABLE, RakPriority.IMMEDIATE));
    }

    private void sendConnectionRequestFailed(ChannelHandlerContext ctx, long guid) {
        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        int length = 9 + magicBuf.readableBytes();

        ByteBuf reply = ctx.alloc().ioBuffer(length, length);
        reply.writeByte(ID_CONNECTION_REQUEST_FAILED);
        reply.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        reply.writeLong(guid);
        ctx.writeAndFlush(reply);
        ctx.fireUserEventTriggered(RakDisconnectReason.CONNECTION_REQUEST_FAILED).close();
    }
}
