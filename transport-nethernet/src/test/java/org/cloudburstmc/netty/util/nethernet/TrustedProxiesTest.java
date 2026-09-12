package org.cloudburstmc.netty.util.nethernet;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxiesTest {

    private HttpServer server;

    @BeforeEach
    @AfterEach
    void reset() {
        TrustedProxies.invalidate();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String serve(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/list", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/list";
    }

    @Test
    void readsOneAddressPerLineFromAUrl() throws Exception {
        String url = serve("198.51.100.7\n# a comment\n\n203.0.113.0/24\n");

        IpRangeSet trusted = TrustedProxies.parse(List.of("192.0.2.1", url));

        assertTrue(trusted.contains(InetAddress.getByName("192.0.2.1")));
        assertTrue(trusted.contains(InetAddress.getByName("198.51.100.7")));
        assertTrue(trusted.contains(InetAddress.getByName("203.0.113.9")));
        // Comments and blank lines are not addresses
        assertFalse(trusted.contains(InetAddress.getByName("198.51.100.8")));
    }

    @Test
    void refusesToFetchWhenTheHostHasTurnedItOff() throws Exception {
        String url = serve("198.51.100.7\n");

        IpRangeSet trusted = IpRangeSet.parse(TrustedProxies.expand(List.of("192.0.2.1", url), false));

        // The configured entry still counts, the fetched one contributes nothing
        assertTrue(trusted.contains(InetAddress.getByName("192.0.2.1")));
        assertFalse(trusted.contains(InetAddress.getByName("198.51.100.7")));
    }
}
