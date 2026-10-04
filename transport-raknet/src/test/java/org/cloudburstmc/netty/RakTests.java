/*
 * Copyright 2022 CloudburstMC
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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class RakTests {

    private static final byte[] ADVERTISEMENT = new StringJoiner(";", "", ";")
            .add("MCPE")
            .add("RakNet unit test")
            .add(Integer.toString(542))
            .add("1.19.0")
            .add(Integer.toString(0))
            .add(Integer.toString(4))
            .add(Long.toUnsignedString(ThreadLocalRandom.current().nextLong()))
            .add("C")
            .add("Survival")
            .add("1")
            .add("19132")
            .add("19132")
            .toString().getBytes(StandardCharsets.UTF_8);

    private static final int RESEND_PACKET_ID = 0xFF;

    // Shared across tests to skip the shutdown quiet period per test. Closing the server closes its children.
    private static EventLoopGroup group;
    private Channel serverChannel;

    @BeforeAll
    public static void setupGroup() {
        group = new NioEventLoopGroup();
    }

    @AfterAll
    public static void shutdownGroup() {
        group.shutdownGracefully().awaitUninterruptibly();
    }

    @AfterEach
    public void teardown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
    }

    private static SimpleChannelInboundHandler<RakMessage> RESEND_HANDLER() {
        return new SimpleChannelInboundHandler<RakMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
                int packetId = message.content().getUnsignedByte(message.content().readerIndex());
                if (packetId == RESEND_PACKET_ID) {
                    ctx.writeAndFlush(new RakMessage(message.content().retain()));
                } else {
                    ctx.fireChannelRead(message.retain());
                }
            }
        };
    };

    private static SimpleChannelInboundHandler<RakMessage> RESEND_RECEIVER(ByteBuf expectedMessage,
                                                                           CountDownLatch received) {
        return new SimpleChannelInboundHandler<RakMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
                int packetId = message.content().getUnsignedByte(message.content().readerIndex());
                if (packetId != RESEND_PACKET_ID) {
                    ctx.fireChannelRead(message.retain());
                    return;
                }

                ByteBuf buffer = message.content().skipBytes(1);
                if (ByteBufUtil.equals(buffer, expectedMessage)) {
                    System.out.println("Received message is valid");
                    received.countDown();
                } else {
                    throw new IllegalStateException("Malformed message received\nExpected: " + ByteBufUtil.hexDump(expectedMessage) + "\nReceived: " + ByteBufUtil.hexDump(buffer));
                }
            }
        };
    };

    private static ServerBootstrap serverBootstrap() {
        return new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{11})
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1)
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer(ADVERTISEMENT))
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) throws Exception {
                        System.out.println("Initialised server channel");
                    }
                })
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) throws Exception {
                        System.out.println("Server child channel initialized " + ch.remoteAddress());
                        ch.pipeline().addLast(RESEND_HANDLER());
                    }
                });
    }

    private static Bootstrap clientBootstrap(int mtu) {
        return new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_MTU, mtu)
                .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1);
    }

    private static IntStream validMtu() {
        return IntStream.range(RakConstants.MINIMUM_MTU_SIZE, RakConstants.MAXIMUM_MTU_SIZE)
                .filter(i -> i % 12 == 0);
    }

    private InetSocketAddress setupServer() {
        serverChannel = serverBootstrap()
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel();
        return (InetSocketAddress) serverChannel.localAddress();
    }

    @Test
    public void testClientConnect() {
        InetSocketAddress address = setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = clientBootstrap(mtu)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(channel.isActive(), "Client should connect");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testCompatibleClientConnect() {
        InetSocketAddress address = setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = clientBootstrap(mtu)
                .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(channel.isActive(), "Client should connect in compatibility mode");
        channel.close().awaitUninterruptibly();
    }


    @ParameterizedTest
    @MethodSource("validMtu")
    public void testClientResend(int mtu) throws InterruptedException {
        InetSocketAddress address = setupServer();
        System.out.println("Testing client with MTU " + mtu);

        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[mtu * 16];
        random.nextBytes(bytes);
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        CountDownLatch received = new CountDownLatch(1);

        ChannelHandler sender = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ByteBuf buf = buffer.alloc().buffer(buffer.readableBytes() + 1);
                buf.writeByte(RESEND_PACKET_ID);
                buf.writeBytes(buffer.slice());

                ctx.channel().writeAndFlush(new RakMessage(buf));
            }
        };

        ChannelInitializer<RakClientChannel> initializer = new ChannelInitializer<RakClientChannel>() {
            @Override
            protected void initChannel(RakClientChannel ch) throws Exception {
                ch.pipeline().addLast(RESEND_RECEIVER(buffer, received));
                ch.pipeline().addLast(sender);
            }
        };

        Channel channel = clientBootstrap(mtu)
                .handler(initializer)
                .connect(address)
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(received.await(5, TimeUnit.SECONDS), "Client should receive the resent message");
        channel.close().awaitUninterruptibly();
    }
}
