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
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.PeerConnection;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** What a remote that breaks the countdown framing gets, which depends on the data channel. */
@Timeout(20)
class NetherNetFramingTest {

    @Test
    void brokenFramingOnTheReliableChannelEndsTheConnectionAsABadPacket() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);

            // A bare countdown header, which the retail client closes on as well
            reliable.sendMessage(frame(0));

            assertEquals(TransportDisconnectReason.BAD_PACKET, server.eventOnceClosed(child));
        }
    }

    @Test
    void brokenFramingOnTheUnreliableChannelIsDropped() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            DataChannel unreliable = client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            var reads = new LinkedBlockingQueue<Byte>();
            child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                    reads.add(message.getByte(0));
                }
            });

            // Its middle segment lost, so it can never complete
            unreliable.sendMessage(frame(2, 1));
            unreliable.sendMessage(frame(0, 3));
            unreliable.sendMessage(frame(0, 4));

            assertEquals((byte) 4, reads.poll(5, TimeUnit.SECONDS));
            assertTrue(child.isActive());
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
