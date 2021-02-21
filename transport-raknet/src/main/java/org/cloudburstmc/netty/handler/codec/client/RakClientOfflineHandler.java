package org.cloudburstmc.netty.handler.codec.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.CorruptedFrameException;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.RakSessionCodec;
import org.cloudburstmc.netty.handler.codec.common.RakDatagramCodec;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class RakClientOfflineHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-client-handler";

    private final ChannelPromise successPromise;
    private ScheduledFuture<?> timeoutFuture;

    public RakClientOfflineHandler(ChannelPromise promise) {
        this.successPromise = promise;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        Channel channel = ctx.channel();
        long timeout = channel.config().getOption(RakChannelOption.RAK_CONNECT_TIMEOUT);
        this.timeoutFuture = channel.eventLoop().schedule(this::onTimeout, timeout, TimeUnit.MILLISECONDS);
        this.successPromise.addListener(future -> this.timeoutFuture.cancel(false));

        int mtuSize = channel.config().getOption(RakChannelOption.RAK_MTU);
        ByteBuf magicBuf = channel.config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        int rakVersion = channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
        InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();

        ByteBuf request = ctx.alloc().ioBuffer(mtuSize);
        request.writeByte(ID_OPEN_CONNECTION_REQUEST_1);
        magicBuf.getBytes(magicBuf.readerIndex(), request);
        request.writeByte(rakVersion);
        // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header));
        request.writeZero(mtuSize - 1 - 16 - 1 - (address.getAddress() instanceof Inet6Address ? 40 : 20) - UDP_HEADER_SIZE);
        channel.writeAndFlush(request);
    }

    private void onTimeout() {
        this.successPromise.tryFailure(new ConnectTimeoutException());
    }

    private void onSuccess(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channel.pipeline().addLast(RakDatagramCodec.NAME, RakDatagramCodec.INSTANCE);

        // Create new session which decodes RakDatagramPacket to RakMessage
        RakSessionCodec sessionCodec = null; // TODO: create session here, consider RakClientChannel#createSession()
        channel.pipeline().addLast(RakSessionCodec.NAME, sessionCodec);
        channel.pipeline().addLast(RakClientOnlineInitialHandler.NAME, new RakClientOnlineInitialHandler(this.successPromise));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        if (buf.isReadable()){
            return; // Empty packet?
        }
        short packetId = buf.readUnsignedByte();

        ByteBuf magicBuf =  ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        if (!buf.isReadable(magicBuf.readableBytes()) || !ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf)) {
            this.successPromise.tryFailure(new CorruptedFrameException("RakMagic does not match"));
            return;
        }

        switch (packetId) {
            case ID_OPEN_CONNECTION_REPLY_1:
                this.onOpenConnectionReply1(ctx, packet, magicBuf);
                break;
            case ID_OPEN_CONNECTION_REPLY_2:
                this.onOpenConnectionReply2(ctx, packet);
                this.onSuccess(ctx);
                break;
            case ID_INCOMPATIBLE_PROTOCOL_VERSION:
                ctx.fireUserEventTriggered(RakDisconnectReason.INCOMPATIBLE_PROTOCOL_VERSION);
                this.successPromise.tryFailure(new IllegalStateException("Incompatible raknet version"));
                break;
            case ID_ALREADY_CONNECTED:
                ctx.fireUserEventTriggered(RakDisconnectReason.ALREADY_CONNECTED);
                this.successPromise.tryFailure(new ChannelException("Already connected"));
                break;
            case ID_NO_FREE_INCOMING_CONNECTIONS:
                ctx.fireUserEventTriggered(RakDisconnectReason.NO_FREE_INCOMING_CONNECTIONS);
                this.successPromise.tryFailure(new ChannelException("No free incoming connections"));
                break;
            case ID_IP_RECENTLY_CONNECTED:
                ctx.fireUserEventTriggered(RakDisconnectReason.IP_RECENTLY_CONNECTED);
                this.successPromise.tryFailure(new ChannelException("Address recently connected"));
                break;
        }
    }

    private void onOpenConnectionReply1(ChannelHandlerContext ctx, DatagramPacket packet, ByteBuf magicBuf) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();

        long serverGuid = buffer.readLong();
        boolean security = buffer.readBoolean();
        int mtu = buffer.readShort();
        if (security) {
            this.successPromise.tryFailure(new SecurityException());
            return;
        }

        // TODO: set server guid?
        ctx.channel().config().setOption(RakChannelOption.RAK_MTU, mtu);

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(34);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REQUEST_2);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        RakNetUtils.writeAddress(buffer, sender);
        replyBuffer.writeShort(mtu);
        replyBuffer.writeLong(ctx.channel().config().getOption(RakChannelOption.RAK_GUID));
        ctx.writeAndFlush(new DatagramPacket(replyBuffer, sender));
    }

    private void onOpenConnectionReply2(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buffer = packet.content();
        InetSocketAddress sender = packet.sender();

        long serverGuid = buffer.readLong();
        InetSocketAddress serverAddress = RakNetUtils.readAddress(buffer);
        int mtu = buffer.readShort();
        boolean security = buffer.readBoolean();

        ctx.channel().config().setOption(RakChannelOption.RAK_MTU, mtu);
    }
}
