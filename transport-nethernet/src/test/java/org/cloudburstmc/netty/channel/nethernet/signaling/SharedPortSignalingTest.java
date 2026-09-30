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
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Signaling served on connections a host accepted itself, as on a port shared with another protocol.
 */
class SharedPortSignalingTest {

    // One thread runs the host, its connections and the signaling, so whatever a request set off has
    // finished by the time a later one is answered
    private final EventLoopGroup group = new NioEventLoopGroup(1);
    /** The client address of every status check that reached the provider. */
    private final BlockingQueue<InetSocketAddress> served = new LinkedBlockingQueue<>();
    private NetherNetHTTPServerSignaling signaling;
    private Channel host;
    private int port;

    @AfterEach
    void tearDown() {
        if (host != null) {
            host.close().syncUninterruptibly();
        }
        if (signaling != null) {
            signaling.close();
        }
        group.shutdownGracefully();
    }

    private NetherNetHTTPServerSignaling.Builder builder() throws Exception {
        return new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("example.test"))
                .setServeHttp(false)
                .setMotdProvider((hostName, remoteAddress, client) -> {
                    served.add(remoteAddress);
                    return PongData.DEFAULT;
                });
    }

    /**
     * Starts a host that reads a connection's first bytes before handing it to signaling, as one
     * telling two protocols apart does, so the channel is active and holds unread bytes by then.
     */
    private void start(NetherNetHTTPServerSignaling.Builder builder) throws Exception {
        signaling = builder.build();
        signaling.bind(new InetSocketAddress("127.0.0.1", 0), group.next());

        host = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // Stays in front and keeps channelInactive to itself, as a host's own
                        // connection handler may
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                            }
                        });
                        ch.pipeline().addLast(new ByteToMessageDecoder() {
                            @Override
                            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                                if (in.readableBytes() < 4) {
                                    return;
                                }
                                signaling.initChannel(ctx.channel());
                                // Removing the decoder passes what it holds on to signaling
                                ctx.pipeline().remove(this);
                            }
                        });
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        port = ((InetSocketAddress) host.localAddress()).getPort();
    }

    @Test
    void servesAConnectionHandedOverAfterItsFirstBytes() throws Exception {
        start(builder());

        try (Client client = new Client()) {
            assertEquals(200, client.status(false));
        }
    }

    @Test
    void neverServesAHandedOverConnectionPastTheCap() throws Exception {
        start(builder().setMaxConnectionsPerAddress(1));

        try (Client held = new Client()) {
            assertEquals(200, held.status(true));
            try (Client refused = new Client()) {
                refused.send(false);
                assertEquals(-1, refused.read(), "the second is closed unanswered");
            }
            // Answered on the thread that had the refused request, after it
            assertEquals(200, held.status(true));
        }
        assertEquals(2, served.size(), "what the refused connection sent went no further");
    }

    @Test
    void freesTheAllowanceWhenAHandedOverConnectionCloses() throws Exception {
        start(builder().setMaxConnectionsPerAddress(1));

        // Closed by the server once answered, which gives the allowance back before the next
        // connection can be accepted on the same thread. A close from this side is only noticed on
        // the server's next read, which is not ordered against that accept.
        try (Client first = new Client()) {
            assertEquals(200, first.status(false));
            assertEquals(-1, first.read());
        }
        try (Client second = new Client()) {
            assertEquals(200, second.status(true));
        }
    }

    @Test
    void closesHandedOverConnectionsWithTheSignaling() throws Exception {
        start(builder());

        try (Client held = new Client()) {
            assertEquals(200, held.status(true));
            signaling.close();
            assertEquals(-1, held.read(), "a kept connection ends with the signaling");
        }
        try (Client late = new Client()) {
            late.send(false);
            assertEquals(-1, late.read(), "one handed in afterwards is closed unanswered");
        }
    }

    @Test
    void readsAProxyHeaderTheHostLeftInPlace() throws Exception {
        start(builder().setProxyProtocol(true).setTrustedProxies(List.of("127.0.0.0/8")));

        try (Client proxied = new Client(TestHttp.proxyV2Header("203.0.113.7", 5555))) {
            assertEquals(200, proxied.status(false));
        }
        assertEquals("203.0.113.7", served.remove().getAddress().getHostAddress());
    }

    @Test
    void refusesAChannelThatIsNotAnIpConnection() throws Exception {
        signaling = builder().build();
        EmbeddedChannel channel = new EmbeddedChannel();

        assertThrows(IllegalArgumentException.class, () -> signaling.initChannel(channel));
        channel.close();
    }

    /** A connection to the host's port, which sends whatever prefix it is given first. */
    private final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;

        Client(byte... prefix) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(10_000);
            this.socket.getOutputStream().write(prefix);
            this.in = new BufferedReader(
                    new InputStreamReader(this.socket.getInputStream(), StandardCharsets.US_ASCII));
        }

        void send(boolean keep) throws IOException {
            this.socket.getOutputStream().write(("GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: "
                    + (keep ? "keep-alive" : "close") + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            this.socket.getOutputStream().flush();
        }

        /** Sends a status check and reads the whole response, so the connection can be used again. */
        int status(boolean keep) throws IOException {
            send(keep);
            int status = TestHttp.readStatus(this.in);
            TestHttp.readBody(this.in);
            return status;
        }

        int read() throws IOException {
            return this.in.read();
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }
}
