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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Serves a status other than 200, the way a list behind an outage or a login page would. */
    private String serveStatus(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/list", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/list";
    }

    @Test
    void widensTrustNoFurtherWhenAListCannotBeFetched() throws Exception {
        // An unreachable list must leave the configured entries standing and add nothing
        IpRangeSet trusted = IpRangeSet.parse(
                TrustedProxies.expand(List.of("192.0.2.1", "http://127.0.0.1:1/list"), true));

        assertTrue(trusted.contains(InetAddress.getByName("192.0.2.1")));
        assertFalse(trusted.contains(InetAddress.getByName("198.51.100.7")));
    }

    @Test
    void widensTrustNoFurtherWhenAListAnswersWithAnError() throws Exception {
        String url = serveStatus(503);

        IpRangeSet trusted = IpRangeSet.parse(TrustedProxies.expand(List.of("192.0.2.1", url), true));

        assertTrue(trusted.contains(InetAddress.getByName("192.0.2.1")));
        assertTrue(IpRangeSet.parse(TrustedProxies.expand(List.of(url), true)).isEmpty());
    }

    @Test
    void keepsEntriesThatAreNotUrls() {
        assertEquals(List.of("192.0.2.1", "10.0.0.0/8"),
                TrustedProxies.expand(Arrays.asList("192.0.2.1", null, "", "  ", " 10.0.0.0/8 "), false));
    }

    @Test
    void fetchesOverBothSchemes() throws Exception {
        String url = serve("198.51.100.7\n");

        assertEquals(List.of("198.51.100.7"), TrustedProxies.expand(List.of(url), true));
        // https reaches the fetch too, where it fails closed rather than being treated as an address
        assertEquals(List.of(), TrustedProxies.expand(List.of("https://127.0.0.1:1/list"), true));
    }

    @Test
    void resolvesOnceAndForgetsOnlyWhenAsked() throws Exception {
        String url = serve("198.51.100.7\n");

        IpRangeSet first = TrustedProxies.parse(List.of(url));
        assertTrue(first.contains(InetAddress.getByName("198.51.100.7")));

        // A second listener starting must not fetch again, it takes what the first resolved
        assertSame(first, TrustedProxies.parse(List.of("192.0.2.1")));

        TrustedProxies.invalidate();
        assertFalse(TrustedProxies.parse(List.of("192.0.2.1"))
                .contains(InetAddress.getByName("198.51.100.7")));
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
