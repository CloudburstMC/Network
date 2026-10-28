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
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.unix.Socket;
import io.netty.util.concurrent.Promise;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class ProxyChannelTests {

    private static final int PORT = 19134;

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

    @Test
    public void testDisconnectAfterCloseLeavesReusedFdAlone() throws Exception {
        Assumptions.assumeTrue(Epoll.isAvailable(), "Needs epoll");

        EventLoopGroup epollGroup = new EpollEventLoopGroup(1);
        try {
            Promise<Void> unregistered = epollGroup.next().newPromise();
            RakClientChannel channel = (RakClientChannel) new Bootstrap()
                    .channelFactory(RakChannelFactory.client(EpollDatagramChannel.class))
                    .group(epollGroup)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) {
                            unregistered.trySuccess(null);
                        }
                    })
                    .register()
                    .awaitUninterruptibly()
                    .channel();
            int fd = ((EpollDatagramChannel) channel.parent()).fd().intValue();

            channel.close().awaitUninterruptibly();
            Assertions.assertTrue(unregistered.awaitUninterruptibly(5, TimeUnit.SECONDS));

            // The OS hands out the lowest free fd, so a new socket takes over the closed one's number
            Socket socket = Socket.newSocketDgram();
            try {
                Assumptions.assumeTrue(socket.intValue() == fd, "fd was not reused");
                socket.connect(new InetSocketAddress("127.0.0.1", PORT));
                InetSocketAddress localAddress = socket.localAddress();

                Assertions.assertTrue(channel.disconnect().awaitUninterruptibly().isSuccess());
                Assertions.assertEquals(localAddress, socket.localAddress(),
                        "Disconnecting a closed channel must not disconnect the socket that reused its fd");
            } finally {
                socket.close();
            }
        } finally {
            epollGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    @Test
    public void testDisconnectEndsOpenSession() {
        Promise<TransportDisconnectReason> serverReason = group.next().newPromise();
        this.serverChannel = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof TransportDisconnectReason) {
                            serverReason.trySuccess((TransportDisconnectReason) evt);
                        }
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();

        Channel client = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(client.isActive());

        client.disconnect();

        // Well below the session timeout, so the server must have received the notification
        Assertions.assertTrue(serverReason.awaitUninterruptibly(5, TimeUnit.SECONDS));
        Assertions.assertEquals(TransportDisconnectReason.CLOSED_BY_REMOTE_PEER, serverReason.getNow());
        Assertions.assertTrue(client.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCloseInsideReadKeepsParentOpenUntilTheReadEnds() {
        this.startEchoServer();

        Promise<Boolean> parentOpen = group.next().newPromise();
        Channel client = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .handler(closeOnRead(parentOpen))
                .connect(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(client.isActive());

        client.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(new byte[]{(byte) 0xfe, 1})));

        Assertions.assertTrue(parentOpen.awaitUninterruptibly(5, TimeUnit.SECONDS));
        Assertions.assertTrue(parentOpen.getNow(), "the parent socket closed while its read was still running");
        Assertions.assertTrue(client.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS));
        Assertions.assertFalse(client.parent().isOpen());
    }

    @Test
    public void testCloseInsideReadDoesNotReadTheClosedFd() {
        Assumptions.assumeTrue(Epoll.isAvailable(), "Needs epoll");
        this.startEchoServer();

        EventLoopGroup epollGroup = new EpollEventLoopGroup(1);
        try {
            Promise<Boolean> parentOpen = epollGroup.next().newPromise();
            Channel client = new Bootstrap()
                    .channelFactory(RakChannelFactory.client(EpollDatagramChannel.class))
                    .group(epollGroup)
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                    .handler(closeOnRead(parentOpen))
                    .connect(new InetSocketAddress("127.0.0.1", PORT))
                    .awaitUninterruptibly()
                    .channel();
            Assertions.assertTrue(client.isActive());
            // Older Netty, such as 4.1.101, leaves epoll datagram channels at one read per wakeup, which hides the extra read
            client.parent().config().<MaxMessagesRecvByteBufAllocator>getRecvByteBufAllocator().maxMessagesPerRead(16);

            List<Throwable> errors = new CopyOnWriteArrayList<>();
            client.parent().pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    errors.add(cause);
                    ctx.fireExceptionCaught(cause);
                }
            });

            client.writeAndFlush(new RakMessage(Unpooled.wrappedBuffer(new byte[]{(byte) 0xfe, 1})));

            Assertions.assertTrue(client.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS));
            // The loop has one thread, so once this runs the read that closed the channel is over
            client.eventLoop().submit(() -> { }).awaitUninterruptibly();
            // Epoll reads once more after every datagram, which fails with EBADF if the close already freed the fd
            Assertions.assertTrue(errors.isEmpty(), () -> "the read loop went on reading the fd it closed: " + errors);
        } finally {
            epollGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }

    private void startEchoServer() {
        this.serverChannel = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .childHandler(new SimpleChannelInboundHandler<RakMessage>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                        ctx.writeAndFlush(new RakMessage(message.content().retain()));
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();
    }

    /** Closes the client on its first message and reports whether the parent socket was still open right after. */
    private static ChannelHandler closeOnRead(Promise<Boolean> parentOpenAfterClose) {
        return new SimpleChannelInboundHandler<RakMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                ctx.channel().close();
                parentOpenAfterClose.trySuccess(ctx.channel().parent().isOpen());
            }
        };
    }
}
