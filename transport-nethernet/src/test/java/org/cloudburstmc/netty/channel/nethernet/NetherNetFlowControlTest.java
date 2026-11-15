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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** What happens once more is written than the remote takes, which the SCTP buffers of 1 MiB soon show. */
@Timeout(30)
class NetherNetFlowControlTest {
    private static final int MESSAGE = 64 << 10;

    @Test
    void aSendQueuePastTheLimitEndsTheConnectionAsQueueTooLong() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            NetherNetChildChannel child = connect(server, client).drain();
            // One array behind every message, so the burst does not hold its size in memory
            byte[] shared = new byte[MESSAGE];
            List<ByteBuf> buffers = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                buffers.add(Unpooled.wrappedBuffer(shared));
            }

            // Written in one flush from the loop, as encoded batches are, so the burst outruns the association
            List<ChannelFuture> writes = child.eventLoop().submit(() -> {
                child.maxQueuedBytes = 1 << 20;
                List<ChannelFuture> written = buffers.stream().map(child::write).toList();
                child.flush();
                return written;
            }).get(10, TimeUnit.SECONDS);

            assertEquals(TransportDisconnectReason.QUEUE_TOO_LONG, server.eventOnceClosed(child));
            for (ChannelFuture write : writes) {
                assertTrue(write.await(5, TimeUnit.SECONDS));
            }
            assertTrue(writes.stream().anyMatch(ChannelFuture::isSuccess), "the first ones went out");
            assertFalse(writes.get(writes.size() - 1).isSuccess(), "the rest failed with the close");
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt());
            }
        }
    }

    @Test
    void writabilityFollowsWhatTheDataChannelsHold() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            Connected connected = connect(server, client);
            NetherNetChildChannel child = connected.child();
            var changes = new LinkedBlockingQueue<Boolean>();
            child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                    changes.add(ctx.channel().isWritable());
                }
            });
            // Each flushed alone and under Netty's own high water mark, so only the data channels can turn it off.
            // The client reads nothing yet: libdatachannel queues 1024 messages for it, half the segments these
            // make, then stops reading, so native has to hold the rest
            byte[] shared = new byte[32 << 10];

            boolean writableAfterBurst = child.eventLoop().submit(() -> {
                for (int i = 0; i < 512; i++) {
                    child.writeAndFlush(Unpooled.wrappedBuffer(shared));
                }
                return child.isWritable();
            }).get(10, TimeUnit.SECONDS);

            assertFalse(writableAfterBurst, "native holds megabytes the client has not read");
            connected.drain();
            // It may come and go meanwhile, as the association takes what waits in bursts of its buffer
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!child.isWritable() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(child.isWritable(), "writable again once the remote read it all");
            assertNotNull(changes.poll(5, TimeUnit.SECONDS), "the pipeline heard of it");
            assertTrue(child.isOpen());
        }
    }

    /** A child and its client's data channels, which read nothing until {@link #drain}. */
    private record Connected(NetherNetChildChannel child, List<DataChannel> remote) {
        /** Reads and drops everything from now on, so only the client's pace holds the server back. */
        NetherNetChildChannel drain() {
            for (DataChannel channel : remote) {
                channel.onMessage.register(DataChannelCallback.Message.handleBinary((dc, data) -> {
                }));
            }
            return child;
        }
    }

    private static Connected connect(NetherNetTestServer server, PeerConnection client) throws Exception {
        server.bind();
        List<DataChannel> remote = new ArrayList<>();
        for (String label : new String[]{NetherNetConstants.RELIABLE_CHANNEL_LABEL,
                NetherNetConstants.UNRELIABLE_CHANNEL_LABEL}) {
            remote.add(client.createDataChannel(label));
        }
        return new Connected(server.connect(client), remote);
    }
}
