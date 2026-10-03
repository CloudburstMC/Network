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
import io.netty.channel.*;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.*;
import tel.schich.libdatachannel.exception.LibDataChannelException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetherNetServerOwnershipTest {
    @Test
    void signalingSetupFailureClosesAllocatedPeer() throws Exception {
        assertSetupFailureClosesPeer(false);
    }

    @Test
    void signalingCleanupFailureCannotSkipPeerDeletion() throws Exception {
        assertSetupFailureClosesPeer(true);
    }

    private void assertSetupFailureClosesPeer(boolean failRemoval) throws Exception {
        try (var server = new NetherNetTestServer()) {
            server.signaling.failSetup = true;
            server.signaling.failRemoval = failRemoval;
            server.bind();
            assertPeerClosed(server, server.accept("unused"));
        }
    }

    @Test
    void negotiationFailureClosesAllocatedPeer() throws Exception {
        try (var server = new NetherNetTestServer()) {
            server.bind();
            assertPeerClosed(server, server.accept("not an SDP offer"));
        }
    }

    @Test
    void handshakeTimeoutClosesRegisteredChildAndItsPeer() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.server.config().setOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, 1);
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            var accepted = server.accept(NetherNetTestServer.offer(client));
            assertTrue(accepted.child().isRegistered());
            assertPeerClosed(server, accepted);
        }
    }

    @Test
    void nonTrickleJoinWithoutIceServersConnects() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            assertTrue(server.server.config().getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG)
                    .iceServers().isEmpty());
            assertTrue(server.signaling.getIceServers().isEmpty());
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            assertCarriesMessages(server.connect(client), reliable);
            assertTrue(server.signaling.sent.isEmpty(), "a non-trickle join must not trickle");
        }
    }

    @Test
    void trickleJoinWithoutIceServersConnects() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.signaling.trickle = true;
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            String offer = NetherNetTestServer.offer(client);
            List<String> candidates = NetherNetTestServer.candidates(offer);

            // The client's candidates all arrive before the child registers
            var accepted = server.acceptOffLoop(NetherNetTestServer.withoutCandidates(offer), () -> {
                for (String candidate : candidates) {
                    server.signaling.handler.onSignal(NetherNetConstants.buildSignalCandidateAdd("1", candidate));
                }
            });

            // The answer goes out before any of the server's candidates
            String first = server.signaling.sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(first, "nothing trickled");
            var answer = NetherNetConstants.parseSignal(first);
            assertEquals(NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE, answer.type());
            client.setRemoteDescription(answer.payload(), SessionDescriptionType.ANSWER);
            for (String sent; (sent = server.signaling.sent.poll(200, TimeUnit.MILLISECONDS)) != null; ) {
                var candidate = NetherNetConstants.parseSignal(sent);
                assertEquals(NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD, candidate.type());
                client.addRemoteCandidate(candidate.payload());
            }

            NetherNetChildChannel child = server.active();
            String applied = accepted.peer().remoteDescription();
            for (String candidate : candidates) {
                // candidate:<foundation> <component> <transport> <priority> <address> <port> typ ...
                String[] fields = candidate.split(" ");
                assertTrue(applied.contains(" " + fields[4] + " " + fields[5] + " typ "),
                        "early candidate not applied: " + candidate);
            }
            assertCarriesMessages(child, reliable);
        }
    }

    @Test
    void pendingSignalsReplayInOrderThenPassStraightThrough() {
        var pending = new NetherNetServerChannel.PendingSignals();
        var expected = new ArrayList<String>();
        for (int i = 0; i < NetherNetServerChannel.PendingSignals.MAX_PENDING; i++) {
            pending.onSignal("s" + i);
            expected.add("s" + i);
        }
        pending.onSignal("over the cap");
        expected.add("after");

        var seen = new ArrayList<String>();
        pending.deliverTo(seen::add);
        pending.onSignal("after");
        assertEquals(expected, seen);
    }

    private static void assertCarriesMessages(NetherNetChildChannel child, DataChannel reliable) throws Exception {
        var received = new CompletableFuture<Integer>();
        child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                received.complete(message.readInt());
            }
        });
        reliable.sendMessage(ByteBuffer.allocateDirect(5).put((byte) 0).putInt(1234).flip());
        assertEquals(1234, received.get(5, TimeUnit.SECONDS));
        assertTrue(child.isActive());
    }

    @Test
    void unknownAndDuplicateChannelsAreClosedAndOriginalChannelStillCarriesMessages() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            for (String label : new String[]{"unexpected", NetherNetConstants.RELIABLE_CHANNEL_LABEL,
                    NetherNetConstants.UNRELIABLE_CHANNEL_LABEL}) {
                var closed = new CompletableFuture<Void>();
                try (DataChannel extra = client.createDataChannel(label)) {
                    extra.onClosed.register(channel -> closed.complete(null));
                    if (extra.isClosed()) closed.complete(null);
                    closed.get(5, TimeUnit.SECONDS);
                }
            }
            assertCarriesMessages(child, reliable);
        }
    }

    private static void assertPeerClosed(NetherNetTestServer server, NetherNetTestServer.Accepted accepted)
            throws Exception {
        assertTrue(accepted.child().closeFuture().await(5, TimeUnit.SECONDS));
        server.signaling.removed.get(5, TimeUnit.SECONDS);
        assertNull(accepted.child().peerConnection);
        assertThrows(LibDataChannelException.class, () -> accepted.peer().createDataChannel("must-be-deleted"));
    }
}
