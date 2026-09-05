package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the per connection protocol selection: one port serves TLS and
 * plaintext together when a certificate is available, and closes TLS
 * speakers cleanly when none is. The plaintext HTTP path itself is covered
 * by {@link NetherNetHttpSignalingTest}.
 */
class NetherNetHttpSignalingProtocolTest {

    private MultiThreadIoEventLoopGroup group;
    private NetherNetHttpSignaling signaling;

    private InetSocketAddress bind(SslContext sslContext) throws Exception {
        group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        signaling = new NetherNetHttpSignaling(sslContext, group);
        signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp) -> {
        });
        signaling.bind(new InetSocketAddress("127.0.0.1", 0));
        return signaling.boundAddress();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (signaling != null) {
            signaling.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /** A server side context from the checked in self signed test pair. */
    private static SslContext testServerContext() throws Exception {
        try (InputStream cert = NetherNetHttpSignalingProtocolTest.class.getResourceAsStream("/cert.pem");
             InputStream key = NetherNetHttpSignalingProtocolTest.class.getResourceAsStream("/key.pem")) {
            return SslContextBuilder.forServer(cert, key).build();
        }
    }

    /**
     * Speaks HTTPS to the bound listener with a trust-everything socket
     * (java.net.http enforces SAN matching even with a lenient trust
     * manager, and the test certificate is deliberately mismatched) and
     * returns the response status code.
     */
    private static int getOverTls(InetSocketAddress bound, String path) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (Socket socket = context.getSocketFactory().createSocket("127.0.0.1", bound.getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))
                    .readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Test
    void tlsSpeakerAtCertificatelessServerIsClosedWithoutAnHttpResponse() throws Exception {
        InetSocketAddress bound = bind(null);
        try (Socket socket = new Socket("127.0.0.1", bound.getPort())) {
            socket.setSoTimeout(5000);
            // The first five bytes of a TLS 1.2 ClientHello record.
            socket.getOutputStream().write(new byte[]{0x16, 0x03, 0x01, 0x00, 0x05});
            socket.getOutputStream().flush();
            // The server must close without writing anything: an HTTP
            // response here would be garbage to a TLS client.
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void plaintextHttpIsServedAlongsideTls() throws Exception {
        InetSocketAddress bound = bind(testServerContext());
        String base = "127.0.0.1:" + bound.getPort();

        // The TLS path works against the configured certificate.
        assertEquals(200, getOverTls(bound, "/v1/join"));

        // And the same listener still answers plain HTTP: the fallback for
        // clients whose TLS attempt failed against a broken certificate.
        HttpResponse<String> overPlaintext = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + base + "/v1/join")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, overPlaintext.statusCode());
    }
}
