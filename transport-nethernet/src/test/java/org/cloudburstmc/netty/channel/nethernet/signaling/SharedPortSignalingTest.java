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
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signaling served on connections a host accepted itself, as on a port shared with another protocol.
 */
class SharedPortSignalingTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private NetherNetHTTPServerSignaling signaling;
    private Channel host;

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

    /**
     * Starts a host that reads a connection's first bytes before handing it to signaling, so the
     * channel is active and holds unread bytes by then.
     */
    private int start(int maxConnectionsPerAddress) throws Exception {
        signaling = new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("example.test"))
                .setServeHttp(false)
                .setMaxConnectionsPerAddress(maxConnectionsPerAddress)
                .setMotdProvider((hostName, remoteAddress) -> PongData.DEFAULT)
                .build();
        signaling.bind(new InetSocketAddress("127.0.0.1", 0), group.next());

        host = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
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
        return ((InetSocketAddress) host.localAddress()).getPort();
    }

    private static String get(Socket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write("GET /v1/join HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        socket.setSoTimeout(10_000);
        InputStream in = socket.getInputStream();
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void servesAConnectionHandedOverAfterItsFirstBytes() throws Exception {
        int port = start(8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String response = get(socket);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.contains("\"transportLayer\""), response);
        }
    }

    @Test
    void countsHandedOverConnectionsAgainstTheirAddress() throws Exception {
        int port = start(1);
        try (Socket held = new Socket("127.0.0.1", port)) {
            // Handed over once its first bytes arrive, which is when it is counted
            held.getOutputStream().write("GET ".getBytes(StandardCharsets.US_ASCII));
            held.getOutputStream().flush();
            Thread.sleep(500);

            try (Socket refused = new Socket("127.0.0.1", port)) {
                assertEquals("", get(refused));
            }
        }
    }
}
