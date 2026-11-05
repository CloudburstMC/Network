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
package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.ProtocolDetectionState;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Reads a PROXY header from a trusted proxy, and steps aside for anything else.
 */
final class OptionalProxyProtocol extends ByteToMessageDecoder {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(OptionalProxyProtocol.class);

    /** The source a trusted proxy declared in its PROXY header. */
    static final AttributeKey<InetSocketAddress> PROXIED_SOURCE =
            AttributeKey.valueOf(OptionalProxyProtocol.class, "proxiedSource");

    private final IpRangeSet trustedProxies;

    OptionalProxyProtocol(IpRangeSet trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        InetSocketAddress peer = (InetSocketAddress) ctx.channel().remoteAddress();
        if (!trustedProxies.contains(peer)) {
            ctx.pipeline().remove(this);
            return;
        }

        ProtocolDetectionResult<HAProxyProtocolVersion> detected = HAProxyMessageDecoder.detectProtocol(in);
        if (detected.state() == ProtocolDetectionState.NEEDS_MORE_DATA) {
            return;
        }
        if (detected.state() == ProtocolDetectionState.INVALID) {
            // A trusted proxy is allowed to speak plain HTTP too
            ctx.pipeline().remove(this);
            return;
        }

        ctx.pipeline().addAfter(ctx.name(), null, new SimpleChannelInboundHandler<HAProxyMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext inner, HAProxyMessage message) {
                if (message.sourceAddress() != null) {
                    inner.channel().attr(PROXIED_SOURCE)
                            .set(new InetSocketAddress(message.sourceAddress(), message.sourcePort()));
                    log.debug("Got PROXY header: (from " + peer + ") " + message.sourceAddress());
                }
                inner.pipeline().remove(this);
            }
        });
        ctx.pipeline().replace(this, null, new HAProxyMessageDecoder());
    }
}
