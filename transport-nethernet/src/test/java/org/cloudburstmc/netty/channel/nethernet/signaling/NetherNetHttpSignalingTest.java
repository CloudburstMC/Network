package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSessionListener;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the HTTP signaling front end against the real netty stack and
 * server channel, with the WebRTC engine replaced by a scripted backend: an
 * accepted offer produces a canned full ICE answer on a separate thread,
 * mirroring how the engine reports the answer after gathering completes.
 */
class NetherNetHttpSignalingTest {

    private static final String CANNED_ANSWER = "v=0\r\no=- 0 2 IN IP4 127.0.0.1\r\na=candidate:1 1 udp 2122260223 127.0.0.1 55555 typ host\r\n";

    private static NioEventLoopGroup group;
    private static NetherNetHttpSignaling signaling;
    private static Channel serverChannel;
    private static ScriptedBackend backend;
    private static HttpClient client;
    private static String baseUrl;

    /** Records accepts and answers each offer with the canned SDP. */
    private static final class ScriptedBackend implements WebRtcServerBackend {
        final List<Boolean> fullIceFlags = new CopyOnWriteArrayList<>();
        volatile boolean answerOffers = true;

        @Override
        public WebRtcSession accept(String offerSdp, List<NetherNetSignaling.IceServerInfo> iceServers,
                                    WebRtcSessionListener listener, boolean fullIceAnswer) {
            fullIceFlags.add(fullIceAnswer);
            if (answerOffers) {
                // Engine threads report the answer asynchronously; model that.
                CompletableFuture.runAsync(() -> listener.onAnswerReady(CANNED_ANSWER));
            }
            return new WebRtcSession() {
                @Override
                public void send(ByteBuffer data) {
                }

                @Override
                public void addRemoteCandidate(String candidateSdp) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void close() {
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        group = new NioEventLoopGroup(2);
        backend = new ScriptedBackend();
        signaling = new NetherNetHttpSignaling((SslContext) null, group);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(group, group)
                .channelFactory(() -> new NetherNetServerChannel(backend, signaling))
                .childHandler(new ChannelInboundHandlerAdapter());
        serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

        InetSocketAddress bound = signaling.boundAddress();
        baseUrl = "http://127.0.0.1:" + bound.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully(0, 3, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void joinProbeAnswers200() throws Exception {
        assertEquals(200, get("/v1/join").statusCode());
    }

    @Test
    void unknownPathAnswers404() throws Exception {
        assertEquals(404, get("/v1/other").statusCode());
        assertEquals(404, post("/v2/join/123", "x").statusCode());
    }

    @Test
    void offerProducesFullIceAnswer() throws Exception {
        HttpResponse<String> response = post("/v1/join/12345678901234567890", "v=0\r\nfake offer\r\n");
        assertEquals(200, response.statusCode());
        assertEquals("application/sdp", response.headers().firstValue("content-type").orElse(""));
        assertEquals(CANNED_ANSWER, response.body());
        assertTrue(response.headers().firstValue("connection").orElse("").equalsIgnoreCase("close"));
        // The channel must have requested a full ICE answer from the backend.
        assertTrue(backend.fullIceFlags.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void nonNumericNetworkIdAnswers400() throws Exception {
        assertEquals(400, post("/v1/join/not-a-number", "v=0\r\n").statusCode());
    }

    @Test
    void emptyOfferAnswers400() throws Exception {
        assertEquals(400, post("/v1/join/42", "").statusCode());
    }
}
