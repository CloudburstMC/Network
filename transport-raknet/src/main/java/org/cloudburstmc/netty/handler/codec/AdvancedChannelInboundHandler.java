package org.cloudburstmc.netty.handler.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.TypeParameterMatcher;

public abstract class AdvancedChannelInboundHandler<T> extends ChannelInboundHandlerAdapter {
    private final TypeParameterMatcher matcher;

    public AdvancedChannelInboundHandler() {
        this.matcher = TypeParameterMatcher.find(this, AdvancedChannelInboundHandler.class, "T");
    }

    public AdvancedChannelInboundHandler(Class<? extends T> inboundMessageType) {
        this.matcher = TypeParameterMatcher.get(inboundMessageType);
    }

    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        return this.matcher.match(msg);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean release = true;

        try {
            if (this.acceptInboundMessage(ctx, msg)) {
                this.channelRead0(ctx, (T) msg);
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

    protected abstract void channelRead0(ChannelHandlerContext ctx, T msg) throws Exception;
}
