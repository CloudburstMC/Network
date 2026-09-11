package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
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
    private NetherNetHTTPSignaling signaling;

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

        signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setTrustedProxies(trusted)
                .setProxyProtocol(proxyProtocol)
                .setMotdProvider((host, remoteAddress) -> {
                    seen.offer(remoteAddress);
                    return NetherNetServerSignaling.PongData.DEFAULT;
                })
                .build();
        signaling.bind(new InetSocketAddress("127.0.0.1", port), group.next());
        return port;
    }

    /** A PROXY v2 header declaring an IPv4 source. */
    private static byte[] v2Header(String source, int sourcePort) {
        byte[] sig = {0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};
        byte[] out = new byte[sig.length + 4 + 12];
        System.arraycopy(sig, 0, out, 0, sig.length);
        out[12] = 0x21;                     // version 2, PROXY
        out[13] = 0x11;                     // TCP over IPv4
        out[14] = 0;
        out[15] = 12;                       // address block length
        String[] octets = source.split("\\.");
        for (int i = 0; i < 4; i++) {
            out[16 + i] = (byte) Integer.parseInt(octets[i]);
        }
        out[20] = 127; out[21] = 0; out[22] = 0; out[23] = 1;   // destination
        out[24] = (byte) (sourcePort >> 8);
        out[25] = (byte) sourcePort;
        out[26] = (byte) (19190 >> 8);
        out[27] = (byte) 19190;
        return out;
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
        assertNotNull(address, "the signalling server never reported a client address");
        return address;
    }

    @Test
    void readsAProxyHeaderFromATrustedProxy() throws Exception {
        int port = start(List.of("127.0.0.0/8"), true);
        request(port, v2Header("203.0.113.7", 5555));

        assertEquals("203.0.113.7", observed().getAddress().getHostAddress());
    }

    @Test
    void neverBelievesAProxyHeaderFromAnUntrustedSource() throws Exception {
        // Trusting a range this connection is not in, so the header must not be believed
        int port = start(List.of("10.0.0.0/8"), true);
        request(port, v2Header("203.0.113.7", 5555));

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
