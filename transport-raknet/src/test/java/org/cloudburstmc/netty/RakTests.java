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
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakDatagramCodec;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
    private static final int WARMUP_PACKET_ID = 0xFE;
    private static final int BURST_PACKET_ID = 0xFD;

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
                        System.out.println("Initialized server channel");
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
        return bind(serverBootstrap());
    }

    private InetSocketAddress setupServer(BlockingQueue<RakChildChannel> children) {
        return bind(serverBootstrap().childHandler(new ChannelInitializer<RakChildChannel>() {
            @Override
            protected void initChannel(RakChildChannel ch) {
                children.add(ch);
            }
        }));
    }

    // An event would replace the reason the disconnecting code already holds, such as a kick message
    private static ChannelInboundHandler reasonRecorder(AtomicBoolean fired) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof RakDisconnectReason) {
                    fired.set(true);
                }
                ctx.fireUserEventTriggered(evt);
            }
        };
    }

    private InetSocketAddress bind(ServerBootstrap bootstrap) {
        serverChannel = bootstrap
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


    @Test
    public void testClientClosesOnRemoteDisconnect() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        AtomicBoolean inactiveWhileOpen = new AtomicBoolean();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                // Long enough that only the disconnect notification can close the client in time
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        if (ctx.channel().isOpen()) {
                            inactiveWhileOpen.set(true);
                        }
                        ctx.fireChannelInactive();
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        child.rakPipeline().get(RakSessionCodec.class).disconnect(RakDisconnectReason.DISCONNECTED);

        Assertions.assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS),
                "Client should close when the server disconnects");
        Assertions.assertFalse(inactiveWhileOpen.get(), "Client should not go inactive while still open");
    }

    @Test
    public void testServerClosesOnRemoteDisconnect() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        AtomicBoolean reasonFired = new AtomicBoolean();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(reasonRecorder(reasonFired))
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        channel.disconnect().awaitUninterruptibly();

        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS),
                "Server child should close when the client disconnects");
        Assertions.assertFalse(reasonFired.get(), "Disconnecting a client should not fire a disconnect reason");
    }

    @Test
    public void testServerDisconnectNotifiesClient() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                // Long enough that only the disconnect notification can close the client in time
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AtomicBoolean reasonFired = new AtomicBoolean();
        child.pipeline().addLast(reasonRecorder(reasonFired));
        child.disconnect();

        Assertions.assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS),
                "Client should close when the server disconnects it");
        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS), "Server child should close");
        Assertions.assertFalse(reasonFired.get(), "Disconnecting a child should not fire a disconnect reason");
    }

    @Test
    public void testServerClosesWhenDisconnectNotificationFails() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        // Child session pipelines allocate through the server channel's own config
        NotificationFailingAllocator allocator = new NotificationFailingAllocator();
        serverChannel.config().setAllocator(allocator);

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        allocator.failNotification = true;
        child.rakPipeline().get(RakSessionCodec.class).disconnect(RakDisconnectReason.DISCONNECTED);

        // The live client keeps the session from timing out, so only the disconnect can close it
        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS),
                "Server child should close even if the disconnect notification fails");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testRepeatedDisconnectCompletesOnClose() throws Exception {
        InetSocketAddress address = setupServer();
        AtomicReference<Runnable> heldClose = new AtomicReference<>();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                        // Hold the close, so the session stays disconnecting while the channel is open
                        heldClose.set(() -> ctx.close(promise));
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        ChannelFuture first = channel.disconnect();
        ChannelFuture second = channel.disconnect();
        channel.eventLoop().submit(() -> { }).sync(); // Both disconnects have run once this does
        Assertions.assertTrue(first.isSuccess(), "First disconnect should succeed");
        Assertions.assertNull(second.cause(), "A repeated disconnect should not fail");
        Assertions.assertNotNull(heldClose.get(), "First disconnect should close the channel");

        channel.eventLoop().execute(heldClose.get());
        Assertions.assertTrue(second.await(5, TimeUnit.SECONDS) && second.isSuccess(),
                "A repeated disconnect should complete once the channel closes");
        Assertions.assertFalse(channel.isOpen(), "Client should be closed");
    }

    @Test
    public void testConnectFailsAsClosedWhenClosedDuringHandshake() throws InterruptedException {
        // A peer that never replies keeps the client in the handshake
        CountDownLatch handshakeStarted = new CountDownLatch(1);
        Channel silentPeer = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ReferenceCountUtil.release(msg);
                        handshakeStarted.countDown();
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel();
        try {
            ChannelFuture future = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                    .handler(new ChannelInboundHandlerAdapter())
                    .connect(silentPeer.localAddress());
            Assertions.assertTrue(handshakeStarted.await(5, TimeUnit.SECONDS), "Client should start the handshake");
            future.channel().close();

            Assertions.assertTrue(future.await(5, TimeUnit.SECONDS), "Connect should complete once the client closes");
            Assertions.assertTrue(future.cause() instanceof ClosedChannelException,
                    "Connect should fail as closed, not cancelled: " + future.cause());
        } finally {
            silentPeer.close().awaitUninterruptibly();
        }
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

    @Test
    public void testReliableMessagesStayWithinWindow() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        int warmup = 100;
        int burst = RakConstants.RELIABLE_WINDOW_SIZE * 2;
        CountDownLatch warmupReceived = new CountDownLatch(warmup);
        CountDownLatch burstReceived = new CountDownLatch(burst);

        Channel channel = connectCounting(address, RakConstants.MAXIMUM_MTU_SIZE, warmupReceived, burstReceived);
        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AckBlocker blocker = new AckBlocker();
        child.rakPipeline().addAfter(RakDatagramCodec.NAME, "ack-blocker", blocker);

        // Acknowledged full datagrams grow the congestion window well past the reliable window, so bytes alone
        // would not hold the burst back
        sendFromSession(child, warmup, () -> {
            ByteBuf buf = Unpooled.buffer(1200);
            buf.writeByte(WARMUP_PACKET_ID);
            return buf.writeZero(1199);
        });
        Assertions.assertTrue(warmupReceived.await(5, TimeUnit.SECONDS), "Client should receive the warmup");
        int windowEnd = blocker.highestReliabilityIndex + 1 + RakConstants.RELIABLE_WINDOW_SIZE;

        blocker.blocking = true;
        sendFromSession(child, burst, () -> Unpooled.buffer(1).writeByte(BURST_PACKET_ID));
        Thread.sleep(200);
        Assertions.assertTrue(blocker.highestReliabilityIndex < windowEnd,
                "Sent reliable index " + blocker.highestReliabilityIndex + " past the window ending at " + windowEnd);
        Assertions.assertTrue(burstReceived.getCount() > 0, "Burst should wait for acknowledgements");

        blocker.blocking = false;
        Assertions.assertTrue(burstReceived.await(10, TimeUnit.SECONDS),
                "Client should receive the burst once acknowledgements resume");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testSplitMessagesInFlightStayWithinLimit() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        int warmup = 1000;
        int burst = RakConstants.MAXIMUM_SPLITS_IN_FLIGHT * 2;
        CountDownLatch warmupReceived = new CountDownLatch(warmup);
        CountDownLatch burstReceived = new CountDownLatch(burst);

        Channel channel = connectCounting(address, RakConstants.MAXIMUM_MTU_SIZE, warmupReceived, burstReceived);
        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AckBlocker blocker = new AckBlocker();
        child.rakPipeline().addAfter(RakDatagramCodec.NAME, "ack-blocker", blocker);

        // A full datagram per warmup message grows the congestion window past the burst, so bytes alone would let
        // it all out. Blocking the last acknowledgements would leave the warmup to be resent, shrinking the window.
        sendFromSession(child, warmup, () -> Unpooled.buffer(1200).writeByte(WARMUP_PACKET_ID).writeZero(1199));
        Assertions.assertTrue(warmupReceived.await(5, TimeUnit.SECONDS), "Client should receive the warmup");
        Thread.sleep(200);

        blocker.blocking = true;
        int partSize = RakConstants.MAXIMUM_MTU_SIZE - RakConstants.UDP_HEADER_SIZE - 20
                - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE;
        sendFromSession(child, burst,
                () -> Unpooled.buffer(partSize + 1).writeByte(BURST_PACKET_ID).writeZero(partSize));
        Thread.sleep(200);
        Assertions.assertTrue(blocker.splitIds.size() <= RakConstants.MAXIMUM_SPLITS_IN_FLIGHT,
                "Sent " + blocker.splitIds.size() + " split messages without acknowledgements");
        Assertions.assertTrue(burstReceived.getCount() > 0, "Burst should wait for acknowledgements");

        blocker.blocking = false;
        Assertions.assertTrue(burstReceived.await(10, TimeUnit.SECONDS),
                "Client should receive the burst once acknowledgements resume");
        channel.close().awaitUninterruptibly();
    }

    // Counts what the client receives by the first byte of each message
    private static Channel connectCounting(InetSocketAddress address, int mtu, CountDownLatch warmupReceived,
                                           CountDownLatch burstReceived) {
        Channel channel = clientBootstrap(mtu)
                .handler(new SimpleChannelInboundHandler<RakMessage>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                        int id = message.content().getUnsignedByte(message.content().readerIndex());
                        (id == WARMUP_PACKET_ID ? warmupReceived : burstReceived).countDown();
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");
        return channel;
    }

    @Test
    public void testOversizedMessageFailsWithoutStallingTheSession() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        BlockingQueue<Integer> received = new LinkedBlockingQueue<>();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new SimpleChannelInboundHandler<RakMessage>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                        received.add(message.content().readableBytes());
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        int largest = child.maxMessageSize();
        // Every part full: the MTU less the UDP, IPv4, encapsulated and datagram headers
        Assertions.assertEquals(RakConstants.MAXIMUM_SPLIT_COUNT * (RakConstants.MAXIMUM_MTU_SIZE
                - RakConstants.UDP_HEADER_SIZE - 20 - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE
                - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE), largest);
        Assertions.assertEquals(largest, ((RakChannel) channel).maxMessageSize(), "Client sees the same limit");

        ChannelFuture oversized = child.rakPipeline().writeAndFlush(new RakMessage(userMessage(largest + 1)));
        Assertions.assertTrue(oversized.await(5, TimeUnit.SECONDS), "Oversized write should complete");
        Assertions.assertTrue(oversized.cause() instanceof IllegalArgumentException,
                "Oversized write should fail: " + oversized.cause());

        // Ordered after the refused write, so it only arrives if that write left no gap in the ordering channel
        child.rakPipeline().writeAndFlush(new RakMessage(userMessage(largest)));
        Assertions.assertEquals(largest, received.poll(10, TimeUnit.SECONDS), "The largest message should arrive");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testMaxMessageSizeWithoutASession() {
        // Never connected, so no handshake added a session
        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInboundHandlerAdapter())
                .register()
                .syncUninterruptibly()
                .channel();
        try {
            // Every part full at the smallest MTU, over IPv6
            Assertions.assertEquals(RakConstants.MAXIMUM_SPLIT_COUNT * (RakConstants.MINIMUM_MTU_SIZE
                    - RakConstants.UDP_HEADER_SIZE - 40 - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE
                    - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE), ((RakChannel) channel).maxMessageSize());
        } finally {
            channel.close().awaitUninterruptibly();
        }
    }

    // Starts with a user packet ID, as a leading zero would be taken for a connected ping
    private static ByteBuf userMessage(int size) {
        return Unpooled.buffer(size).writeByte(BURST_PACKET_ID).writeZero(size - 1);
    }

    // Queues messages in one task on the session's thread, so a single flush sees all of them
    private static void sendFromSession(RakChildChannel child, int count, Supplier<ByteBuf> message) throws Exception {
        child.parent().eventLoop().submit(() -> {
            for (int i = 0; i < count; i++) {
                child.rakPipeline().write(new RakMessage(message.get()));
            }
            child.rakPipeline().flush();
        }).sync();
    }

    // Drops acknowledgements while blocking, and records the highest reliable index and the split IDs sent
    private static final class AckBlocker extends ChannelDuplexHandler {
        private final Set<Integer> splitIds = ConcurrentHashMap.newKeySet();
        private volatile boolean blocking;
        private volatile int highestReliabilityIndex = -1;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Data datagrams are decoded by now, so a buffer is an ACK or NACK
            if (this.blocking && msg instanceof ByteBuf) {
                ByteBuf buffer = (ByteBuf) msg;
                if ((buffer.getByte(buffer.readerIndex()) & RakConstants.FLAG_ACK) != 0) {
                    buffer.release();
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof RakDatagramPacket) {
                for (EncapsulatedPacket packet : ((RakDatagramPacket) msg).getPackets()) {
                    if (packet.getReliability().isReliable()) {
                        this.highestReliabilityIndex = Math.max(this.highestReliabilityIndex,
                                packet.getReliabilityIndex());
                    }
                    if (packet.isSplit()) {
                        this.splitIds.add(packet.getPartId());
                    }
                }
            }
            ctx.write(msg, promise);
        }
    }

    private static final class NotificationFailingAllocator extends AbstractByteBufAllocator {
        private volatile boolean failNotification;

        @Override
        public ByteBuf ioBuffer(int initialCapacity) {
            // The disconnect notification is the only single byte allocation in the session
            if (initialCapacity == 1 && this.failNotification) {
                this.failNotification = false;
                throw new OutOfMemoryError("Simulated direct memory exhaustion");
            }
            return super.ioBuffer(initialCapacity);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
