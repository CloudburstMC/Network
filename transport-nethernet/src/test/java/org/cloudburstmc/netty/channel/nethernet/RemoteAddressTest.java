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

import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A channel reports where signaling met the peer, which a relay may stand in for, until it goes
 * active, and the selected pair from then on.
 */
class RemoteAddressTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final InetSocketAddress SIGNALED = new InetSocketAddress("198.51.100.1", 19132);
    private static final InetSocketAddress PLAYER = new InetSocketAddress("203.0.113.7", 54321);

    @Test
    @Timeout(30)
    void takesTheSelectedPairOnceActiveEvenAfterAnEarlierRead() throws Exception {
        try (var server = new NetherNetTestServer();
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            server.offer("1", NetherNetTestServer.offer(client), SIGNALED);

            // The client has no answer yet, so ICE cannot have connected; AbstractChannel would keep this read
            assertEquals(SIGNALED, server.accepted().child().remoteAddress());

            client.setRemoteDescription(server.signaling.answer.get(5, TimeUnit.SECONDS),
                    SessionDescriptionType.ANSWER);
            NetherNetChildChannel child = server.active();
            NetherNetChannel.Path path = child.selectedPath();
            assertNotNull(path);
            assertEquals(new InetSocketAddress(LOOPBACK, client.selectedCandidatePair().local().getPort()),
                    child.remoteAddress());
            assertEquals(path.remote(), child.remoteAddress());
        }
    }

    @Test
    void aNetworkIdStays() {
        NetherNetAddress target = new NetherNetAddress("1234");
        assertEquals(target, NetherNetChannel.connectedAddress(target, pair(PLAYER, "srflx")));
    }

    private static NetherNetChannel.Path pair(InetSocketAddress remote, String type) {
        return new NetherNetChannel.Path(new InetSocketAddress(LOOPBACK, 19132), "host", remote, type);
    }
}
