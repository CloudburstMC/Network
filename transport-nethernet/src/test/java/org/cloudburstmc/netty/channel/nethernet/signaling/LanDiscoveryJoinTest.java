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
import io.netty.channel.ChannelFuture;
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
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.util.nethernet.Identity;
import org.jose4j.jwt.JwtClaims;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Joins over LAN discovery, the way a console joins an entry in its LAN list. */
@Timeout(30)
class LanDiscoveryJoinTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private final CompletableFuture<NetherNetChildChannel> joined = new CompletableFuture<>();
    private NetherNetDiscoverySignaling server;
    private Channel serverChannel;
    private Channel client;

    @AfterEach
    void close() throws InterruptedException {
        if (client != null) {
            client.close().sync();
        }
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    @Test
    void aJoinWithAnIdentityCarriesItsPlayer() throws Exception {
        startServer(TokenTrust.ANY, null);

        client = join(OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester"), null)
                .sync().channel();

        NetherNetChildChannel child = joined.get(10, TimeUnit.SECONDS);
        PlayerInfo player = child.attr(NetherNetChildChannel.PLAYER_INFO).get();
        assertNotNull(player);
        assertEquals("2535000000000001", player.xuid());
        assertEquals(TransportIdentityBinding.State.PENDING, TransportIdentityBinding.state(child));
    }

    @Test
    void aJoinWithoutAnIdentityIsLeftToTheHost() throws Exception {
        startServer(TokenTrust.ANY, null);

        client = join(null, null).sync().channel();

        NetherNetChildChannel child = joined.get(10, TimeUnit.SECONDS);
        assertNull(child.attr(NetherNetChildChannel.PLAYER_INFO).get());
        assertEquals(TransportIdentityBinding.State.NONE, TransportIdentityBinding.state(child));
    }

    @Test
    void aJoinWhoseIdentityDoesNotHoldIsDropped() throws Exception {
        startServer(TokenTrust.pinnedTo(OperatorIdentity.generate("other.test").publicKey()), null);

        ChannelFuture attempt = join(OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester"),
                null);

        assertTrue(attempt.await(10, TimeUnit.SECONDS), "the join never settled");
        assertFalse(attempt.isSuccess(), "the join got in");
        assertFalse(joined.isDone(), "the server accepted the join");
    }

    @Test
    void answersAreSignedWithTheHostsIdentity() throws Exception {
        OperatorIdentity host = OperatorIdentity.generate("host.test");
        startServer(TokenTrust.ANY, host);

        client = join(null, TokenTrust.pinnedTo(host.publicKey())).sync().channel();

        assertNotNull(joined.get(10, TimeUnit.SECONDS));
    }

    @Test
    void anIdentityIsCheckedOffTheDiscoveryLoopWhileTheJoinsSignalsWait() throws Exception {
        CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
        NetherNetDiscoverySignaling host = new NetherNetDiscoverySignaling(1, new InetSocketAddress(LOOPBACK, 0));
        NetherNetDiscovery peer = new NetherNetDiscovery(2);
        try {
            // A trust anchor fetching its keys, which used to stall discovery for every peer
            host.setTokenTrust(identity -> {
                checking.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return TokenTrust.ANY.claims(identity);
            });
            host.setAdvertisementData(PongData.DEFAULT);
            CompletableFuture<PlayerInfo> connected = new CompletableFuture<>();
            List<String> signals = new CopyOnWriteArrayList<>();
            host.setNewConnectionHandler((connectionId, remoteNetworkId, offer, address, player) -> {
                host.setSignalHandler(connectionId, signals::add);
                connected.complete(player);
            });
            host.bind(new InetSocketAddress(LOOPBACK, 0), null, group.next());
            peer.bind(new InetSocketAddress(LOOPBACK, 0));
            InetSocketAddress address = host.localAddress();

            String offer = OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester")
                    .withAssertion("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
                            + "a=fingerprint:sha-256 " + "AA:".repeat(31) + "AA\r\n"
                            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
            peer.sendSignal(address, 1, NetherNetConstants.buildSignalConnectRequest("7", offer));
            assertTrue(checking.await(10, TimeUnit.SECONDS), "the identity was never checked");
            String candidate = NetherNetConstants.buildSignalCandidateAdd("7", "candidate:1 1 udp 1 127.0.0.1 9 typ host");
            peer.sendSignal(address, 1, candidate);

            // Discovery still answers while the check waits, after handling the candidate sent ahead of it
            CompletableFuture<Long> pong = new CompletableFuture<>();
            peer.sendDiscoveryRequest(address, (networkId, payload) -> {
                ReferenceCountUtil.release(payload);
                pong.complete(networkId);
            });
            assertEquals(1L, pong.get(5, TimeUnit.SECONDS));
            assertFalse(connected.isDone());

            release.countDown();
            assertEquals("2535000000000001", connected.get(10, TimeUnit.SECONDS).xuid());
            // The candidate that came while the identity was checked reaches the connection
            for (int i = 0; i < 100 && signals.isEmpty(); i++) {
                Thread.sleep(20);
            }
            assertEquals(List.of(candidate), signals);
        } finally {
            release.countDown();
            peer.close();
            host.close();
        }
    }

    @Test
    void aTrustSetAfterBindingFetchesItsKeysAhead() throws Exception {
        NetherNetDiscoverySignaling host = new NetherNetDiscoverySignaling(1, new InetSocketAddress(LOOPBACK, 0));
        try {
            // Fetches nothing, so the trust set after binding is the only one preparing
            host.setTokenTrust(TokenTrust.ANY);
            host.bind(new InetSocketAddress(LOOPBACK, 0), null, group.next());
            CountDownLatch prepared = new CountDownLatch(1);
            host.setTokenTrust(new TokenTrust() {
                @Override
                public JwtClaims claims(Identity identity) throws Exception {
                    return TokenTrust.ANY.claims(identity);
                }

                @Override
                public void prepare() {
                    prepared.countDown();
                }
            });
            assertTrue(prepared.await(5, TimeUnit.SECONDS), "the new trust never prepared");
        } finally {
            host.close();
        }
    }

    private void startServer(TokenTrust trust, OperatorIdentity identity) throws InterruptedException {
        server = new NetherNetDiscoverySignaling(1, new InetSocketAddress(LOOPBACK, 0));
        server.setTokenTrust(trust);
        server.setServerIdentity(identity);
        server.setAdvertisementData(PongData.DEFAULT);
        serverChannel = new ServerBootstrap().group(group)
                .channelFactory(NetherNetChannelFactory.server(server))
                // ICE skips loopback unless bound to it, and a test host may have nothing else
                .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, new InetSocketAddress(LOOPBACK, 0))
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        joined.complete((NetherNetChildChannel) ctx.channel());
                        ctx.fireChannelActive();
                    }
                })
                .bind(LOOPBACK, 0).sync().channel();
    }

    /** Connects a client to the server's discovery socket, as picking its LAN entry does. */
    private ChannelFuture join(OperatorIdentity identity, TokenTrust serverTrust) {
        Bootstrap bootstrap = new Bootstrap().group(group)
                .channelFactory(NetherNetChannelFactory.client(
                        new NetherNetDiscoverySignaling(2, new InetSocketAddress(LOOPBACK, 0))))
                .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG,
                        PeerConnectionConfiguration.DEFAULT.withBindAddress(LOOPBACK))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 5_000)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 1)
                .handler(new ChannelInboundHandlerAdapter());
        if (identity != null) {
            bootstrap.option(NetherChannelOption.NETHER_CLIENT_IDENTITY, identity);
        }
        if (serverTrust != null) {
            bootstrap.option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, serverTrust);
        }
        return bootstrap.connect(server.localAddress());
    }
}
