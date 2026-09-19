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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptOfferTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private NetherNetHTTPSignaling signaling;

    @AfterEach
    void tearDown() {
        if (signaling != null) {
            signaling.close();
        }
        group.shutdownGracefully();
    }

    private NetherNetHTTPSignaling build(boolean serveHttp, boolean allow) throws Exception {
        signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setTokenTrust(TokenTrust.ANY)
                .setServeHttp(serveHttp)
                .setPlayerFilter((host, player) -> allow ? null : NetherNetHTTPSignaling.JoinRefusal.REJECTED)
                .build();
        signaling.setNewConnectionHandler((id, networkId, payload, address, player) -> { });
        return signaling;
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    @Test
    void bindsNothingWhenHttpIsOff() throws Exception {
        int port = freePort();
        build(false, true).bind(new InetSocketAddress("127.0.0.1", port), group.next());

        // The port is still free, so nothing is listening on it
        try (ServerSocket taken = new ServerSocket(port)) {
            assertFalse(taken.isClosed());
        }
    }

    @Test
    void answersAnOfferHandedInFromOutside() throws Exception {
        NetherNetHTTPSignaling s = build(false, true);
        s.bind(new InetSocketAddress("127.0.0.1", freePort()), group.next());

        CompletableFuture<String> answer =
                s.acceptOffer("42", TestOffers.selfSigned(), new InetSocketAddress("203.0.113.7", 1234), null);
        // Nothing produced an answer yet, so it is still pending rather than failed
        assertFalse(answer.isDone());

        s.sendFullSdp("42", "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
        assertTrue(answer.get(10, TimeUnit.SECONDS).contains("m=application"));
    }

    @Test
    void reportsWhyAnOfferWasRejected() throws Exception {
        NetherNetHTTPSignaling s = build(false, false);
        s.bind(new InetSocketAddress("127.0.0.1", freePort()), group.next());

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> s.acceptOffer("42", TestOffers.selfSigned(), null, null).get(10, TimeUnit.SECONDS));

        NetherNetHTTPSignaling.OfferRejected rejected =
                assertInstanceOf(NetherNetHTTPSignaling.OfferRejected.class, e.getCause());
        assertEquals(NetherNetHTTPSignaling.JoinRefusal.REJECTED, rejected.refusal());
    }

    @Test
    void reportsAnUnusableIdentity() throws Exception {
        NetherNetHTTPSignaling s = build(false, true);
        s.bind(new InetSocketAddress("127.0.0.1", freePort()), group.next());

        ExecutionException e = assertThrows(ExecutionException.class,
                () -> s.acceptOffer("42", "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n", null, null)
                        .get(10, TimeUnit.SECONDS));

        NetherNetHTTPSignaling.OfferRejected rejected =
                assertInstanceOf(NetherNetHTTPSignaling.OfferRejected.class, e.getCause());
        assertEquals(NetherNetHTTPSignaling.JoinRefusal.INVALID_IDENTITY, rejected.refusal());
    }
}
