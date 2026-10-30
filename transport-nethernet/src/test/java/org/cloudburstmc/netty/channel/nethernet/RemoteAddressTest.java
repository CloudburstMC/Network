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
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A channel reports where signaling met the peer, which a relay may stand in for, until it goes
 * active, and the selected pair from then on unless that is a TURN relay or the server keeps what a
 * signaling proxy declared.
 */
class RemoteAddressTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final InetSocketAddress SIGNALED = new InetSocketAddress("198.51.100.1", 19132);
    private static final InetSocketAddress PLAYER = new InetSocketAddress("203.0.113.7", 54321);
    private static final InetSocketAddress TURN = new InetSocketAddress("192.0.2.9", 3478);
    private static final SourcedAddress SIGNALING = new SourcedAddress(SIGNALED, AddressSource.SIGNALING, null);

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
            NetherNetChildChannel accepted = server.accepted().child();
            assertEquals(SIGNALED, accepted.remoteAddress());
            assertEquals(SIGNALING, accepted.reportedAddress());
            assertEquals(AddressSource.UNKNOWN, accepted.mediaAddress().source());

            client.setRemoteDescription(server.signaling.answer.get(5, TimeUnit.SECONDS),
                    SessionDescriptionType.ANSWER);
            NetherNetChildChannel child = server.active();
            NetherNetChannel.Path path = child.selectedPath();
            assertNotNull(path);
            assertEquals(new InetSocketAddress(LOOPBACK, client.selectedCandidatePair().local().getPort()),
                    child.remoteAddress());
            assertEquals(path.remote(), child.remoteAddress());
            assertEquals(new SourcedAddress(path.remote(), AddressSource.ICE, null), child.mediaAddress());
            assertEquals(child.mediaAddress(), child.reportedAddress());
            assertEquals(SIGNALING, child.signaledAddress());
        }
    }

    @Test
    @Timeout(30)
    void keepsTheProxiedAddressOnceActiveWhenTold() throws Exception {
        SourcedAddress forwarded = new SourcedAddress(SIGNALED, AddressSource.SIGNALING_FORWARDED_FOR, null);
        try (var server = new NetherNetTestServer();
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.preferProxied = true;
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            server.offer("1", NetherNetTestServer.offer(client), forwarded);
            client.setRemoteDescription(server.signaling.answer.get(5, TimeUnit.SECONDS),
                    SessionDescriptionType.ANSWER);

            NetherNetChildChannel child = server.active();
            NetherNetChannel.Path path = child.selectedPath();
            assertNotNull(path);
            assertEquals(SIGNALED, child.remoteAddress());
            assertEquals(forwarded, child.reportedAddress());
            assertEquals(new SourcedAddress(path.remote(), AddressSource.ICE, null), child.mediaAddress(),
                    "the pair is still there to read");
        }
    }

    @Test
    void aProxiedAddressWinsOverAnyPairWhenTold() {
        List<SourcedAddress> pairs = List.of(new SourcedAddress(PLAYER, AddressSource.ICE, null),
                new SourcedAddress(PLAYER, AddressSource.ICE_PROXY_PROTOCOL, TURN),
                new SourcedAddress(TURN, AddressSource.TURN, null));
        for (AddressSource source : List.of(AddressSource.SIGNALING_PROXY_PROTOCOL,
                AddressSource.SIGNALING_FORWARDED_FOR)) {
            SourcedAddress signaled = new SourcedAddress(SIGNALED, source, null);
            for (SourcedAddress media : pairs) {
                assertEquals(signaled, NetherNetChannel.reported(signaled, media, true), source + " over " + media);
                if (media.source() != AddressSource.TURN) {
                    assertEquals(media, NetherNetChannel.reported(signaled, media, false), "only when told");
                }
            }
        }
    }

    @Test
    void anAddressNoProxyDeclaredFollowsThePairEvenWhenTold() {
        SourcedAddress media = new SourcedAddress(PLAYER, AddressSource.ICE, null);
        SourcedAddress turn = new SourcedAddress(TURN, AddressSource.TURN, null);
        for (AddressSource source : List.of(AddressSource.SIGNALING, AddressSource.HOST,
                AddressSource.SIGNALING_LAN, AddressSource.NXS_ADMISSION)) {
            SourcedAddress signaled = new SourcedAddress(SIGNALED, source, null);
            assertEquals(media, NetherNetChannel.reported(signaled, media, true), source.name());
            assertEquals(NetherNetChannel.reported(signaled, turn, false),
                    NetherNetChannel.reported(signaled, turn, true), source + " behind TURN");
        }
    }

    @Test
    void theSignaledAddressStandsUntilActive() {
        assertEquals(SIGNALING, NetherNetChannel.reported(SIGNALING, SourcedAddress.unknown(), false));
    }

    @Test
    void thePairWinsUnlessItIsTurn() {
        SourcedAddress media = new SourcedAddress(PLAYER, AddressSource.ICE, null);
        assertEquals(media, NetherNetChannel.reported(SIGNALING, media, false));
    }

    @Test
    void turnKeepsTheSignaledAddressWhenThereIsOne() {
        SourcedAddress turn = new SourcedAddress(TURN, AddressSource.TURN, null);
        assertEquals(SIGNALING, NetherNetChannel.reported(SIGNALING, turn, false));
        assertEquals(turn, NetherNetChannel.reported(SourcedAddress.unknown(), turn, false));
    }

    /** Only the peer says a pair is TURN, so it gives way to nothing a forged datagram could plant. */
    @Test
    void turnKeepsOnlyAnUnforgedSignaledAddress() {
        SourcedAddress turn = new SourcedAddress(TURN, AddressSource.TURN, null);
        for (AddressSource source : List.of(AddressSource.SIGNALING, AddressSource.SIGNALING_PROXY_PROTOCOL,
                AddressSource.SIGNALING_FORWARDED_FOR, AddressSource.HOST)) {
            SourcedAddress signaled = new SourcedAddress(SIGNALED, source, null);
            assertEquals(signaled, NetherNetChannel.reported(signaled, turn, false), source.name());
        }
        for (AddressSource source : List.of(AddressSource.SIGNALING_LAN, AddressSource.NXS_ADMISSION)) {
            SourcedAddress signaled = new SourcedAddress(SIGNALED, source, null);
            assertEquals(turn, NetherNetChannel.reported(signaled, turn, false), source.name());
        }
    }

    @Test
    void withNothingSignaledThePairStands() {
        // As for a client dialing a network id, which keeps that as its remote address
        SourcedAddress media = new SourcedAddress(PLAYER, AddressSource.ICE, null);
        assertEquals(media, NetherNetChannel.reported(SourcedAddress.unknown(), media, false));
    }

    @Test
    void neverHoldsANullAddress() {
        assertThrows(NullPointerException.class, () -> new SourcedAddress(null, AddressSource.ICE, null));
    }

    @Test
    void remembersRelaysItNeverApplies() {
        // Behind PROXY protocol relays no remote candidate is applied, and the peer is never touched
        RemoteCandidates candidates = RemoteCandidates.relayed(null, "1");
        candidates.trickle("candidate:1 1 UDP 16777215 192.0.2.9 3478 typ relay raddr 0.0.0.0 rport 0");
        candidates.trickle("candidate:2 1 UDP 1694498815 203.0.113.7 54321 typ srflx raddr 0.0.0.0 rport 0");
        assertTrue(candidates.isRelay(TURN));
        assertFalse(candidates.isRelay(PLAYER));
    }
}
