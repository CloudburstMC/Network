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
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How client channels use their signaling: the failures it reports and who closes it. */
public class ClientChannelSignalingTest {

    private static final NetherNetAddress SERVER = new NetherNetAddress("2535000000000001");

    private final EventLoopGroup group = new NioEventLoopGroup(2);

    @AfterEach
    void shutDown() throws Exception {
        this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    @Test
    void aFailureOnceConnectedLeavesTheChannelOpen() throws Exception {
        try (NetherNetTestServer server = new NetherNetTestServer()) {
            server.bind();
            LoopbackSignaling signaling = new LoopbackSignaling(server);
            Channel channel = this.client(NetherNetChannelFactory.client(signaling))
                    .connect(SERVER).sync().channel();

            // As for a signal that reaches the peer only after it left
            signaling.registered.onFailure("Player not registered");
            channel.eventLoop().submit(() -> { }).sync();

            assertTrue(channel.isActive(), "a report after the connect closed the connection");
            channel.close().sync();
        }
    }

    /** ProxyPass makes a signaling per connection and counts on the channel to close it. */
    @Test
    void aChannelClosesTheSignalingItWasGiven() throws Exception {
        RecordingSignaling signaling = new RecordingSignaling();
        ChannelFuture connect = this.client(NetherNetChannelFactory.client(signaling)).connect(SERVER);
        signaling.awaitRegistered();

        connect.channel().close().sync();

        assertEquals(1, signaling.closes.get());
    }

    private Bootstrap client(ChannelFactory<NetherNetClientChannel> factory) {
        return new Bootstrap().group(this.group)
                .channelFactory(factory)
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 20_000)
                .option(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 1)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                    }
                });
    }

    /** Signaling that records the failure handler a channel registers and never connects. */
    static class RecordingSignaling implements NetherNetClientSignaling {
        final AtomicInteger closes = new AtomicInteger();
        volatile FailureHandler registered;

        /** Waits for a channel to start its handshake, which registers for failures. */
        void awaitRegistered() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (this.registered == null) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("no channel registered for failures");
                }
                Thread.sleep(10);
            }
        }

        @Override
        public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
            return new CompletableFuture<>();
        }

        @Override
        public void setFailureHandler(FailureHandler handler) {
            this.registered = handler;
        }

        @Override
        public void setSignalHandler(String connectionId, SignalHandler handler) {
        }

        @Override
        public void removeSignalHandler(String connectionId) {
        }

        @Override
        public String getLocalNetworkId() {
            return "client";
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void close() {
            this.closes.incrementAndGet();
        }
    }

    /** Signaling that hands the offer to an in-process server and its answer back. */
    static final class LoopbackSignaling extends RecordingSignaling {
        private final NetherNetTestServer server;
        private volatile String connectionId;
        private volatile SignalHandler handler;

        LoopbackSignaling(NetherNetTestServer server) {
            this.server = server;
        }

        @Override
        public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public boolean usesTrickleIce() {
            return false;
        }

        @Override
        public void setSignalHandler(String connectionId, SignalHandler handler) {
            this.connectionId = connectionId;
            this.handler = handler;
        }

        @Override
        public void sendDescription(String targetNetworkId, String sdp) {
            String id = this.connectionId;
            CompletableFuture.runAsync(() -> {
                try {
                    this.server.offer(id, sdp, new InetSocketAddress("127.0.0.1", 1));
                    String answer = this.server.signaling.answer.get(5, TimeUnit.SECONDS);
                    this.handler.onSignal(NetherNetConstants.buildSignalConnectResponse(id, answer));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
