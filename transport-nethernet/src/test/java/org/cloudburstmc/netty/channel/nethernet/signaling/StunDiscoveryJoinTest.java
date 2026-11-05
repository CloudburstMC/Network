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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Discovery against a STUN server on loopback that reports a public mapping, as one would for a
 * host behind NAT, and a real join whose answer has to carry it.
 */
class StunDiscoveryJoinTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final InetSocketAddress MAPPED = new InetSocketAddress("11.22.33.44", 40404);
    /** One for every join, as each client keeps its connection, and signaling takes 8 per address. */
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final EventLoopGroup group = new NioEventLoopGroup(2);
    private Channel server;

    @AfterEach
    void tearDown() throws Exception {
        if (this.server != null) {
            this.server.close().sync();
        }
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    private static NetherNetHTTPServerSignaling.Builder signaling(StunServer stun) throws Exception {
        return new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("host.test"))
                .setTokenTrust(TokenTrust.ANY)
                .setStunServers(List.of("stun:127.0.0.1:" + stun.socket.getLocalPort()));
    }

    /** Sharable, since every join adds it to a child of its own. */
    private static final ChannelHandler CHILD = new ChannelInitializer<>() {
        @Override
        protected void initChannel(Channel channel) {
        }
    };

    private ChannelFuture bind(NetherNetHTTPServerSignaling signaling, int port) {
        return new ServerBootstrap().group(this.group)
                .channelFactory(NetherNetChannelFactory.server(signaling))
                .childHandler(CHILD)
                .bind(new InetSocketAddress(LOOPBACK, port));
    }

    @Test
    void answersCarryTheMappingFoundOnTheIcePort() throws Exception {
        try (StunServer stun = new StunServer()) {
            int port = freePort();
            this.server = bind(signaling(stun).build(), port).sync().channel();

            // Asked from the port the peers use, which is the mapping a player can reach
            assertEquals(port, stun.firstRequest.get(10, TimeUnit.SECONDS).getPort());

            String answer = answerOnceDiscovered(port);
            assertTrue(answer.contains(" 127.0.0.1 " + port + " typ host"),
                    "the peer shares the socket the monitor opened first: " + answer);
            assertTrue(answer.contains(" 11.22.33.44 40404 typ srflx raddr 127.0.0.1 rport " + port), answer);
            // The router chose its own port here, so the ICE port is announced on that address too
            assertTrue(answer.contains(" 11.22.33.44 " + port + " typ srflx raddr 127.0.0.1 rport " + port), answer);
        }
    }

    @Test
    void reportsTheMappingItAnnounces() throws Exception {
        try (StunServer stun = new StunServer()) {
            int port = freePort();
            NetherNetHTTPServerSignaling signaling = signaling(stun).build();
            assertEquals(List.of(), signaling.discoveredEndpoints());
            this.server = bind(signaling, port).sync().channel();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DiscoveredEndpoints.REFRESH_SECONDS + 10);
            while (signaling.discoveredEndpoints().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(List.of(MAPPED, new InetSocketAddress(MAPPED.getAddress(), port)),
                    signaling.discoveredEndpoints());
            assertEquals(List.of(), signaling.advertisedAddresses());
        }
    }

    @Test
    void refusesToBindWithoutAFixedIcePort() throws Exception {
        try (StunServer stun = new StunServer()) {
            ChannelFuture bound = bind(signaling(stun).setIceOnLocalPort(false).build(), freePort()).await();

            assertInstanceOf(IOException.class, bound.cause());
            assertTrue(bound.cause().getMessage().contains("fixed port"), bound.cause().getMessage());
        }
    }

    @Test
    void anUnresolvedIceAddressDiscoversOnTheWildcard() throws Exception {
        try (StunServer stun = new StunServer()) {
            int icePort = freePort();
            this.server = new ServerBootstrap().group(this.group)
                    .channelFactory(NetherNetChannelFactory.server(signaling(stun).build()))
                    .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS,
                            InetSocketAddress.createUnresolved("ice.test", icePort))
                    .childHandler(CHILD)
                    .bind(new InetSocketAddress(LOOPBACK, freePort())).sync().channel();

            assertEquals(icePort, stun.firstRequest.get(10, TimeUnit.SECONDS).getPort());
        }
    }

    /** Joins until an answer carries the mapping, which lands with the first refresh after the bind. */
    private static String answerOnceDiscovered(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DiscoveredEndpoints.REFRESH_SECONDS + 10);
        for (int attempt = 0; ; attempt++) {
            String answer = join(port, "4200" + attempt);
            if (answer.contains(" typ srflx") || System.nanoTime() > deadline) {
                return answer;
            }
            Thread.sleep(500);
        }
    }

    private static String join(int port, String networkId) throws Exception {
        try (PeerConnection client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT
                .withBindAddress(LOOPBACK))) {
            CountDownLatch gathered = new CountDownLatch(1);
            client.onGatheringStateChange.register((peer, state) -> {
                if (state == GatheringState.RTC_GATHERING_COMPLETE) {
                    gathered.countDown();
                }
            });
            client.createDataChannel("probe");
            client.setLocalDescription(null);
            assertTrue(gathered.await(10, TimeUnit.SECONDS), "the client should gather");

            String offer = OperatorIdentity.generate("client.test").forPlayer("2535000000000001", "Tester")
                    .withAssertion(client.localDescription());
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/join/" + networkId))
                            .POST(HttpRequest.BodyPublishers.ofString(offer)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                fail("the join was refused with " + response.statusCode());
            }
            return response.body();
        }
    }

    /** A port free for TCP and for UDP on every address, as signaling and ICE may share it. */
    private static int freePort() throws IOException {
        while (true) {
            try (ServerSocket tcp = new ServerSocket(0, 1, LOOPBACK);
                 DatagramSocket udp = new DatagramSocket(new InetSocketAddress(tcp.getLocalPort()))) {
                return udp.getLocalPort();
            } catch (BindException taken) {
                // UDP has that one, such as the STUN server's socket
            }
        }
    }

    /** Answers every binding request with {@link #MAPPED}, whoever asked. */
    private static final class StunServer implements AutoCloseable {
        final DatagramSocket socket = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        final CompletableFuture<InetSocketAddress> firstRequest = new CompletableFuture<>();

        StunServer() throws IOException {
            Thread thread = new Thread(this::serve, "test-stun");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try {
                while (!this.socket.isClosed()) {
                    byte[] data = new byte[2048];
                    DatagramPacket request = new DatagramPacket(data, data.length);
                    this.socket.receive(request);
                    if (request.getLength() < 20 || data[0] != 0 || data[1] != 1) {
                        continue;
                    }
                    this.firstRequest.complete(new InetSocketAddress(request.getAddress(), request.getPort()));

                    byte[] address = MAPPED.getAddress().getAddress();
                    ByteBuffer response = ByteBuffer.allocate(28 + address.length);
                    // Binding success echoing the transaction, with one XOR-MAPPED-ADDRESS
                    response.putShort((short) 0x0101).putShort((short) (8 + address.length))
                            .putInt(0x2112a442).put(data, 8, 12);
                    response.putShort((short) 0x0020).putShort((short) (4 + address.length))
                            .put((byte) 0).put((byte) (address.length == 4 ? 1 : 2))
                            .putShort((short) (MAPPED.getPort() ^ 0x2112));
                    byte[] mask = Arrays.copyOfRange(response.array(), 4, 20);
                    for (int i = 0; i < address.length; i++) {
                        response.put((byte) (address[i] ^ mask[i]));
                    }
                    this.socket.send(new DatagramPacket(response.array(), response.position(),
                            request.getSocketAddress()));
                }
            } catch (IOException e) {
                if (!this.socket.isClosed()) {
                    this.firstRequest.completeExceptionally(e);
                }
            }
        }

        @Override
        public void close() {
            this.socket.close();
        }
    }
}
