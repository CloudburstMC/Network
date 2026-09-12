package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import javax.net.ssl.SSLSocket;
import java.security.cert.X509Certificate;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the signalling endpoint answers to requests it should not serve. Anyone on the internet can
 * reach this, so the refusals matter as much as the join it exists for.
 */
class HttpSignalingRequestTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1);
    private NetherNetHTTPSignaling signaling;
    private int port;

    @AfterEach
    void tearDown() {
        if (this.signaling != null) {
            this.signaling.close();
        }
        this.group.shutdownGracefully();
    }

    private NetherNetHTTPSignaling.Builder builder() throws Exception {
        return new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setTrustedProxies(List.of())
                .setTokenTrust(TokenTrust.ANY);
    }

    private void start(NetherNetHTTPSignaling.Builder builder) throws Exception {
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
        this.start(this.builder().setPlayerFilter((host, player) -> false));

        assertEquals(403, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
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
    void refusesAnOfferWithNothingBehindTheSignalling() throws Exception {
        // Bound, but no transport is listening for connections yet
        this.start(this.builder());

        assertEquals(503, this.status("POST", "/v1/join/42", TestOffers.selfSigned()));
    }

    @Test
    void answersAnOfferTheTransportAcceptsFor() throws Exception {
        this.start(this.builder());
        this.signaling.setNewConnectionHandler((connectionId, networkId, payload, clientAddress, player) ->
                this.signaling.sendFullSdp(networkId, ANSWER));

        HttpResponse<String> response = this.send("POST", "/v1/join/42", TestOffers.selfSigned());

        assertEquals(200, response.statusCode());
        assertEquals("application/sdp", response.headers().firstValue("content-type").orElse(""));
        assertTrue(response.body().startsWith("v=0"), "the answer is the SDP the transport produced");
    }

    @Test
    void ignoresAnAnswerNobodyIsWaitingFor() throws Exception {
        this.start(this.builder());

        // A late or stray answer must not disturb the endpoint
        this.signaling.sendFullSdp("999", ANSWER);

        assertEquals(200, this.status("GET", "/v1/join", null));
    }

    @Test
    void servesBothSchemesOnTheOnePortClientsLookAt() throws Exception {
        // A client that finds no TLS falls back to plaintext on the same port, so serving TLS must
        // not take plaintext away
        SelfSignedCertificate certificate = new SelfSignedCertificate("example.test");
        this.start(this.builder().setSslContext(
                SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build()));

        assertEquals(200, this.status("GET", "/v1/join", null), "plaintext still reaches it");
        assertEquals(200, this.secureStatus(), "and so does TLS");
    }

    @Test
    void refusesTlsWhenItServesNone() throws Exception {
        // Without a certificate a handshake has to be turned away rather than read as a request
        this.start(this.builder());

        assertThrows(IOException.class, this::secureStatus);
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
