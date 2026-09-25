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
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings.Scheme;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Serves on the loopback name, which is what a certificate can be issued for. */
    private InetSocketAddress serve(NetherNetHTTPServerSignaling.Builder builder) throws Exception {
        InetAddress loopback = InetAddress.getByName("localhost");
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
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
                .bind(new InetSocketAddress(loopback, port)).sync().channel();
        return new InetSocketAddress("localhost", port);
    }

    private NetherNetHTTPServerSignaling.Builder plaintextServer() throws Exception {
        return new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("host.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY);
    }

    private SelfSignedCertificate certificate;

    private NetherNetHTTPServerSignaling.Builder tlsServer() throws Exception {
        this.certificate = new SelfSignedCertificate("localhost");
        return this.plaintextServer().setSslContext(
                SslContextBuilder.forServer(this.certificate.certificate(), this.certificate.privateKey()).build());
    }

    /** Settings that trust the test server's certificate, as a private CA would be trusted. */
    private HttpSignalingSettings trusting() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("test", this.certificate.cert());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return HttpSignalingSettings.DEFAULT.withSslContext(context);
    }

    private static OperatorIdentity player() throws Exception {
        return OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester");
    }

    private Bootstrap client(OperatorIdentity identity) {
        return this.client(identity, null, HttpSignalingSettings.DEFAULT);
    }

    private Bootstrap client(OperatorIdentity identity, TokenTrust serverTrust) {
        return this.client(identity, serverTrust, HttpSignalingSettings.DEFAULT);
    }

    private Bootstrap client(OperatorIdentity identity, TokenTrust serverTrust, HttpSignalingSettings settings) {
        Bootstrap bootstrap = new Bootstrap().group(this.group)
                .channelFactory(NetherNetChannelFactory.client(new NetherNetHTTPClientSignaling(settings)))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 20_000)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 1)
                .handler(new ChannelInboundHandlerAdapter());
        if (identity != null) {
            bootstrap.option(NetherChannelOption.NETHER_CLIENT_IDENTITY, identity);
        }
        if (serverTrust != null) {
            bootstrap.option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, serverTrust);
        }
        return bootstrap;
    }

    @Test
    void confirmsTheServerItWasPinnedTo() throws Exception {
        OperatorIdentity host = OperatorIdentity.generate("host.test");
        InetSocketAddress endpoint = this.serve(new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(host)
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY));
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester");

        this.client = this.client(player, TokenTrust.pinnedTo(host.publicKey())).connect(endpoint).sync().channel();

        assertTrue(this.client.isActive(), "the data channel opened");
        assertEquals("2535000000000001", this.admitted.get(10, TimeUnit.SECONDS).xuid());
    }

    @Test
    void confirmsAServerWhoseIdentityCameFromAFileThroughTheKeysTextForm(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("identity.pem");
        Files.writeString(pem, NetherNetHTTPServerSignalingBuilderTest.PEM);
        InetSocketAddress endpoint = this.serve(new NetherNetHTTPServerSignaling.Builder()
                .setIdentityPem(pem.toFile(), "host.test")
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY));
        // The key the operator copies into the client's config, as the file's owner would print it
        String configured = IdentityUtils.encodePublicKey(this.signaling.serverIdentity().publicKey());
        PublicKey pinned = IdentityUtils.decodePublicKey(configured);
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester");

        this.client = this.client(player, TokenTrust.pinnedTo(pinned)).connect(endpoint).sync().channel();

        assertTrue(this.client.isActive(), "the data channel opened");
        assertEquals("2535000000000001", this.admitted.get(10, TimeUnit.SECONDS).xuid());
    }

    @Test
    void refusesAServerAnsweringWithAnotherIdentity() throws Exception {
        InetSocketAddress endpoint = this.serve(new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("host.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY));
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("2535000000000001", "Tester");
        TokenTrust expected = TokenTrust.pinnedTo(OperatorIdentity.generate("host.test").publicKey());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> this.client(player, expected).connect(endpoint).get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("identity was refused"), cause.getMessage());
        assertFalse(this.admitted.isDone(), "no data channel opened toward the impostor");
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
    void speaksHttpsWhenTheServerServesIt() throws Exception {
        // The server refuses plaintext, so an admitted join can only have gone over HTTPS
        InetSocketAddress endpoint = this.serve(this.tlsServer());

        this.client = this.client(player(), null, this.trusting()).connect(endpoint).sync().channel();

        assertTrue(this.client.isActive(), "the data channel opened");
        assertEquals("2535000000000001", this.admitted.get(10, TimeUnit.SECONDS).xuid());
    }

    @Test
    void saysWhyWhenTheServerRequiresTlsItCannotTrust() throws Exception {
        InetSocketAddress endpoint = this.serve(this.tlsServer());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> this.client(player()).connect(endpoint).get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("requires TLS, and HTTPS failed"), cause.getMessage());
        assertFalse(this.admitted.isDone());
    }

    @Test
    void neverFallsBackToPlaintextWhenHttpsIsRequired() throws Exception {
        AtomicInteger offers = new AtomicInteger();
        InetSocketAddress endpoint = this.serve(this.plaintextServer().setPlayerFilter((host, player) -> {
            offers.incrementAndGet();
            return null;
        }));
        HttpSignalingSettings required = HttpSignalingSettings.DEFAULT.withScheme(Scheme.HTTPS);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> this.client(player(), null, required).connect(endpoint).get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("TLS failed"), cause.getMessage());
        assertEquals(0, offers.get(), "no offer reached the server in plaintext");
    }

    @Test
    void isRefusedWhenPlaintextIsForcedAgainstATlsServer() throws Exception {
        InetSocketAddress endpoint = this.serve(this.tlsServer());
        HttpSignalingSettings plaintext = HttpSignalingSettings.DEFAULT.withScheme(Scheme.HTTP);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> this.client(player(), null, plaintext).connect(endpoint).get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("HTTP 426"), cause.getMessage());
    }

    @Test
    void probeReportsTheSchemeAndTheStatus() throws Exception {
        InetSocketAddress endpoint = this.serve(this.plaintextServer()
                .setMotd(new PongData.Builder().setServerName("Probe target").build()));

        NetherNetHTTPClientSignaling.Probe probe = NetherNetHTTPClientSignaling
                .probe(endpoint, HttpSignalingSettings.DEFAULT).get(10, TimeUnit.SECONDS);

        assertEquals(Scheme.HTTP, probe.scheme());
        assertEquals("Probe target", probe.motd().serverName());
    }

    @Test
    void probeFindsTlsFirst() throws Exception {
        InetSocketAddress endpoint = this.serve(this.tlsServer());

        NetherNetHTTPClientSignaling.Probe probe = NetherNetHTTPClientSignaling
                .probe(endpoint, this.trusting()).get(10, TimeUnit.SECONDS);

        assertEquals(Scheme.HTTPS, probe.scheme());
    }

    @Test
    void probeFailsAtOnceWhenNothingAnswers() throws Exception {
        InetSocketAddress nobody;
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
            nobody = new InetSocketAddress("localhost", taken.getLocalPort());
        }

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> NetherNetHTTPClientSignaling.probe(nobody, HttpSignalingSettings.DEFAULT)
                        .get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("could not be reached: connection refused"), cause.getMessage());
    }

    @Test
    void probeFailsWhenTheServerDoesNotServeNetherNet() throws Exception {
        InetSocketAddress endpoint = this.serve(this.plaintextServer().setMotdProvider((host, remote) -> null));

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> NetherNetHTTPClientSignaling.probe(endpoint, HttpSignalingSettings.DEFAULT)
                        .get(10, TimeUnit.SECONDS));

        ConnectException cause = assertInstanceOf(ConnectException.class, refused.getCause());
        assertTrue(cause.getMessage().contains("does not serve NetherNet"), cause.getMessage());
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
