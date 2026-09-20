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

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPServerSignaling.JoinRefusal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import javax.net.ssl.SSLSocket;
import java.security.cert.X509Certificate;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the signaling endpoint answers to requests it should not serve. Anyone on the internet can
 * reach this, so the refusals matter as much as the join it exists for.
 */
class HttpSignalingRequestTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private NetherNetHTTPServerSignaling signaling;
    private int port;

    @AfterEach
    void tearDown() {
        if (this.signaling != null) {
            this.signaling.close();
        }
        this.group.shutdownGracefully();
    }

    private NetherNetHTTPServerSignaling.Builder builder() throws Exception {
        return new NetherNetHTTPServerSignaling.Builder()
                .setIdentity(OperatorIdentity.generate("example.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY);
    }

    /** Serves TLS from a certificate no client trusts, which is all a scheme check needs. */
    private NetherNetHTTPServerSignaling.Builder tlsBuilder() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate("example.test");
        return this.builder().setSslContext(
                SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build());
    }

    private void start(NetherNetHTTPServerSignaling.Builder builder) throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            this.port = probe.getLocalPort();
        }
        this.signaling = builder.build();
        this.signaling.bind(new InetSocketAddress("127.0.0.1", this.port), this.group.next());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int status(String method, String path, String body) throws Exception {
        return this.send(method, path, body).statusCode();
    }

    @Test
    void servesTheStatusCheck() throws Exception {
        this.start(this.builder());

        HttpResponse<String> response = this.send("GET", "/v1/join", null);

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void servesFromAChannelOnAnyKindOfLoop() throws Exception {
        // The listener brings its own NIO loop, so the channel's loop need not be one
        DefaultEventLoopGroup plain = new DefaultEventLoopGroup(1);
        try {
            try (ServerSocket probe = new ServerSocket(0)) {
                this.port = probe.getLocalPort();
            }
            this.signaling = this.builder().build();
            this.signaling.bind(new InetSocketAddress("127.0.0.1", this.port), plain.next());

            assertEquals(200, this.status("GET", "/v1/join", null));
        } finally {
            plain.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void refusesTheWrongMethodForEachRoute() throws Exception {
        this.start(this.builder());

        assertEquals(405, this.status("POST", "/v1/join", ""), "the status check is a GET");
        assertEquals(405, this.status("GET", "/v1/join/42", null), "a join is a POST");
    }

    @Test
    void refusesRoutesItDoesNotServe() throws Exception {
        this.start(this.builder());

        assertEquals(404, this.status("GET", "/", null));
        assertEquals(404, this.status("GET", "/v1", null));
        assertEquals(404, this.status("POST", "/v1/joining/42", ""), "a prefix is not a route");
        assertEquals(404, this.status("POST", "/v1/join/", ""), "a join needs a network id");
        assertEquals(404, this.status("POST", "/v1/join/42/extra", ""), "and only one segment of it");
    }

    @Test
    void servesNoStatusWhenTheHostDoesNotTakeNetherNet() throws Exception {
        // The endpoint reads as absent, which is what sends the client back to RakNet
        this.start(this.builder().setMotdProvider((host, client) -> null));

        assertEquals(404, this.status("GET", "/v1/join", null));
    }

    @Test
    void answersWithAnErrorWhenTheMotdProviderFails() throws Exception {
        this.start(this.builder().setMotdProvider((host, client) -> {
            throw new IllegalStateException("no status today");
        }));

        assertEquals(500, this.status("GET", "/v1/join", null));
    }

    @Test
    void refusesAnOfferCarryingNoUsableIdentity() throws Exception {
        this.start(this.builder());

        assertEquals(401, this.status("POST", "/v1/join/42", "not an sdp offer at all"));
    }

    @Test
    void refusesAnOfferTheFilterTurnsAway() throws Exception {
        this.start(this.builder().setPlayerFilter((host, player) -> JoinRefusal.REJECTED));

        HttpResponse<String> response = this.send("POST", "/v1/join/42", TestOffers.selfSigned());

        assertEquals(403, response.statusCode());
        assertTrue(response.body().isEmpty(), "a refusal carries no body of its own");
    }

    @Test
    void refusesAnOfferTheFilterHasNoRoomFor() throws Exception {
        // A host with no room says so itself, rather than turning the player away as denied
        this.start(this.builder().setPlayerFilter((host, player) -> JoinRefusal.FULL));

        assertEquals(503, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
    }

    @Test
    void letsAHostAnswerARefusalOfItsOwn() throws Exception {
        // A host is not held to the refusals this signaling raises
        this.start(this.builder()
                .setPlayerFilter((host, player) -> new JoinRefusal(HttpResponseStatus.TOO_MANY_REQUESTS)));

        assertEquals(429, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
        assertThrows(IllegalArgumentException.class, () -> new JoinRefusal(HttpResponseStatus.OK),
                "any status but a 2xx, which would tell the client the join worked");
    }

    @Test
    void refusesAnOfferWhenAFilterCannotDecide() throws Exception {
        // A filter that throws must turn the player away, never let them through
        this.start(this.builder().setPlayerFilter((host, player) -> {
            throw new IllegalStateException("filter is broken");
        }));

        assertEquals(403, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
    }

    @Test
    void refusesAnOfferWithNothingBehindTheSignaling() throws Exception {
        // Bound, but no transport is listening for connections yet, which is not the host
        // being full
        this.start(this.builder());

        assertEquals(500, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
    }

    @Test
    void answersAnOfferTheTransportAcceptsFor() throws Exception {
        this.start(this.builder());
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) ->
                this.signaling.sendDescription(networkId, ANSWER));

        HttpResponse<String> response = this.send("POST", "/v1/join/42", TestOffers.selfSigned());

        assertEquals(200, response.statusCode());
        assertEquals("application/sdp", response.headers().firstValue("content-type").orElse(""));
        assertTrue(response.body().startsWith("v=0"), "the answer is the SDP the transport produced");
    }

    @Test
    void ignoresAnAnswerNobodyIsWaitingFor() throws Exception {
        this.start(this.builder());

        // A late or stray answer must not disturb the endpoint
        this.signaling.sendDescription("999", ANSWER);

        assertEquals(200, this.status("GET", "/v1/join", null));
    }

    @Test
    void servesBothSchemesOnTheOnePortClientsLookAt() throws Exception {
        // A client that finds no TLS falls back to plaintext on the same port, so serving TLS must
        // not take plaintext away
        this.start(this.tlsBuilder().setRequiresTls(false));

        assertEquals(200, this.status("GET", "/v1/join", null), "plaintext still reaches it");
        assertEquals(200, this.secureStatus(), "and so does TLS");
    }

    @Test
    void refusesPlaintextWhileItServesTls() throws Exception {
        // A peer that cannot reach TLS is sent away to whatever transport it has left, rather than
        // joining over plaintext, where a client shows its first use trust prompt
        this.start(this.tlsBuilder());

        HttpResponse<String> response = this.send("GET", "/v1/join", null);

        assertEquals(426, response.statusCode());
        assertEquals("TLS/1.2, HTTP/1.1", response.headers().firstValue("upgrade").orElse(""),
                "a 426 has to name what to upgrade to");
        assertEquals(426, this.status("POST", "/v1/join/42", TestOffers.selfSigned()),
                "an offer is refused the same way, no peer is allocated for it");
        assertEquals(200, this.secureStatus(), "TLS is served the status it asked for");
    }

    @Test
    void refusesTlsWhenItServesNone() throws Exception {
        // Without a certificate a handshake has to be turned away rather than read as a request
        this.start(this.builder());

        assertThrows(IOException.class, this::secureStatus);
    }

    @Test
    void answersASecondRequestOnTheConnectionItWasAskedToKeep() throws Exception {
        // A real client sends its status check and its join down one connection. Closing after the
        // first leaves the second unanswered: TCP takes the bytes into a half closed socket and the
        // client waits forever for a reply that cannot come.
        this.start(this.builder());
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) ->
                this.signaling.sendDescription(networkId, ANSWER));

        try (Socket socket = new Socket("127.0.0.1", this.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));

            out.write(("GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Keep-Alive\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertEquals(200, readStatus(in), "the status check");
            String body = readBody(in);

            byte[] offer = TestOffers.selfSigned().getBytes(StandardCharsets.US_ASCII);
            out.write(("POST /v1/join/42 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Keep-Alive\r\n"
                    + "Content-Type: application/sdp\r\nContent-Length: " + offer.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(offer);
            out.flush();

            assertEquals(200, readStatus(in), "the join, on the same connection");
            assertFalse(body.isEmpty(), "the status check still carried its body");
        }
    }

    @Test
    void tellsAClientWhenItMayNotKeepTheConnection() throws Exception {
        // HTTP/1.1 keeps a connection unless the response says otherwise, so a server that closes
        // has to say so or the client will reuse a socket that is already gone
        this.start(this.builder());

        try (Socket socket = new Socket("127.0.0.1", this.port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(
                    "GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals(200, readStatus(in));
            assertTrue(this.headers(in).contains("connection: close"),
                    "a client that will not reuse the socket is told the server agrees");
        }
    }

    private static int readStatus(BufferedReader in) throws IOException {
        String status = in.readLine();
        if (status == null) {
            throw new IOException("the listener closed without answering");
        }
        return Integer.parseInt(status.split(" ")[1]);
    }

    /** Reads the header block, lower cased so a comparison does not depend on how it was spelled. */
    private List<String> headers(BufferedReader in) throws IOException {
        List<String> headers = new ArrayList<>();
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            headers.add(line.toLowerCase(Locale.ROOT));
        }
        return headers;
    }

    /** Reads the headers, then exactly the body they declare. */
    private String readBody(BufferedReader in) throws IOException {
        int length = 0;
        for (String header : this.headers(in)) {
            if (header.startsWith("content-length:")) {
                length = Integer.parseInt(header.substring("content-length:".length()).trim());
            }
        }
        char[] body = new char[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) {
                throw new IOException("the body ended early");
            }
            read += n;
        }
        return new String(body);
    }

    @Test
    void holdsOnlySoManyConnectionsForOneAddress() throws Exception {
        // A kept connection costs a socket until it goes idle, so one peer must not be able to
        // take as many as the host has descriptors
        this.start(this.builder().setMaxConnectionsPerAddress(2));

        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                held.add(this.keptConnection());
            }
            try (Socket refused = new Socket("127.0.0.1", this.port)) {
                refused.setSoTimeout(10_000);
                assertEquals(-1, refused.getInputStream().read(), "the third is closed unanswered");
            }
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }

        // Closing them gives the allowance back, once the server has noticed they went
        try (Socket reused = this.eventuallyKept()) {
            assertNotNull(reused, "a closed connection frees its place");
        }
    }

    @Test
    void countsNoConnectionAgainstATrustedProxy() throws Exception {
        // Every client behind a proxy shares its address, so counting them together would throttle
        // all of them at once
        this.start(this.builder().setMaxConnectionsPerAddress(1).setTrustedProxies(List.of("127.0.0.1")));

        try (Socket first = this.keptConnection(); Socket second = this.keptConnection()) {
            assertNotNull(first);
            assertNotNull(second);
        }
    }

    @Test
    void refusesAJoinWhenTooManyAreAlreadyWaiting() throws Exception {
        // The handler accepts the offer and never answers, so the join stays pending
        this.start(this.builder().setMaxPendingJoins(1));
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) -> {
        });

        Thread first = new Thread(() -> {
            try {
                this.status("POST", "/v1/join/1", TestOffers.selfSigned());
            } catch (Exception ignored) {
                // The test ends while it is still waiting for an answer
            }
        });
        first.setDaemon(true);
        first.start();

        // Wait for it to be registered rather than guessing at a delay
        for (int i = 0; i < 100 && this.signaling.pendingJoins() < 1; i++) {
            Thread.sleep(20);
        }
        assertEquals(1, this.signaling.pendingJoins(), "the first join is waiting for an answer");
        assertEquals(503, this.status("POST", "/v1/join/2", TestOffers.selfSigned()),
                "the second is refused rather than opening another peer connection");
    }

    /** The refusal a turned away offer carries, for a future nobody is going to wait on. */
    private static JoinRefusal refusalOf(java.util.concurrent.CompletableFuture<String> refused) {
        Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class, refused::get).getCause();
        return ((NetherNetHTTPServerSignaling.OfferRejected) cause).refusal();
    }

    /** Retries until the allowance frees up, since a peer closing is not instant on this side. */
    private Socket eventuallyKept() throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return this.keptConnection();
            } catch (IOException refused) {
                if (attempt >= 100) {
                    throw refused;
                }
                Thread.sleep(20);
            }
        }
    }

    @Test
    void refusesDuplicatePendingNetworkIdsWithoutReplacingTheirAnswer() throws Exception {
        this.start(this.builder().setMaxPendingJoins(2));
        var created = new java.util.concurrent.atomic.AtomicInteger();
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) ->
                created.incrementAndGet());
        String offer = TestOffers.selfSigned();
        var first = this.signaling.acceptOffer("same-id", offer, null, "example.test");
        var duplicate = this.signaling.acceptOffer("same-id", offer, null, "example.test");
        // Offers are validated and admitted in order, off the caller's thread, so the second is
        // the one refused, once it has been looked at
        assertThrows(ExecutionException.class, () -> duplicate.get(5, java.util.concurrent.TimeUnit.SECONDS),
                "a duplicate cannot replace a pending answer");
        assertEquals(JoinRefusal.DUPLICATE, refusalOf(duplicate), "and it is not refused as a full host");
        assertFalse(first.isDone(), "the original offer keeps its pending answer");
        assertEquals(1, created.get(), "only the original offer may allocate a peer");
        this.signaling.sendDescription("same-id", ANSWER);
        assertTrue(first.get(2, java.util.concurrent.TimeUnit.SECONDS).startsWith("v=0"));
    }

    @Test
    void refusesConcurrentDuplicateOffersBeforeCreatingPeers() throws Exception {
        this.start(this.builder().setMaxPendingJoins(2));
        var created = new java.util.concurrent.atomic.AtomicInteger();
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) ->
                created.incrementAndGet());
        String offer = TestOffers.selfSigned();
        // Two callers at once: admission is serialized on the loop, so exactly one of them wins
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                this.signaling.acceptOffer("same-id", offer, null, "example.test"));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                this.signaling.acceptOffer("same-id", offer, null, "example.test"));
        var a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        var b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!(a.isCompletedExceptionally() || b.isCompletedExceptionally()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(a.isCompletedExceptionally() ^ b.isCompletedExceptionally(), "exactly one is refused");
        assertEquals(JoinRefusal.DUPLICATE, refusalOf(a.isCompletedExceptionally() ? a : b));
        assertEquals(1, created.get(), "concurrent duplicates must not allocate a second peer");
        this.signaling.sendDescription("same-id", ANSWER);
        var accepted = a.isCompletedExceptionally() ? b : a;
        assertTrue(accepted.get(2, java.util.concurrent.TimeUnit.SECONDS).startsWith("v=0"));
    }

    /** A connection that has made one request and been told it may stay. */
    private Socket keptConnection() throws Exception {
        Socket socket = new Socket("127.0.0.1", this.port);
        socket.setSoTimeout(10_000);
        socket.getOutputStream().write(
                "GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: Keep-Alive\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        assertEquals(200, readStatus(in));
        this.readBody(in);
        return socket;
    }

    /** A GET over TLS, trusting whatever the listener presents, since this test made it. */
    private int secureStatus() throws Exception {
        SSLContext trusting = SSLContext.getInstance("TLS");
        trusting.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);

        try (SSLSocket socket = (SSLSocket) trusting.getSocketFactory().createSocket("127.0.0.1", this.port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            socket.getOutputStream().write(
                    "GET /v1/join HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String status = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if (status == null) {
                throw new IOException("the listener closed without answering");
            }
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static final String ANSWER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\n";
}
