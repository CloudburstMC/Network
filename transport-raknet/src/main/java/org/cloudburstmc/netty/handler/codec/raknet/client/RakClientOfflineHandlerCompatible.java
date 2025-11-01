package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakOfflineState;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.ConnectedPingHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.ConnectedPongHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.DisconnectNotificationHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.EncapsulatedToMessageHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakAcknowledgeHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakDatagramCodec;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodecCompatible;
import org.cloudburstmc.netty.util.RakUtils;

public class RakClientOfflineHandlerCompatible extends RakClientOfflineHandler {
    public static final String NAME = "rak-client-handler";

    public RakClientOfflineHandlerCompatible(RakChannel rakChannel, ChannelPromise promise) {
        super(rakChannel, promise);
    }

    @Override
    void onRetryAttempt(Channel channel) {
        if (this.state() == RakOfflineState.HANDSHAKE_COMPLETED) {
            return;
        }
        this.sendOpenConnectionRequest1(channel);
        this.incrementConnectionAttempts();
    }

    @Override
    void onSuccess(ChannelHandlerContext ctx) {
        RakSessionCodec sessionCodec = new RakSessionCodecCompatible(this.rakChannel());
        ctx.pipeline().addAfter(NAME, RakDatagramCodec.NAME, new RakDatagramCodec());
        ctx.pipeline().addAfter(RakDatagramCodec.NAME, RakAcknowledgeHandler.NAME, new RakAcknowledgeHandler(sessionCodec));
        ctx.pipeline().addAfter(RakAcknowledgeHandler.NAME, RakSessionCodec.NAME, sessionCodec);
        // Ensure new incoming connection batches with request network settings game packet
        ctx.pipeline().addAfter(RakSessionCodec.NAME, RakClientNetworkSettingsHandler.NAME, new RakClientNetworkSettingsHandler(this.rakChannel()));
        ctx.pipeline().addAfter(RakSessionCodec.NAME, ConnectedPingHandler.NAME, new ConnectedPingHandler());
        ctx.pipeline().addAfter(ConnectedPingHandler.NAME, ConnectedPongHandler.NAME, new ConnectedPongHandler(sessionCodec));
        ctx.pipeline().addAfter(ConnectedPongHandler.NAME, DisconnectNotificationHandler.NAME, DisconnectNotificationHandler.INSTANCE);
        // Replicate server behavior, and transform unhandled encapsulated packets to rakMessage
        ctx.pipeline().addAfter(DisconnectNotificationHandler.NAME, EncapsulatedToMessageHandler.NAME, EncapsulatedToMessageHandler.INSTANCE);
        ctx.pipeline().addAfter(DisconnectNotificationHandler.NAME, RakClientOnlineInitialHandlerCompatible.NAME, new RakClientOnlineInitialHandlerCompatible(this.rakChannel(), this.successPromise()));
    }

    @Override
    void onOpenConnectionReply2(ChannelHandlerContext ctx, ByteBuf buffer) {
        buffer.readLong(); // serverGuid
        RakUtils.skipAddress(buffer); // serverAddress

        int mtu = buffer.readShort();
        buffer.skipBytes(1); // security (ignored by vanilla client)

        this.rakChannel().config().setOption(RakChannelOption.RAK_MTU, mtu);
        this.state(RakOfflineState.HANDSHAKE_COMPLETED);
    }
}
