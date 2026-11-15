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

import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nothing but STUN lookups passes the PROXY protocol relays, so a peer's TURN allocations never would. */
class TurnBehindRelaysTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** A host's own TURN servers next to relays fail the bind, rather than hold every join up. */
    @Test
    @Timeout(20)
    void configuredTurnServersRefuseTheRelays() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        try (var server = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port))) {
            server.relays = IpRangeSet.parse(List.of("127.0.0.1"));
            server.peerConfig = PeerConnectionConfiguration.DEFAULT
                    .withIceServers(List.of(URI.create("turn:user:secret@relay.example:3478")));
            IOException refused = assertThrows(IOException.class, server::bind);
            assertTrue(refused.getMessage().contains("TURN"), refused.getMessage());
        }
        // STUN servers still answer past the relays
        try (var server = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port))) {
            server.relays = IpRangeSet.parse(List.of("127.0.0.1"));
            server.peerConfig = PeerConnectionConfiguration.DEFAULT
                    .withIceServers(List.of(URI.create("stun:stun.example:3478")));
            server.bind();
        }
    }

    @Test
    void turnIsToldFromStun() {
        assertTrue(NetherNetChannel.isTurn(URI.create("turn:relay.example:3478?transport=udp")));
        assertTrue(NetherNetChannel.isTurn(URI.create("TURNS:relay.example:5349")));
        assertFalse(NetherNetChannel.isTurn(URI.create("stun:stun.example:3478")));
    }
}
