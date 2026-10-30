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

import org.cloudburstmc.netty.channel.nethernet.AddressSource;
import org.cloudburstmc.netty.channel.nethernet.SourcedAddress;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ForwardedForTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final BlockingQueue<InetSocketAddress> seen = new ArrayBlockingQueue<>(4);
    private final BlockingQueue<SourcedAddress> joined = new ArrayBlockingQueue<>(4);
    private NetherNetHTTPServerSignaling signaling;

    @AfterEach
    void tearDown() {
        if (signaling != null) {
            signaling.close();
        }
        group.shutdownGracefully();
    }

    private int start(List<String> proxies) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        signaling = new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("example.test"))
                .setForwardedForProxies(proxies)
                .setTokenTrust(TokenTrust.ANY)
                .setMotdProvider((host, remoteAddress, client) -> {
                    seen.offer(remoteAddress);
                    return PongData.DEFAULT;
                })
                .build();
        signaling.setNewConnectionHandler((id, networkId, offer, address, player) -> joined.offer(address));

        signaling.bind(new InetSocketAddress("127.0.0.1", port), null, group.next());
        return port;
    }

    private void get(int port, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/join"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private InetSocketAddress observed() throws Exception {
        InetSocketAddress address = seen.poll(10, TimeUnit.SECONDS);
        assertNotNull(address, "the signaling server never reported a client address");
        return address;
    }

    @Test
    void namesTheForwarderAJoinCameThrough() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        SourcedAddress address = TestHttp.join(port, null, "X-Forwarded-For: 203.0.113.7\r\n", joined);
        assertNotNull(address, "the join never arrived");
        assertEquals("203.0.113.7", address.address().getAddress().getHostAddress());
        assertEquals(AddressSource.SIGNALING_FORWARDED_FOR, address.source());
        assertEquals("127.0.0.1", address.via().getAddress().getHostAddress());
    }

    @Test
    void anUntrustedForwardedForLeavesTheJoinItsOwn() throws Exception {
        int port = start(List.of("10.0.0.0/8"));
        SourcedAddress address = TestHttp.join(port, null, "X-Forwarded-For: 203.0.113.7\r\n", joined);
        assertNotNull(address, "the join never arrived");
        assertEquals(AddressSource.SIGNALING, address.source());
        assertEquals("127.0.0.1", address.address().getAddress().getHostAddress());
        assertNull(address.via());
    }

    @Test
    void honoursForwardedForFromATrustedProxy() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "203.0.113.7");

        assertEquals("203.0.113.7", observed().getAddress().getHostAddress());
    }

    /** A proxy appends to the header the client sent, so entries left of its own are the client's to choose. */
    @Test
    void takesTheEntryTheTrustedProxyAppended() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "203.0.113.7, 198.51.100.2");

        assertEquals("198.51.100.2", observed().getAddress().getHostAddress());
    }

    @Test
    void readsNothingLeftOfTheClient() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "not an address, 198.51.100.2");

        assertEquals("198.51.100.2", observed().getAddress().getHostAddress());
    }

    @Test
    void walksPastTrustedProxiesInAChain() throws Exception {
        int port = start(List.of("127.0.0.0/8", "10.0.0.0/8"));
        get(port, "203.0.113.7, 198.51.100.2, 10.1.2.3");

        assertEquals("198.51.100.2", observed().getAddress().getHostAddress());
    }

    @Test
    void takesTheLeftmostWhenEveryEntryIsTrusted() throws Exception {
        int port = start(List.of("127.0.0.0/8", "10.0.0.0/8"));
        get(port, "10.0.0.9, 10.1.2.3");

        assertEquals("10.0.0.9", observed().getAddress().getHostAddress());
    }

    @Test
    void anEntryAProxyAppendedThatIsNoAddressLeavesThePeer() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "203.0.113.7, unknown");

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }

    @Test
    void ignoresForwardedForFromAnUntrustedSource() throws Exception {
        int port = start(List.of("10.0.0.0/8"));
        get(port, "203.0.113.7");

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }

    @Test
    void ignoresForwardedForWhenNoProxyIsTrusted() throws Exception {
        int port = start(List.of());
        get(port, "203.0.113.7");

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }

    @Test
    void ignoresAHostnameRatherThanResolvingIt() throws Exception {
        // Resolving here would block the signaling event loop on DNS and let the header stand for
        // whatever the answer happened to be
        int port = start(List.of("127.0.0.0/8"));
        get(port, "localhost");

        // The name would otherwise survive into the address the transport reports
        assertEquals("127.0.0.1", observed().getHostString());
    }

    @Test
    void ignoresAValueThatIsNotAnAddressAtAll() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "not an address");

        InetSocketAddress seen = observed();
        assertFalse(seen.isUnresolved(), "an unresolved address would reach the transport as a null host");
        assertEquals("127.0.0.1", seen.getAddress().getHostAddress());
    }

    @Test
    void ignoresAHeaderThatIsOnlyWhitespace() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "   ");

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }

    @Test
    void honoursAnIpv6LiteralInBrackets() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "[2001:db8::1]");

        assertEquals("2001:db8:0:0:0:0:0:1", observed().getAddress().getHostAddress());
    }

    @Test
    void fallsBackToThePeerWhenTheHeaderIsAbsent() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, null);

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }
}
