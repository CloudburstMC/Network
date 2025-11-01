package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.util.RakUtils;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakClientOnlineInitialHandlerCompatible extends RakClientOnlineInitialHandler {
    public static final String NAME = "rak-client-online-initial-handler";

    private long pingTime = 0;

    public RakClientOnlineInitialHandlerCompatible(RakChannel rakChannel, ChannelPromise promise) {
        super(rakChannel, promise);
    }

    @Override
    void onSuccess(ChannelHandlerContext ctx) {
        super.onSuccess(ctx);

        ByteBuf incomingBuffer = ctx.alloc().ioBuffer();
        this.writeIncomingConnection(ctx, incomingBuffer, pingTime);
        ctx.write(new RakMessage(incomingBuffer, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL));

        ByteBuf pingBuffer = ctx.alloc().ioBuffer();
        pingBuffer.writeByte(ID_CONNECTED_PING);
        pingBuffer.writeLong(System.currentTimeMillis());
        ctx.write(new RakMessage(pingBuffer, RakReliability.UNRELIABLE, RakPriority.NORMAL));

        ByteBuf netSettingsBuffer = ctx.channel().attr(RakClientNetworkSettingsHandler.NETWORK_SETTINGS_PAYLOAD).get();
        if (netSettingsBuffer == null) {
            netSettingsBuffer = ctx.alloc().ioBuffer();
            netSettingsBuffer.writeByte(ID_GAME_PACKET);
            int rakVersion = this.rakChannel().config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
            switch (rakVersion) {
                case 11:
                case 10:
                case 9:
                    netSettingsBuffer.writeByte(0x06); // length
                    netSettingsBuffer.writeByte(0xc1).writeByte(0x01); // header
                    break;
                case 8:
                    netSettingsBuffer.writeByte(0x07); // length
                    netSettingsBuffer.writeByte(0xc1).writeByte(0x00).writeByte(0x00); // header
                    break;
                case 7:
                    netSettingsBuffer.writeByte(0x05); // length
                    netSettingsBuffer.writeByte(0xc1); // header
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported protocol version: " + rakVersion);
            }
            netSettingsBuffer.writeInt(this.rakChannel().config().getOption(RakChannelOption.RAK_CLIENT_BEDROCK_PROTOCOL_VERSION));
        }
        ctx.write(new RakMessage(netSettingsBuffer, RakReliability.RELIABLE_ORDERED, RakPriority.NORMAL));

        ctx.flush();
    }

    @Override
    void onConnectionRequestAccepted(ChannelHandlerContext ctx, ByteBuf buf) {
        buf.skipBytes(1);
        RakUtils.skipAddress(buf);
        buf.skipBytes(2); // System index

        while (buf.isReadable(IPV4_MESSAGE_SIZE + 16)) {
            RakUtils.skipAddress(buf);
        }
        this.pingTime = buf.readLong();
        return;
    }
}
