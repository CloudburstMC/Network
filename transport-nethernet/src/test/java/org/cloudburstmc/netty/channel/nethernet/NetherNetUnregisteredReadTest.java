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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An accepted channel can get messages before it has a loop, as an NXS child does: its peer carries them
 * while the acceptor is still registering it.
 */
@Timeout(30)
class NetherNetUnregisteredReadTest {
    private static final PeerConnectionConfiguration LOOPBACK =
            NetherNetTestServer.CONFIG.withBindAddress(InetAddress.getLoopbackAddress());
    private static final InetSocketAddress ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);

    @Test
    void messagesFromBeforeRegistrationAreReadOnceItRegisters() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try (PeerConnection client = PeerConnection.createPeer(LOOPBACK);
             PeerConnection server = PeerConnection.createPeer(LOOPBACK)) {
            var counts = new Counts();
            NetherNetChildChannel child = accepting(server, counts);
            connect(client, server, frame(0, 1), frame(0, 2));
            await(() -> counts.messages.get() == 2);

            BlockingQueue<String> events = record(child);
            group.register(child).sync();

            assertEquals("active", events.poll(5, TimeUnit.SECONDS));
            assertEquals("reliable 1", events.poll(5, TimeUnit.SECONDS));
            assertEquals("reliable 2", events.poll(5, TimeUnit.SECONDS));
            child.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void brokenFramingFromBeforeRegistrationClosesItOnceItRegisters() throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try (PeerConnection client = PeerConnection.createPeer(LOOPBACK);
             PeerConnection server = PeerConnection.createPeer(LOOPBACK)) {
            var counts = new Counts();
            NetherNetChildChannel child = accepting(server, counts);
            connect(client, server, frame(0));
            await(() -> counts.failures.get() == 1);

            BlockingQueue<String> events = record(child);
            group.register(child).sync();

            assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS));
            assertNull(events.poll(), "it never activated");
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static final class Counts implements NetherChannelMetrics {
        final AtomicInteger messages = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public void messagesIn(int count) {
            messages.addAndGet(count);
        }

        @Override
        public void decodeFail(int count) {
            failures.addAndGet(count);
        }
    }

    /** A child that takes the data channels its peer opens, with nothing to run on yet. */
    private static NetherNetChildChannel accepting(PeerConnection server, Counts counts) {
        NetherNetChildChannel child = new NetherNetChildChannel(null, server,
                new SourcedAddress(ADDRESS, AddressSource.SIGNALING, null), ADDRESS);
        child.config().setOption(NetherChannelOption.NETHER_METRICS, counts);
        server.onDataChannel.register((peer, channel) -> child.acceptDataChannel(channel));
        return child;
    }

    /**
     * Connects the two peers with fully gathered descriptions, the client sending {@code frames} on its reliable
     * channel once it opens.
     */
    private static void connect(PeerConnection client, PeerConnection server, ByteBuffer... frames)
            throws Exception {
        DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
        client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
        reliable.onOpen.register(channel -> {
            for (ByteBuffer frame : frames) {
                channel.sendMessage(frame);
            }
        });
        server.setRemoteDescription(NetherNetTestServer.offer(client), SessionDescriptionType.OFFER);
        var answer = new CompletableFuture<String>();
        server.onGatheringStateChange.register((peer, state) -> {
            if (state == GatheringState.RTC_GATHERING_COMPLETE) {
                answer.complete(peer.localDescription());
            }
        });
        server.setLocalDescription("answer");
        client.setRemoteDescription(answer.get(5, TimeUnit.SECONDS), SessionDescriptionType.ANSWER);
    }

    /** channelActive and each read with the Delivery before it. */
    private static BlockingQueue<String> record(NetherNetChildChannel child) {
        var events = new LinkedBlockingQueue<String>();
        child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
            private NetherNetPacket.Delivery delivery;

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                events.add("active");
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof NetherNetPacket.Delivery next) {
                    delivery = next;
                }
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                events.add((delivery.reliable() ? "reliable " : "unreliable ") + message.getByte(0));
            }
        });
        return events;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out");
            }
            Thread.sleep(10);
        }
    }

    private static ByteBuffer frame(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        for (int value : bytes) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }
}
