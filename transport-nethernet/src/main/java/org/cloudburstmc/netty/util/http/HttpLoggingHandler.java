package org.cloudburstmc.netty.util.http;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.internal.logging.InternalLogger;

/**
 * Log any web requests and their response codes and times to a given Netty log
 */
public class HttpLoggingHandler extends ChannelDuplexHandler {
    private final InternalLogger log;

    private long startNanos;
    private String request;

    public HttpLoggingHandler(InternalLogger log) {
        this.log = log;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            startNanos = System.nanoTime();
            request = ctx.channel().remoteAddress() + " " + req.method() + " " + req.uri();
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof HttpResponse res) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.debug("{} -> {} ({} ms)", request, res.status().code(), ms);
        }
        ctx.write(msg, promise);
    }
}
