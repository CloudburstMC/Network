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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Which data channel a message takes each way, and what either refuses. */
@Timeout(20)
class NetherNetDeliveryTest {

    /** A reliable and an unreliable data channel of a client, with what each received. */
    private record Client(DataChannel reliable, DataChannel unreliable, BlockingQueue<byte[]> onReliable,
                          BlockingQueue<byte[]> onUnreliable) {
    }

    @Test
    void eachReadNamesTheDataChannelItCameOn() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection peer = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            Client client = open(server, peer);
            NetherNetChildChannel child = server.connect(peer);
            var reads = record(child);

            client.reliable().sendMessage(frame(0, 1));
            client.unreliable().sendMessage(frame(0, 2));

            assertEquals(Set.of("reliable 1", "unreliable 2"),
                    Set.of(reads.poll(5, TimeUnit.SECONDS), reads.poll(5, TimeUnit.SECONDS)));
        }
    }

    @Test
    void aPacketPicksTheDataChannelItGoesOut() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection peer = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            Client client = open(server, peer);
            NetherNetChildChannel child = server.connect(peer);

            child.writeAndFlush(new NetherNetPacket(Unpooled.wrappedBuffer(new byte[]{5, 6}), false)).sync();
            child.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{7})).sync();

            assertArrayEquals(new byte[]{0, 5, 6}, client.onUnreliable().poll(5, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{0, 7}, client.onReliable().poll(5, TimeUnit.SECONDS));
            assertTrue(client.onReliable().isEmpty() && client.onUnreliable().isEmpty());
        }
    }

    @Test
    void writesNoSegmentCarriesFailAndLeaveTheConnectionUp() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection peer = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            open(server, peer);
            NetherNetChildChannel child = server.connect(peer);
            int length = NetherNetChannel.MAX_SEGMENT_PAYLOAD + 1;
            ByteBuf unreliable = Unpooled.buffer(length).writerIndex(length);

            assertInstanceOf(IllegalArgumentException.class,
                    child.writeAndFlush(new NetherNetPacket(unreliable, false)).await().cause());
            assertInstanceOf(UnsupportedOperationException.class, child.writeAndFlush("text").await().cause());
            assertEquals(0, unreliable.refCnt());
            assertTrue(child.isActive());
        }
    }

    private static Client open(NetherNetTestServer server, PeerConnection peer) throws Exception {
        server.bind();
        var onReliable = new LinkedBlockingQueue<byte[]>();
        var onUnreliable = new LinkedBlockingQueue<byte[]>();
        DataChannel reliable = peer.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
        DataChannel unreliable = peer.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
        reliable.onMessage.register(
                DataChannelCallback.Message.handleBinary((dc, data) -> onReliable.add(bytes(data))));
        unreliable.onMessage.register(
                DataChannelCallback.Message.handleBinary((dc, data) -> onUnreliable.add(bytes(data))));
        return new Client(reliable, unreliable, onReliable, onUnreliable);
    }

    /** Each read as the channel the Delivery before it named, and its first byte. */
    private static BlockingQueue<String> record(NetherNetChildChannel child) {
        var reads = new LinkedBlockingQueue<String>();
        child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
            private NetherNetPacket.Delivery delivery;

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof NetherNetPacket.Delivery next) {
                    delivery = next;
                }
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                reads.add((delivery.reliable() ? "reliable " : "unreliable ") + message.getByte(0));
                delivery = null;
            }
        });
        return reads;
    }

    private static ByteBuffer frame(int... bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        for (int value : bytes) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }

    private static byte[] bytes(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }
}
