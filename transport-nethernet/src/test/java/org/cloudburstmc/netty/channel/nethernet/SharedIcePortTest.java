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
import tel.schich.libdatachannel.IceUdpMuxListener;
import tel.schich.libdatachannel.PeerConnection;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedIcePortTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** Hybrid mode: built-in joins share the ICE port with the NXS listener and never wait on it. */
    @Test
    @Timeout(20)
    void builtInJoinsBypassAPendingListenerOnTheirPort() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        var held = new AtomicInteger();
        try (var nxs = new IceUdpMuxListener(LOOPBACK, port, Runnable::run, request -> {
                 held.incrementAndGet();
                 return CompletableFuture.completedFuture(null);
             });
             var server = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port));
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            // As NXS filters it before any key arrives
            nxs.setAdmission(new byte[0], Map.of());
            assertTrue(server.bindAndConnect(client).isActive());
            assertEquals(0, held.get());
            assertEquals(0, nxs.statistics().notifications());
        }
    }

    /** On a wildcard ICE address, built-in peers join the listener's socket instead of failing to bind the port. */
    @Test
    @Timeout(20)
    void builtInPeersShareAWildcardListenersSocket() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        InetAddress any = InetAddress.getByName("0.0.0.0");
        try (var nxs = new IceUdpMuxListener(any, port, Runnable::run, request -> CompletableFuture.completedFuture(null));
             var server = new NetherNetTestServer(new InetSocketAddress(any, port));
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            var accepted = server.accept(NetherNetTestServer.offer(client));
            // The child sets its peer up on the event loop
            for (int i = 0; i < 200 && nxs.statistics().agents() == 0; i++) {
                Thread.sleep(10);
            }
            assertEquals(1, nxs.statistics().agents());
            assertTrue(accepted.child().isOpen());
        }
    }

    /** Signaling learns the media is relayed before it binds, so no answer names this host past the relays. */
    @Test
    @Timeout(20)
    void signalingKnowsTheMediaIsRelayedBeforeItBinds() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        try (var relayed = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port))) {
            relayed.relays = IpRangeSet.parse(List.of("127.0.0.1"));
            relayed.bind();
            assertTrue(relayed.signaling.relayedAtBind);
        }
        try (var direct = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port))) {
            direct.bind();
            assertFalse(direct.signaling.relayedAtBind);
        }
    }

    /** Signaling that cannot keep the media behind relays fails the bind, which releases the relays. */
    @Test
    @Timeout(20)
    void signalingThatCannotHideTheMediaReleasesTheRelays() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        try (var refusing = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port))) {
            refusing.relays = IpRangeSet.parse(List.of(LOOPBACK.getHostAddress()));
            refusing.signaling.refuseRelays = true;
            assertThrows(UnsupportedOperationException.class, refusing::bind);
        }
        // A listed relay's headerless checks would be dropped, so this only connects once they are gone
        try (var direct = new NetherNetTestServer(new InetSocketAddress(LOOPBACK, port));
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            assertTrue(direct.bindAndConnect(client).isActive());
        }
    }
}
