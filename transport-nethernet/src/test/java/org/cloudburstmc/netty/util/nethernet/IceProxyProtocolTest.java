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

package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.IceUdpMuxListener;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IceProxyProtocolTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** Hybrid mode: the NXS listener closing leaves built-in signaling's relays on the shared port. */
    @Test
    @Timeout(20)
    void relaysStayUntilEveryOwnerCloses() throws Exception {
        int port;
        try (var available = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = available.getLocalPort();
        }
        InetAddress any = InetAddress.getByName("0.0.0.0");
        IpRangeSet relays = IpRangeSet.parse(List.of("127.0.0.1"));
        BlockingQueue<IceUdpMuxListener.Request> requests = new LinkedBlockingQueue<>();
        try (var mux = new IceUdpMuxListener(any, port, Runnable::run, request -> {
                 requests.add(request);
                 return CompletableFuture.completedFuture(null);
             })) {
            IceProxyProtocol builtin = IceProxyProtocol.set(null, port, relays);
            IceProxyProtocol nxs = IceProxyProtocol.set(any, port, relays);
            assertNotNull(builtin);
            assertNotNull(nxs);
            try {
                nxs.close();
                IceUdpMuxListener.Request request = relay(port, requests, 40123);
                assertNotNull(request, "the relay is still read");
                assertEquals("203.0.113.7", request.remoteAddress());
                assertEquals(40123, request.remotePort());
            } finally {
                builtin.close();
            }
            assertNull(relay(port, requests, 40124), "the relay is no longer read");
        }
    }

    /** Sends a binding request from a new relay port for a client at 203.0.113.7. */
    private static IceUdpMuxListener.Request relay(int port, BlockingQueue<IceUdpMuxListener.Request> requests,
                                                   int clientPort) throws Exception {
        try (var relay = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            byte[] packet = proxied(binding(), clientPort, port);
            relay.send(new DatagramPacket(packet, packet.length, LOOPBACK, port));
            return requests.poll(2, TimeUnit.SECONDS);
        }
    }

    /** A PROXY v2 header for a UDP datagram from 203.0.113.7, as a relay sends it. */
    private static byte[] proxied(byte[] payload, int clientPort, int port) throws Exception {
        ByteBuffer packet = ByteBuffer.allocate(16 + 12 + payload.length);
        packet.put(new byte[] {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A});
        packet.put((byte) 0x21).put((byte) 0x12).putShort((short) 12);
        packet.put(InetAddress.getByName("203.0.113.7").getAddress()).put(LOOPBACK.getAddress());
        packet.putShort((short) clientPort).putShort((short) port);
        return packet.put(payload).array();
    }

    /** A STUN connectivity check, signed with a password no one checks, as no admission keys are set. */
    private static byte[] binding() throws Exception {
        byte[] username = "serverUfrag:clientUfrag".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer packet = ByteBuffer.allocate(2048);
        packet.putShort((short) 1).putShort((short) 0).putInt(0x2112a442);
        packet.putInt(0x12345678).putLong(0x0102030405060708L);
        packet.putShort((short) 6).putShort((short) username.length).put(username);
        while (packet.position() % 4 != 0) {
            packet.put((byte) 0);
        }
        packet.putShort((short) 0x24).putShort((short) 4).putInt(2130706431);
        packet.putShort((short) 0x802a).putShort((short) 8).putLong(0x7071727374757677L);
        int integrity = packet.position();
        packet.putShort(2, (short) (integrity + 24 - 20));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("unchecked".getBytes(StandardCharsets.US_ASCII), "HmacSHA1"));
        byte[] digest = mac.doFinal(Arrays.copyOf(packet.array(), integrity));
        packet.putShort((short) 8).putShort((short) 20).put(digest);
        return Arrays.copyOf(packet.array(), packet.position());
    }
}
