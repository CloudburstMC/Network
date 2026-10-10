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
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProxyProtocolTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final BlockingQueue<InetSocketAddress> seen = new ArrayBlockingQueue<>(4);
    private NetherNetHTTPServerSignaling signaling;

    @AfterEach
    void tearDown() {
        if (signaling != null) {
            signaling.close();
        }
        group.shutdownGracefully();
    }

    private int start(List<String> trusted, boolean proxyProtocol) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        signaling = new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("example.test"))
                .setTrustedProxies(trusted)
                .setProxyProtocol(proxyProtocol)
                .setMotdProvider((host, remoteAddress, client) -> {
                    seen.offer(remoteAddress);
                    return PongData.DEFAULT;
                })
                .build();
        signaling.bind(new InetSocketAddress("127.0.0.1", port), group.next());
        return port;
    }

    private void request(int port, byte[] prefix) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            if (prefix != null) {
                out.write(prefix);
            }
            out.write(("GET /v1/join HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.getInputStream().read();
        }
    }

    private InetSocketAddress observed() throws Exception {
        InetSocketAddress address = seen.poll(10, TimeUnit.SECONDS);
        assertNotNull(address, "the signaling server never reported a client address");
        return address;
    }

    @Test
    void readsAProxyHeaderFromATrustedProxy() throws Exception {
        int port = start(List.of("127.0.0.0/8"), true);
        request(port, TestHttp.proxyV2Header("203.0.113.7", 5555));

        assertEquals("203.0.113.7", observed().getAddress().getHostAddress());
    }

    @Test
    void neverBelievesAProxyHeaderFromAnUntrustedSource() throws Exception {
        // Trusting a range this connection is not in, so the header must not be believed
        int port = start(List.of("10.0.0.0/8"), true);
        request(port, TestHttp.proxyV2Header("203.0.113.7", 5555));

        // The header is left in the stream, so the request does not parse as HTTP and is never
        // served. What matters is that the address it claimed is never taken for the client's.
        InetSocketAddress address = seen.poll(2, TimeUnit.SECONDS);
        if (address != null) {
            assertEquals("127.0.0.1", address.getAddress().getHostAddress());
        }
    }

    @Test
    void servesPlainHttpFromATrustedProxyToo() throws Exception {
        int port = start(List.of("127.0.0.0/8"), true);
        request(port, null);

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }

    @Test
    void ignoresProxyProtocolWhenItIsOff() throws Exception {
        int port = start(List.of("127.0.0.0/8"), false);
        request(port, null);

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }
}
