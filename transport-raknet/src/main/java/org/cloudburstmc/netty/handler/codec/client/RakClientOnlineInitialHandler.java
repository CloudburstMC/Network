package org.cloudburstmc.netty.handler.codec.client;


import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.RakNetUtils;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;

import java.net.InetSocketAddress;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class RakClientOnlineInitialHandler extends SimpleChannelInboundHandler<RakMessage> {

    public static final String NAME = "rak-client-online-initial-handler";
    private final ChannelPromise successPromise;

    public RakClientOnlineInitialHandler(ChannelPromise promise) {
        this.successPromise = promise;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        long guid = ctx.channel().config().getOption(RakChannelOption.RAK_GUID);

        ByteBuf buffer = ctx.alloc().ioBuffer(18);
        buffer.writeByte(ID_CONNECTION_REQUEST);
        buffer.writeLong(guid);
        buffer.writeLong(System.currentTimeMillis());
        buffer.writeBoolean(false);
        ctx.writeAndFlush(new RakMessage(buffer, RakReliability.RELIABLE_ORDERED, RakPriority.IMMEDIATE));
    }

    private void onSuccess(ChannelHandlerContext ctx) {
        // At this point connection is fully initialized.
        Channel channel = ctx.channel();
        channel.pipeline().remove(RakClientOfflineHandler.NAME);
        channel.pipeline().remove(RakClientOnlineInitialHandler.NAME);
        this.successPromise.trySuccess();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) throws Exception {
        ByteBuf buf = msg.content();
        int packetId = buf.readUnsignedByte();

        switch (packetId) {
            case ID_CONNECTION_REQUEST_ACCEPTED:
                this.onConnectionRequestAccepted(ctx, buf);
                this.onSuccess(ctx);
                break;
            case ID_CONNECTION_REQUEST_FAILED:
                this.successPromise.tryFailure(new IllegalStateException("Connection denied"));
                break;
        }
    }

    private void onConnectionRequestAccepted(ChannelHandlerContext ctx, ByteBuf buf) {
        RakNetUtils.readAddress(buf); // Client address
        buf.readUnsignedShort(); // System index

        // Address + 2 * Long - Minimum amount of data
        int required = IPV4_MESSAGE_SIZE + 16;
        int count = 0;
        long pingTime = 0;
        try {
            while (buf.isReadable(required)) {
                RakNetUtils.readAddress(buf);
                count++;
            }
            pingTime = buf.readLong();
            buf.readLong();
        } catch (IndexOutOfBoundsException ignored) {
            // Hive sends malformed IPv6 address
        }

        ByteBuf buffer = ctx.alloc().ioBuffer();
        buffer.writeByte(ID_NEW_INCOMING_CONNECTION);
        RakNetUtils.writeAddress(buffer, (InetSocketAddress) ctx.channel().remoteAddress());
        for (int i = 0; i < count; i++) {
            RakNetUtils.writeAddress(buffer, LOCAL_ADDRESS);
        }
        buffer.writeLong(pingTime);
        buffer.writeLong(System.currentTimeMillis());
        ctx.writeAndFlush(new RakMessage(buffer, RakReliability.RELIABLE_ORDERED, RakPriority.IMMEDIATE));
    }
}
