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

import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Which address a child reports once ICE connects: the selected pair, unless a trusted proxy
 * declared the signaled address, as a proxy relaying the media without a PROXY header would
 * otherwise have every player share its own.
 */
class SignaledAddressTest {
    private static final InetSocketAddress SIGNALED = new InetSocketAddress("198.51.100.1", 19132);

    @Test
    void theSelectedPairReplacesAnAddressThePeerSignaledFrom() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client, SIGNALED, false);

            assertNotEquals(SIGNALED, child.remoteAddress());
        }
    }

    @Test
    void aSignaledAddressIsKeptWhenThePairGoesThroughTurn() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            // As a client behind a TURN server announces the address the server allocated, which is where
            // its traffic then comes from: here loopback, the path these peers connect over
            NetherNetChildChannel child = server.connect(client, SIGNALED, false, offer -> offer.replaceAll(
                    "(?<pre>a=candidate:\\S+ \\d+ \\S+ \\d+ )\\S+(?<port> \\d+ typ )host",
                    "${pre}127.0.0.1${port}relay raddr 0.0.0.0 rport 0"));

            assertEquals(SIGNALED, child.remoteAddress());
        }
    }

    @Test
    void anAddressAProxyDeclaredIsKept() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client, SIGNALED, true);

            assertEquals(SIGNALED, child.remoteAddress());
        }
    }
}
