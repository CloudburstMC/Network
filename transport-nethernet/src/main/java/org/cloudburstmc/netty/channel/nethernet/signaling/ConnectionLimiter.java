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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Caps how many connections one address may hold open at once.
 * <p>
 * Anyone on the internet can reach this endpoint, and a kept connection costs a socket until
 * it goes idle, so without a cap a single peer can hold as many as the host has descriptors.
 * A trusted reverse proxy is exempt, since every client behind it shares its address and
 * counting them together would throttle all of them at once.
 */
final class ConnectionLimiter extends ChannelInboundHandlerAdapter {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(ConnectionLimiter.class);

    private final IpRangeSet trustedProxies;
    private final int maxConnectionsPerAddress;
    private final Map<InetAddress, Integer> connectionsPerAddress;
    private final Supplier<NetherServerMetrics> metrics;
    private InetAddress counted;

    /**
     * @param connectionsPerAddress The counts, shared by every connection of one endpoint
     * @param metrics               Where a refusal is reported, read at the time of one
     */
    ConnectionLimiter(IpRangeSet trustedProxies, int maxConnectionsPerAddress,
                      Map<InetAddress, Integer> connectionsPerAddress, Supplier<NetherServerMetrics> metrics) {
        this.trustedProxies = trustedProxies;
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
        this.connectionsPerAddress = connectionsPerAddress;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        InetAddress peer = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
        if (trustedProxies.contains(peer)) {
            ctx.fireChannelActive();
            return;
        }

        if (connectionsPerAddress.merge(peer, 1, Integer::sum) > maxConnectionsPerAddress) {
            release(peer);
            log.debug("Refused a connection from {}, already holding {}", peer, maxConnectionsPerAddress);
            NetherServerMetrics metrics = this.metrics.get();
            if (metrics != null) {
                metrics.addressRefused((InetSocketAddress) ctx.channel().remoteAddress());
            }
            ctx.close();
            return;
        }
        this.counted = peer;
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (this.counted != null) {
            release(this.counted);
            this.counted = null;
        }
        ctx.fireChannelInactive();
    }

    private void release(InetAddress peer) {
        connectionsPerAddress.computeIfPresent(peer, (address, held) -> held <= 1 ? null : held - 1);
    }
}
