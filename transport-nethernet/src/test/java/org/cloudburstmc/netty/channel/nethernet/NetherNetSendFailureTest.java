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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnection;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class NetherNetSendFailureTest {

    @Test
    void aFailedSendClosesAsATransportFailure() throws Exception {
        try (var server = new NetherNetTestServer();
             // Smaller than a segment, so the server's send fails while the peer stays up
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withMaxMessageSize(1024))) {
            NetherNetChildChannel child = server.bindAndConnect(client);
            // Without it Netty only shuts the output down, which left a channel that never reported closing
            child.config().setAutoClose(false);

            ChannelFuture write = child.writeAndFlush(Unpooled.wrappedBuffer(new byte[4096]));

            assertTrue(write.await(5, TimeUnit.SECONDS), "the write settles");
            assertInstanceOf(IOException.class, write.cause());
            // A reason and no error, so the pipeline never sees the failure as an exception
            assertEquals(TransportDisconnectReason.TRANSPORT_FAILED,
                    server.eventOnceClosed(child));
        }
    }

    /** Protocol writes on void promises, so the failure arrives as an exception, after the reason. */
    @Test
    void theReasonArrivesBeforeAVoidWriteFails() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withMaxMessageSize(1024))) {
            NetherNetChildChannel child = server.bindAndConnect(client);
            child.config().setAutoClose(false);

            child.writeAndFlush(Unpooled.wrappedBuffer(new byte[4096]), child.voidPromise());

            assertTrue(child.closeFuture().await(10, TimeUnit.SECONDS), "the channel closes");
            assertEquals(TransportDisconnectReason.TRANSPORT_FAILED, server.childEvents.poll(1, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, server.childEvents.poll(1, TimeUnit.SECONDS));
        }
    }
}
