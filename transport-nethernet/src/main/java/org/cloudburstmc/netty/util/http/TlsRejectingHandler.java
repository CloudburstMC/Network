package org.cloudburstmc.netty.util.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslHandler;

import java.util.List;

/**
 * Rejects TLS clients on a plaintext port
 */
public class TlsRejectingHandler extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Make sure we have enough bytes to check if this is TLS
        if (in.readableBytes() < 5) {
            return;
        }

        if (SslHandler.isEncrypted(in, false)) {
            ctx.close();
            return;
        }

        // Allow the pipeline to continue
        ctx.pipeline().remove(this);
    }
}
