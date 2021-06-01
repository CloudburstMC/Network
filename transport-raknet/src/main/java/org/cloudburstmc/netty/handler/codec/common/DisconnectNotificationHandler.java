package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.handler.codec.AdvancedChannelInboundHandler;

@ChannelHandler.Sharable
public class DisconnectNotificationHandler extends AdvancedChannelInboundHandler<EncapsulatedPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(DisconnectNotificationHandler.class);

    public static final DisconnectNotificationHandler INSTANCE = new DisconnectNotificationHandler();
    public static final String NAME = "rak-disconnect-notification-handler";

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        ByteBuf buf = ((EncapsulatedPacket) msg).getBuffer();
        return buf.getUnsignedByte(buf.readerIndex()) == RakConstants.ID_DISCONNECTION_NOTIFICATION;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket packet) throws Exception {
        ByteBuf buf = packet.getBuffer();
        buf.readUnsignedByte(); // Packet ID
        if (log.isTraceEnabled()) {
            log.trace("RakNet Session ({} => {}) by remote peer!", ctx.channel().localAddress(), ctx.channel().remoteAddress());
        }
        ctx.fireUserEventTriggered(RakDisconnectReason.CLOSED_BY_REMOTE_PEER).close();
    }
}
