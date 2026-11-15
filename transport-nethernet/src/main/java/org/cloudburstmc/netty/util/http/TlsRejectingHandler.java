/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

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
    /** Whether Netty has isEncrypted(ByteBuf, boolean), from 4.1.113. Platforms bring their own Netty. */
    private static final boolean SSLV2_PROBE_OPTIONAL = hasOverload();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // Make sure we have enough bytes to check if this is TLS
        if (in.readableBytes() < 5) {
            return;
        }

        if (isTls(in)) {
            ctx.close();
            return;
        }

        ctx.pipeline().remove(this);
    }

    @SuppressWarnings("deprecation")
    private static boolean isTls(ByteBuf in) {
        // Older Netty also probes for SSLv2, which no HTTP request resembles
        return SSLV2_PROBE_OPTIONAL ? SslHandler.isEncrypted(in, false) : SslHandler.isEncrypted(in);
    }

    private static boolean hasOverload() {
        try {
            SslHandler.class.getMethod("isEncrypted", ByteBuf.class, boolean.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
