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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.CandidatePair;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claims from upstream #105 and #106 about which address a NetherNet child reports, checked
 * against this branch on real loopback connections.
 */
class AddressClaimsTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final InetSocketAddress PLAYER = new InetSocketAddress("203.0.113.7", 54321);
    private static final InetSocketAddress PROXY = new InetSocketAddress("198.51.100.9", 40000);
    private static final UnaryOperator<String> HOST_AS_RELAY =
            offer -> offer.replace(" typ host", " typ relay raddr 0.0.0.0 rport 0");

    /** #106's SignaledAddressTest case on this branch: TURN told by the pair's own type, no PPv2. */
    @Test
    @Timeout(30)
    void aPairTypedRelayKeepsTheSignaledAddress() throws Exception {
        SourcedAddress signaled = new SourcedAddress(PLAYER, AddressSource.SIGNALING, null);
        connect(HOST_AS_RELAY, signaled, child -> {
            assertEquals("relay", child.selectedPath().remoteType(), "the typed branch, not the PPv2 match");
            assertEquals(AddressSource.TURN, child.mediaAddress().source());
            assertEquals(PLAYER, child.remoteAddress());
            assertEquals(signaled, child.reportedAddress());
        });
    }

    /** A friend join, which signals no address: TURN falls back to the relay's own address. */
    @Test
    @Timeout(30)
    void aPairTypedRelayWithNothingSignaledReportsTheRelay() throws Exception {
        connect(HOST_AS_RELAY, null, child -> {
            assertEquals(AddressSource.UNKNOWN, child.signaledAddress().source());
            assertEquals(AddressSource.TURN, child.mediaAddress().source());
            assertEquals(child.selectedPath().remote(), child.remoteAddress());
            assertEquals(child.mediaAddress(), child.reportedAddress());
        });
    }

    /**
     * #105's MCXboxBroadcast case: Xbox signaling hands in no address, so before ICE the child
     * holds the wildcard, and the pair from channelActive on.
     */
    @Test
    @Timeout(30)
    void aJoinWithNothingSignaledTakesThePair() throws Exception {
        connect(offer -> offer, null, child -> {
            assertEquals(AddressSource.ICE, child.reportedAddress().source());
            assertEquals(child.selectedPath().remote(), child.remoteAddress());
        });
    }

    /**
     * #106's Infinity Filter case, a deliberate policy difference: a proxy declared the player on
     * signaling but relays the media without a header, so the pair names the proxy and wins. The
     * declared address stays readable as the signaled address.
     */
    @Test
    @Timeout(30)
    void aProxyDeclaredSignalingAddressGivesWayToThePair() throws Exception {
        for (AddressSource source : List.of(AddressSource.SIGNALING_PROXY_PROTOCOL,
                AddressSource.SIGNALING_FORWARDED_FOR)) {
            SourcedAddress declared = new SourcedAddress(PLAYER, source, PROXY);
            connect(offer -> offer, declared, child -> {
                assertEquals(declared, child.signaledAddress());
                assertEquals(AddressSource.ICE, child.reportedAddress().source());
                assertEquals(child.selectedPath().remote(), child.remoteAddress());
                assertNotEquals(PLAYER, child.remoteAddress());
            });
        }
    }

    /**
     * #105's regression: the address moved inside the native RTC_CONNECTED callback, off the
     * loop, while the channel was still inactive. Here it stays signaled until the loop activates
     * the channel, and the pair shows from channelActive on. The loop is held from before
     * RTC_CONNECTED, so activation cannot overtake the read however fast loopback is.
     */
    @Test
    @Timeout(30)
    void theAddressMovesOnTheLoopRightBeforeChannelActive() throws Exception {
        InetSocketAddress signaled = new InetSocketAddress("198.51.100.1", 19132);
        try (var server = new NetherNetTestServer();
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            server.offer("1", NetherNetTestServer.offer(client), signaled);
            NetherNetChildChannel child = server.accepted().child();
            assertEquals(signaled, child.remoteAddress(), "an initializer's read");

            var atConnected = new CompletableFuture<SocketAddress>();
            var connectedOnLoop = new CompletableFuture<Boolean>();
            // After the server's own listener, which is where 54f2b742 moved the address
            server.accepted().peer().onStateChange.register((peer, state) -> {
                if (state == PeerState.RTC_CONNECTED) {
                    connectedOnLoop.complete(child.eventLoop().inEventLoop());
                    atConnected.complete(child.remoteAddress());
                }
            });
            var atActive = new CompletableFuture<SocketAddress>();
            child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    atActive.complete(ctx.channel().remoteAddress());
                    ctx.fireChannelActive();
                }
            });

            String answer = server.signaling.answer.get(5, TimeUnit.SECONDS);
            var hold = new CountDownLatch(1);
            child.eventLoop().execute(() -> {
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                client.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                assertEquals(signaled, atConnected.get(10, TimeUnit.SECONDS), "read at RTC_CONNECTED");
                assertFalse(connectedOnLoop.get(), "RTC_CONNECTED arrives off the loop");
                assertFalse(child.isActive());
                assertEquals(signaled, child.remoteAddress(), "read while still inactive");
            } finally {
                hold.countDown();
            }
            server.active();
            InetSocketAddress pair = child.selectedPath().remote();
            assertEquals(pair, atActive.get(5, TimeUnit.SECONDS));
            assertEquals(pair, child.remoteAddress());
        }
    }

    /**
     * #106: "peerConnection.remoteAddress() comes back unresolved". True of that call, which
     * always makes an unresolved address. The pair this branch reads is resolved even when the
     * peer named its candidates by name, as libdatachannel resolves them before libjuice sees
     * them, an mDNS name that fails is never added, and libjuice prints a peer reflexive one
     * from its socket address.
     */
    @Test
    @Timeout(30)
    void theSelectedPairIsResolvedEvenWhenThePeerNamesItsCandidates() throws Exception {
        for (String name : List.of("localhost", "0f5b6c1e-3a7d-4c11-9a3e-2d0c4b1e9f00.local")) {
            PeerConnectionConfiguration loopback = PeerConnectionConfiguration.DEFAULT.withBindAddress(LOOPBACK);
            try (PeerConnection offerer = PeerConnection.createPeer(loopback);
                 PeerConnection answerer = PeerConnection.createPeer(loopback)) {
                offerer.onLocalDescription.register((peer, sdp, type) -> answerer.setRemoteDescription(sdp, type));
                answerer.onLocalDescription.register((peer, sdp, type) -> offerer.setRemoteDescription(
                        sdp.replace(LOOPBACK.getHostAddress(), name), type));
                offerer.onLocalCandidate.register((peer, candidate, mid) ->
                        answerer.addRemoteCandidate(candidate, mid));
                answerer.onLocalCandidate.register((peer, candidate, mid) -> offerer.addRemoteCandidate(
                        candidate.replace(LOOPBACK.getHostAddress(), name), mid));

                CountDownLatch open = new CountDownLatch(1);
                DataChannel sender = offerer.createDataChannel("pair");
                sender.onOpen.register(channel -> open.countDown());
                offerer.setLocalDescription(null);
                assertTrue(open.await(20, TimeUnit.SECONDS), "connects with " + name);

                CandidatePair pair = offerer.selectedCandidatePair();
                assertNotNull(pair.remote(), pair.remoteCandidate());
                assertFalse(pair.remote().isUnresolved(), name + ": " + pair.remoteCandidate());
                assertFalse(pair.remoteCandidate().contains(name), pair.remoteCandidate());
                assertTrue(offerer.remoteAddress().isUnresolved(), "the call #106 resolves by hand");
                System.out.println("[claims] " + name + ": " + pair.remoteCandidate());
            }
        }
    }

    /**
     * IP and port, not IP alone: a TURN server sends from the relayed transport address its
     * candidate names, so another port on that host is not the relay. Also IPv6 and an
     * IPv4-mapped spelling of a relay, as a dual-stack PPv2 relay may write it.
     */
    @Test
    void relaysMatchByAddressAndPort() throws Exception {
        RemoteCandidates candidates = RemoteCandidates.relayed(null, "1");
        candidates.trickle("candidate:1 1 UDP 16777215 192.0.2.9 3478 typ relay raddr 0.0.0.0 rport 0");
        candidates.trickle("candidate:2 1 UDP 16777215 2001:db8::1 50124 typ relay raddr :: rport 0");
        assertTrue(candidates.isRelay(new InetSocketAddress("192.0.2.9", 3478)));
        assertTrue(candidates.isRelay(new InetSocketAddress(InetAddress.getByName("::ffff:192.0.2.9"), 3478)));
        assertTrue(candidates.isRelay(new InetSocketAddress("2001:db8:0:0:0:0:0:1", 50124)));
        assertFalse(candidates.isRelay(new InetSocketAddress("192.0.2.9", 3479)));
        assertNull(SdpUtil.relayEndpoint("not a candidate"));
    }

    private interface Check {
        void accept(NetherNetChildChannel child) throws Exception;
    }

    /** Joins as signaled from {@code signaled}, with the offer rewritten on the way, and checks the active child. */
    private static void connect(UnaryOperator<String> asSent, SourcedAddress signaled, Check check) throws Exception {
        try (var server = new NetherNetTestServer();
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            String offer = asSent.apply(NetherNetTestServer.offer(client));
            server.server.eventLoop().submit(() -> server.server.acceptConnection("1", offer, "client", signaled, null))
                    .sync();
            client.setRemoteDescription(server.signaling.answer.get(5, TimeUnit.SECONDS),
                    SessionDescriptionType.ANSWER);
            check.accept(server.active());
        }
    }
}
