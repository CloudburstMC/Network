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

import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.*;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetherNetReceiveOwnershipTest {
    @Test
    void messagesMetricFailureReleasesCompletedPacket() throws Exception {
        assertReleasedAfter(new NetherChannelMetrics() {
            public void messagesIn(int count) { throw new IllegalStateException("metric failed"); }
        });
    }

    @Test
    void bytesMetricErrorReleasesCompletedPacket() throws Exception {
        assertReleasedAfter(new NetherChannelMetrics() {
            public void bytesIn(int count) { throw new AssertionError("metric failed"); }
        });
    }

    private void assertReleasedAfter(NetherChannelMetrics metrics) throws Exception {
        var allocator = new TrackingAllocator();
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            child.config().setAllocator(allocator);
            child.config().setOption(NetherChannelOption.NETHER_METRICS, metrics);
            var delivered = new CompletableFuture<Void>();
            // Runs after the transport's receive listener, including its failure cleanup.
            child.reliableChannel.onMessage.register(DataChannelCallback.Message.handleBinary(
                    (channel, data) -> delivered.complete(null)));
            reliable.sendMessage(ByteBuffer.allocateDirect(4).put(new byte[]{0, 1, 2, 3}).flip());
            delivered.get(5, TimeUnit.SECONDS);
            assertEquals(1, allocator.buffers.size());
            allocator.assertReleased();
        } finally {
            for (var buffer : allocator.buffers) if (buffer.refCnt() != 0) buffer.release(buffer.refCnt());
        }
    }
}
