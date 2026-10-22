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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetherNetDiscoveryTest {

    /** Each bind brings a loop thread, which kept the JVM alive after close. */
    @Test
    void closeStopsTheLoopTheSocketRanOn() throws Exception {
        NetherNetDiscovery discovery = new NetherNetDiscovery(1);
        discovery.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        assertTrue(discovery.isActive());

        discovery.close();

        assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS), "the loop outlived close");
    }

    @Test
    void aFailedBindStopsItsLoop() throws Exception {
        try (DatagramSocket taken = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            NetherNetDiscovery discovery = new NetherNetDiscovery(1);

            assertThrows(BindException.class,
                    () -> discovery.bind((InetSocketAddress) taken.getLocalSocketAddress()));

            assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS),
                    "the loop outlived the failed bind");
        }
    }

    /** A request does not say which version a client reads, so it is answered in each, newest last. */
    @Test
    void everyRequestIsAnsweredFromTheMotdProviderAnewInEveryVersion() throws Exception {
        AtomicInteger players = new AtomicInteger(3);
        BlockingQueue<PongData> answers = new LinkedBlockingQueue<>();
        NetherNetDiscovery server = new NetherNetDiscovery(1);
        NetherNetDiscovery client = new NetherNetDiscovery(2);
        try {
            server.setMotdProvider((host, address, info) ->
                    new PongData.Builder().setPlayerCount(players.getAndIncrement()).build());
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            for (int i = 0; i < 2; i++) {
                client.sendDiscoveryRequest(server.localAddress(), (sender, payload) -> {
                    try {
                        answers.add(ServerData.decode(payload));
                    } finally {
                        payload.release();
                    }
                });
                for (int version : ServerData.VERSIONS) {
                    PongData answer = answers.poll(5, TimeUnit.SECONDS);
                    assertEquals(version, answer.dataVersion());
                    assertEquals(3 + i, answer.playerCount());
                }
            }
        } finally {
            server.close();
            client.close();
        }
    }

    @Test
    void aNullMotdLeavesTheRequestUnanswered() throws Exception {
        BlockingQueue<Long> answers = new LinkedBlockingQueue<>();
        NetherNetDiscovery server = new NetherNetDiscovery(1);
        NetherNetDiscovery client = new NetherNetDiscovery(2);
        try {
            server.setMotdProvider((host, address, info) -> null);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            client.sendDiscoveryRequest(server.localAddress(), (sender, payload) -> {
                payload.release();
                answers.add(sender);
            });

            assertNull(answers.poll(500, TimeUnit.MILLISECONDS));
        } finally {
            server.close();
            client.close();
        }
    }

    /** A 1.26.51 client's offers run to about 2000 bytes, and it declares 1140 of them, as BDS does. */
    @Test
    void aSignalLongerThanItsDeclaredLengthArrivesWhole() throws Exception {
        String offer = longOffer();
        BlockingQueue<String> offers = new LinkedBlockingQueue<>();
        NetherNetDiscovery server = new NetherNetDiscovery(1);
        NetherNetDiscovery client = new NetherNetDiscovery(2);
        try {
            server.setNewConnectionHandler((connectionId, remoteNetworkId, sdp, address, player) -> offers.add(sdp));
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            client.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            client.sendSignal(server.localAddress(), 1, NetherNetConstants.buildSignalConnectRequest("7", offer));

            assertEquals(offer, offers.poll(5, TimeUnit.SECONDS));
        } finally {
            server.close();
            client.close();
        }
    }

    @Test
    void aLongSignalDeclaresTheLengthBdsDoes() throws Exception {
        String signal = NetherNetConstants.buildSignalConnectRequest("7", longOffer());
        NetherNetDiscovery client = new NetherNetDiscovery(2);
        try (DatagramSocket peer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            peer.setSoTimeout(5000);
            client.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

            client.sendSignal((InetSocketAddress) peer.getLocalSocketAddress(), 1, signal);
            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
            peer.receive(packet);

            ByteBuf data = NetherNetConstants.decryptDiscoveryPacket(
                    Unpooled.wrappedBuffer(packet.getData(), 0, packet.getLength()));
            data.skipBytes(2 + 8 + 8 + 8); // Packet ID, sender, padding, recipient
            assertEquals(NetherNetDiscovery.MAX_DECLARED_SIGNAL_LENGTH, data.readIntLE());
            assertEquals(signal, data.toString(StandardCharsets.UTF_8));
            data.release();
        } finally {
            client.close();
        }
    }

    /** An offer listing as many candidates as a host with VPNs and container bridges does. */
    private static String longOffer() {
        StringBuilder offer = new StringBuilder("v=0\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
        for (int i = 0; i < 20; i++) {
            offer.append("a=candidate:").append(1000000 + i).append(" 1 udp 2122194687 10.0.0.").append(i)
                    .append(" 40000 typ host generation 0 network-id ").append(i).append("\r\n");
        }
        return offer.append("a=setup:actpass\r\na=mid:0\r\na=sctp-port:5000\r\n").toString();
    }
}
