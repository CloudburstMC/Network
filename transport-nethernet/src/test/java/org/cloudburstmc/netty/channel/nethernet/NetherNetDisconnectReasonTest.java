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

package org.cloudburstmc.netty.channel.nethernet;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.PeerConnection;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class NetherNetDisconnectReasonTest {

    /** Nothing runs on a loop shut down under an open channel, which used to leave it and its peer open. */
    @Test
    void aChannelWhoseLoopIsGoneStillClosesWhenTheRemoteEndsIt() throws Exception {
        // Not closed at the end, as closing goes through the loop that is gone
        NetherNetTestServer server = new NetherNetTestServer();
        try (PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            NetherNetChildChannel child = server.bindAndConnect(client);
            server.shutDownLoop();

            client.closeAsync();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child.isOpen() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(child.isOpen(), "the channel and its peer stayed open");
        }
    }

    @Test
    void aClientClosingTheReliableChannelReadsAsClosedByTheRemote() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);

            reliable.close();

            assertEquals(TransportDisconnectReason.CLOSED_BY_REMOTE_PEER, server.eventOnceClosed(child));
        }
    }

    @Test
    void aClientClosingItsPeerReadsAsTheRemoteEndingIt() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            NetherNetChildChannel child = server.bindAndConnect(client);

            client.closeAsync();

            // Whether SCTP shuts down or aborts depends on how far the client's teardown gets
            Object reason = server.eventOnceClosed(child);
            assertTrue(reason == TransportDisconnectReason.CLOSED_BY_REMOTE_PEER
                    || reason == TransportDisconnectReason.ABORTED_BY_REMOTE_PEER, String.valueOf(reason));
        }
    }

    /** Netty fires channelInactive only for a channel that is still active when it closes. */
    @Test
    void aRemoteEndingTheConnectionFiresChannelInactive() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            CountDownLatch inactive = inactiveLatch(child);

            reliable.close();

            assertEquals(TransportDisconnectReason.CLOSED_BY_REMOTE_PEER, server.eventOnceClosed(child));
            assertTrue(inactive.await(2, TimeUnit.SECONDS), "closed without channelInactive");
        }
    }

    /**
     * A flush that lands after the remote closed but before that reaches the loop, as a host's batch
     * flush does when a player quits. It used to fail with NotYetConnectedException.
     */
    @Test
    void aFlushBeforeTheRemoteCloseReachesTheLoopIsDroppedQuietly() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            AtomicBoolean closedNatively = new AtomicBoolean();

            // Holds the loop, so the close queues behind the flush
            ChannelFuture write = child.eventLoop().submit(() -> {
                reliable.close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!child.reliableChannel.isClosed() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                closedNatively.set(child.reliableChannel.isClosed());
                return child.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{1}));
            }).get(10, TimeUnit.SECONDS);

            assertTrue(closedNatively.get(), "the remote close never arrived");
            assertNull(write.cause());
            assertEquals(TransportDisconnectReason.CLOSED_BY_REMOTE_PEER, server.eventOnceClosed(child));
        }
    }

    /** A client channel activates on its own, which used to leave it never reporting a reason. */
    @Test
    void aClientHearsTheServerEndingTheConnection() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try (var server = new NetherNetTestServer()) {
            server.bind();
            BlockingQueue<Object> events = new LinkedBlockingQueue<>();
            Channel client = connectClient(group, server, events);

            server.active().close().sync();

            assertTrue(client.closeFuture().await(10, TimeUnit.SECONDS), "the client never closed");
            Object reason = events.poll(1, TimeUnit.SECONDS);
            assertTrue(reason == TransportDisconnectReason.CLOSED_BY_REMOTE_PEER
                    || reason == TransportDisconnectReason.ABORTED_BY_REMOTE_PEER, String.valueOf(reason));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void aClientFiresChannelInactiveWhenTheServerEndsTheConnection() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try (var server = new NetherNetTestServer()) {
            server.bind();
            Channel client = connectClient(group, server, new LinkedBlockingQueue<>());
            CountDownLatch inactive = inactiveLatch(client);

            server.active().close().sync();

            assertTrue(client.closeFuture().await(10, TimeUnit.SECONDS), "the client never closed");
            assertTrue(inactive.await(2, TimeUnit.SECONDS), "closed without channelInactive");
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void aClientClosingItselfTellsItsPipelineNothing() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try (var server = new NetherNetTestServer()) {
            server.bind();
            BlockingQueue<Object> events = new LinkedBlockingQueue<>();
            Channel client = connectClient(group, server, events);

            client.close().sync();

            assertNull(events.poll(500, TimeUnit.MILLISECONDS));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /** Counts down once {@code channel} fires channelInactive. */
    private static CountDownLatch inactiveLatch(Channel channel) throws InterruptedException {
        CountDownLatch inactive = new CountDownLatch(1);
        channel.eventLoop().submit(() -> channel.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactive.countDown();
                ctx.fireChannelInactive();
            }
        })).sync();
        return inactive;
    }

    /** A real client channel joined to the test server, whose user events land in {@code events}. */
    private static Channel connectClient(EventLoopGroup group, NetherNetTestServer server,
                                         BlockingQueue<Object> events) throws InterruptedException {
        var signaling = new ClientChannelSignalingTest.LoopbackSignaling(server);
        return new Bootstrap().group(group)
                .channelFactory(NetherNetChannelFactory.client(signaling))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 10_000)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 1)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                        events.add(event);
                    }
                })
                .connect(new NetherNetAddress("server")).sync().channel();
    }

    @Test
    void closingThisSideTellsTheLocalPipelineNothing() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            NetherNetChildChannel child = server.bindAndConnect(client);

            child.close().sync();

            assertNull(server.childEvents.poll(500, TimeUnit.MILLISECONDS));
        }
    }
}
