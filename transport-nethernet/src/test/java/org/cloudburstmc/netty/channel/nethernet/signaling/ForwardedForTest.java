package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ForwardedForTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private final BlockingQueue<InetSocketAddress> seen = new ArrayBlockingQueue<>(4);
    private NetherNetHTTPSignaling signaling;

    @AfterEach
    void tearDown() {
        if (signaling != null) {
            signaling.close();
        }
        group.shutdownGracefully();
    }

    private int start(List<String> trustedProxies) throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setTrustedProxies(trustedProxies)
                .setMotdProvider((host, remoteAddress) -> {
                    seen.offer(remoteAddress);
                    return NetherNetServerSignaling.PongData.DEFAULT;
                })
                .build();

        signaling.bind(new InetSocketAddress("127.0.0.1", port), group.next());
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
        assertNotNull(address, "the signalling server never reported a client address");
        return address;
    }

    @Test
    void honoursForwardedForFromATrustedProxy() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "203.0.113.7");

        assertEquals("203.0.113.7", observed().getAddress().getHostAddress());
    }

    @Test
    void takesTheLeftmostEntryOfAChain() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, "203.0.113.7, 198.51.100.2");

        assertEquals("203.0.113.7", observed().getAddress().getHostAddress());
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
    void fallsBackToThePeerWhenTheHeaderIsAbsent() throws Exception {
        int port = start(List.of("127.0.0.0/8"));
        get(port, null);

        assertEquals("127.0.0.1", observed().getAddress().getHostAddress());
    }
}
