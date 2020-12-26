package org.cloudburstmc.netty.handler.codec.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.RakMessage;

public class RakServerOnlineConnectedHandler extends SimpleChannelInboundHandler<RakMessage> {

    public static final RakServerOnlineConnectedHandler INSTANCE = new RakServerOnlineConnectedHandler();
    public static final String NAME = "rak-server-online-connected-handler";

    private RakServerOnlineConnectedHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RakMessage msg) throws Exception {

    }
}
