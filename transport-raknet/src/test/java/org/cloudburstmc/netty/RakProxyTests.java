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

package org.cloudburstmc.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.client.RakClientProxyRouteHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

public class RakProxyTests {

    private static final int PORT = 19134;
    private static final int PROTOCOL_VERSION = 11;
    private static final InetSocketAddress FAKE = new InetSocketAddress("8.8.8.8", 12345);

    private EventLoopGroup group;
    private Channel serverChannel;

    @BeforeEach
    public void setup() {
        group = new NioEventLoopGroup();
    }

    @AfterEach
    public void teardown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        group.shutdownGracefully().awaitUninterruptibly();
    }

    private void setupServer() {
        ServerBootstrap b = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROXY_PROTOCOL, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        if (!ch.remoteAddress().equals(FAKE)) {
                            ch.close();
                        }
                    }
                });

        this.serverChannel = b.bind(new InetSocketAddress(PORT)).awaitUninterruptibly().channel();
    }

    private Bootstrap clientBootstrap() {
        return new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) {
                        ch.rakPipeline().addBefore(
                                RakClientProxyRouteHandler.NAME,
                                "proxy-protocol-header",
                                new ProxyProtocolHeaderPrepender()
                        );
                    }
                });
    }

    @Test
    public void testProxyProtocol() {
        setupServer();

        Channel client = clientBootstrap()
                .connect(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(client.isActive(), "Client should connect with fake IP");
        client.close().awaitUninterruptibly();
    }

    private static class ProxyProtocolHeaderPrepender extends ChannelOutboundHandlerAdapter {

        private static final byte[] PROXY_V2_SIGNATURE = new byte[]{
                0x0D, 0x0A, 0x0D, 0x0A,
                0x00, 0x0D, 0x0A,
                0x51, 0x55, 0x49, 0x54,
                0x0A
        };

        private boolean sentHeader;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (sentHeader || !(msg instanceof DatagramPacket)) {
                ctx.write(msg, promise);
                return;
            }

            sentHeader = true;

            DatagramPacket packet = (DatagramPacket) msg;
            ByteBuf header = proxyProtocolHeader(
                    ctx.alloc(),
                    FAKE,
                    packet.recipient()
            );

            ByteBuf content = ctx.alloc().buffer(
                    header.readableBytes() + packet.content().readableBytes()
            );

            try {
                content.writeBytes(header);
                content.writeBytes(
                        packet.content(),
                        packet.content().readerIndex(),
                        packet.content().readableBytes()
                );

                ctx.write(new DatagramPacket(content, packet.recipient()), promise);
            } catch (Throwable throwable) {
                content.release();
                promise.setFailure(throwable);
            } finally {
                header.release();
                ReferenceCountUtil.release(packet);
            }
        }

        private static ByteBuf proxyProtocolHeader(ByteBufAllocator alloc,
                                                   InetSocketAddress source,
                                                   InetSocketAddress destination) {
            byte[] sourceAddress = source.getAddress().getAddress();
            byte[] destinationAddress = destination.getAddress().getAddress();
            if (sourceAddress.length != 4 || destinationAddress.length != 4) {
                throw new IllegalArgumentException("This test only supports IPv4 addresses");
            }

            ByteBuf buf = alloc.buffer(28);
            buf.writeBytes(PROXY_V2_SIGNATURE);
            buf.writeByte(0x21);
            buf.writeByte(0x12);
            buf.writeShort(12);
            buf.writeBytes(sourceAddress);
            buf.writeBytes(destinationAddress);
            buf.writeShort(source.getPort());
            buf.writeShort(destination.getPort());
            return buf;
        }
    }
}
