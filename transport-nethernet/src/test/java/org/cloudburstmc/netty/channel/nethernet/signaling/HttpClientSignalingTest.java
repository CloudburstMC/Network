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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole outbound leg on loopback: a client channel gathers, signs its offer for a player,
 * posts it through the HTTP client signaling, and the server endpoint admits it with that player.
 */
class HttpClientSignalingTest {

    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final CompletableFuture<PlayerInfo> admitted = new CompletableFuture<>();
    private NetherNetHTTPServerSignaling signaling;
    private Channel server;
    private Channel client;

    @AfterEach
    void tearDown() throws Exception {
        if (this.client != null) {
            this.client.close().sync();
        }
        if (this.server != null) {
            this.server.close().sync();
        }
        if (this.signaling != null) {
            this.signaling.close();
        }
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    private InetSocketAddress serve(NetherNetHTTPServerSignaling.Builder builder) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        this.signaling = builder.build();
        this.server = new ServerBootstrap().group(this.group)
                .channelFactory(NetherNetChannelFactory.server(this.signaling))
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        admitted.complete(ctx.channel().attr(NetherNetChildChannel.PLAYER_INFO).get());
                        ctx.fireChannelActive();
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", port)).sync().channel();
        return new InetSocketAddress("127.0.0.1", port);
    }

    private Bootstrap client(OperatorIdentity identity) {
        Bootstrap bootstrap = new Bootstrap().group(this.group)
                .channelFactory(NetherNetChannelFactory.client(new NetherNetHTTPClientSignaling()))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 20_000)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 1)
                .handler(new ChannelInboundHandlerAdapter());
        if (identity != null) {
            bootstrap.option(NetherChannelOption.NETHER_CLIENT_IDENTITY, identity);
        }
        return bootstrap;
    }

    @Test
    void connectsAndIsAdmittedAsThePlayerItSignedFor() throws Exception {
        InetSocketAddress endpoint = this.serve(new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("host.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY));
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester");

        this.client = this.client(player).connect(endpoint).sync().channel();

        assertTrue(this.client.isActive(), "the data channel opened");
        PlayerInfo info = this.admitted.get(10, TimeUnit.SECONDS);
        assertEquals("2535000000000001", info.xuid());
        assertEquals("Tester", info.displayName());
        assertEquals("proxy.test", info.claims().getIssuer());
    }

    @Test
    void reportsWhyTheServerRefusedTheOffer() throws Exception {
        InetSocketAddress endpoint = this.serve(new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("host.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY));

        // No identity, which a validating endpoint answers with 401 rather than an SDP
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> this.client(null).connect(endpoint).get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("HTTP 401"), cause.getMessage());
        assertTrue(cause.getMessage().contains(endpoint.toString()), cause.getMessage());
        assertFalse(this.admitted.isDone());
    }

    @Test
    void aSignalingServesOneConnectionAndSaysSoAfterwards() throws Exception {
        NetherNetHTTPClientSignaling signaling = new NetherNetHTTPClientSignaling();
        signaling.close();

        ExecutionException spent = assertThrows(ExecutionException.class,
                () -> signaling.connect(new InetSocketAddress("127.0.0.1", 1)).get());

        assertInstanceOf(IllegalStateException.class, spent.getCause());
    }
}
