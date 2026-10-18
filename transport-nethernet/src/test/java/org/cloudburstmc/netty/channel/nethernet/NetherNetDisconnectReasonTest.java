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
import io.netty.channel.Channel;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
