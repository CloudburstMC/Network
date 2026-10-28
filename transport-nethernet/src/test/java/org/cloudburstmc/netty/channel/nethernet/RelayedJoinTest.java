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
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayedJoinTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /**
     * A join whose media only reaches the server through a PROXY protocol relay, set up in the order a
     * built-in server binds: relays first, with no listener, then the first peer.
     */
    @Test
    @Timeout(30)
    void joinsThroughTheRelay() throws Exception {
        join(InetAddress.getByName("0.0.0.0"), offer -> offer, null, (relay, child) -> {
            assertTrue(relay.down.get() > 0, "the server answers through the relay");
            assertEquals(Set.copyOf(relay.flows.keySet()), Set.of(child.remoteAddress()), "the client, not the relay");
        });
    }

    /**
     * A client whose checks reach the relay through its TURN server. The server applies no remote candidate
     * behind relays, so the pair comes out peer reflexive, and only the relay the client signaled tells.
     */
    @Test
    @Timeout(30)
    void aClientBehindTurnKeepsTheSignaledAddress() throws Exception {
        InetSocketAddress signaled = new InetSocketAddress("198.51.100.1", 19132);
        // The client's own socket stands in for its TURN allocation. Loopback gathers a candidate to
        // translate even on a host with nothing else.
        join(LOOPBACK, offer -> offer.replace(" typ host", " typ relay raddr 0.0.0.0 rport 0"), signaled,
                (relay, child) -> {
                    assertEquals("prflx", child.selectedPath().remoteType());
                    assertEquals(signaled, child.remoteAddress());
                });
    }

    /**
     * Joins through a relay to a server bound to {@code bind}, checking the child once active.
     *
     * @param asSent   What the client's offer looks like by the time it reaches the server
     * @param signaled Where signaling met the client, or null when it does not know
     */
    private static void join(InetAddress bind, UnaryOperator<String> asSent, InetSocketAddress signaled,
                             BiConsumer<Relay, NetherNetChildChannel> check) throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        try (var relay = new Relay(new InetSocketAddress(LOOPBACK, port));
             var server = new NetherNetTestServer(new InetSocketAddress(bind, port));
             var client = PeerConnection.createPeer(NetherNetTestServer.CONFIG.withBindAddress(LOOPBACK))) {
            server.relays = IpRangeSet.parse(List.of(LOOPBACK.getHostAddress()));
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            server.offer("1", asSent.apply(NetherNetTestServer.offer(client)), signaled);
            String answer = SdpUtil.withRelayedCandidates(server.signaling.answer.get(5, TimeUnit.SECONDS),
                    List.of(new InetSocketAddress(LOOPBACK, relay.port())));
            client.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
            check.accept(relay, server.active());
        }
    }

    /** Forwards each client from its own port, with a PROXY v2 header on every datagram. */
    private static final class Relay implements AutoCloseable {
        final DatagramSocket front = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        final Map<InetSocketAddress, DatagramSocket> flows = new ConcurrentHashMap<>();
        final AtomicInteger down = new AtomicInteger();
        private final InetSocketAddress upstream;

        Relay(InetSocketAddress upstream) throws SocketException {
            this.upstream = upstream;
            daemon(this::forward);
        }

        private static void daemon(Runnable task) {
            Thread thread = new Thread(task, "relay");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return front.getLocalPort();
        }

        private void forward() {
            byte[] buffer = new byte[65535];
            try {
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    front.receive(packet);
                    InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();
                    DatagramSocket flow = flows.computeIfAbsent(client, this::open);
                    ByteBuffer out = ByteBuffer.allocate(28 + packet.getLength());
                    out.put(new byte[] {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A});
                    out.put((byte) 0x21).put((byte) 0x12).putShort((short) 12);
                    out.put(client.getAddress().getAddress()).put(LOOPBACK.getAddress());
                    out.putShort((short) client.getPort()).putShort((short) port());
                    out.put(buffer, 0, packet.getLength());
                    flow.send(new DatagramPacket(out.array(), out.position()));
                }
            } catch (IOException ignored) {
                // closed
            }
        }

        private DatagramSocket open(InetSocketAddress client) {
            try {
                DatagramSocket flow = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
                flow.connect(upstream);
                daemon(() -> {
                    byte[] buffer = new byte[65535];
                    try {
                        while (true) {
                            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                            flow.receive(packet);
                            front.send(new DatagramPacket(buffer, packet.getLength(), client));
                            down.incrementAndGet();
                        }
                    } catch (IOException ignored) {
                        // closed
                    }
                });
                return flow;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            front.close();
            flows.values().forEach(DatagramSocket::close);
        }
    }
}
