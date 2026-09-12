package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetHttpSingleRequestTest {
    private final MultiThreadIoEventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final NetherNetHttpSignaling signaling = new NetherNetHttpSignaling((SslContext) null, worker);
    private final EmbeddedChannel channel = new EmbeddedChannel() {
        @Override
        protected SocketAddress remoteAddress0() {
            return new InetSocketAddress("127.0.0.1", 19132);
        }
    };
    private final List<Offer> offers = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        channel.freezeTime();
        signaling.setOfferValidator(null); // These framing and lifecycle fixtures carry unsigned SDP.
        signaling.initConnection(channel);
        signaling.setNewConnectionHandler((connectionId, networkId, sdp) -> {
            offers.add(new Offer(connectionId, networkId, sdp));
            signaling.setSignalHandler(connectionId, errors::add);
        });
    }

    @AfterEach
    void tearDown() {
        signaling.close();
        channel.finishAndReleaseAll();
        worker.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void missingNetworkIdIsRejected() {
        receive(post("", "offer"));
        assertTrue(readResponse().startsWith("HTTP/1.1 400 Bad Request\r\n"));
        assertTrue(offers.isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "not-a-number|not-a-number",
            "c99b1a2e-89d6-4a88-b031-571f5236d64c|c99b1a2e-89d6-4a88-b031-571f5236d64c",
            "18446744073709551616|18446744073709551616",
            "00042|00042",
            "Peer:Name|Peer:Name",
            "peer+name|peer+name",
            "peer%2Bname|peer+name",
            "peer%20name|peer name",
            "peer%2Fname|peer/name",
            "peer%252Fname|peer%2Fname",
            "peer%3Fx%23y%25z?ignored=1|peer?x#y%z",
            "%E7%8E%A9%E5%AE%B6%F0%9F%8E%AE|\u73a9\u5bb6\ud83c\udfae",
            "%EF%BF%BD|\ufffd"
    })
    void networkIdsAreDecodedOnceAndPreserved(String encodedId, String expectedId) {
        receive(post(encodedId, "offer"));
        assertEquals(1, offers.size());
        assertEquals(expectedId, offers.getFirst().networkId());
        answer("answer for opaque peer");
        String response = readResponse();
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(response.endsWith("answer for opaque peer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "%2", "%GG", "peer/child", "peer#fragment", "%FF", "%C3", "%C0%AF", "%ED%A0%80"})
    void malformedNetworkIdPathIsRejected(String encodedId) {
        receive(post(encodedId, "offer"));
        assertTrue(readResponse().startsWith("HTTP/1.1 400 Bad Request\r\n"));
        assertTrue(offers.isEmpty());
    }

    @Test
    void completedOfferCanUseTheFullNegotiationDeadline() {
        receive(post("42", "v=0\r\n"));
        assertEquals(1, offers.size());
        assertNull(channel.pipeline().get(ReadTimeoutHandler.class));

        advance(11);

        assertTrue(channel.isOpen(), "Receiving the complete offer retires the request-read deadline");
        assertNull(channel.readOutbound());
        answer("negotiated answer");
        String response = readResponse();
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(response.endsWith("negotiated answer"));
        assertFalse(channel.isOpen());
        assertTrue(errors.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteRequestsKeepTheirReadDeadline(boolean bodyStarted) {
        String request = "POST /v1/join/42 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n"
                + (bodyStarted ? "\r\nv=0" : "");
        ByteBuf input = receive(request);
        assertNotNull(channel.pipeline().get(ReadTimeoutHandler.class));
        assertTrue(offers.isEmpty());

        advance(11);

        assertFalse(channel.isOpen());
        assertEquals(0, input.refCnt());
        assertTrue(offers.isEmpty());
    }

    @Test
    void unansweredOfferStillTimesOutAndCleansUpItsExchange() {
        receive(post("42", "v=0\r\n"));
        long connectionId = offers.getFirst().connectionId();

        advance(14);
        assertTrue(channel.isOpen());
        assertNotNull(signaling.remoteAddressOf(connectionId));
        assertNull(channel.readOutbound());
        advance(2);

        assertTrue(readResponse().startsWith("HTTP/1.1 502 Bad Gateway\r\n"));
        assertFalse(channel.isOpen());
        assertNull(signaling.remoteAddressOf(connectionId));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().endsWith(" negotiation timeout"));
        advance(30);
        assertEquals(1, errors.size());
        assertNull(channel.readOutbound());
    }

    @ParameterizedTest
    @MethodSource("pipelinedRequests")
    void extraRequestsCannotReplaceOrTerminateTheOriginalOffer(boolean separateRead, String extra) {
        String first = post("42", "first offer");
        ByteBuf firstInput = receive(separateRead ? first : first + extra);
        ByteBuf secondInput = separateRead ? receive(extra) : null;

        assertEquals(1, offers.size());
        assertEquals("42", offers.getFirst().networkId());
        assertEquals("first offer", offers.getFirst().sdp());
        assertTrue(channel.isOpen());
        assertNull(channel.readOutbound(), "Only the original offer may produce an HTTP response");
        assertEquals(0, firstInput.refCnt());
        if (secondInput != null) {
            assertEquals(0, secondInput.refCnt());
        }

        answer("first answer");

        String response = readResponse();
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(response.endsWith("first answer"));
        assertEquals(1, response.split("HTTP/1.1", -1).length - 1);
        assertNull(signaling.remoteAddressOf(offers.getFirst().connectionId()));
        assertTrue(errors.isEmpty());
    }

    private static Stream<Arguments> pipelinedRequests() {
        return Stream.of(
                "GET /v1/join HTTP/1.1\r\nHost: localhost\r\n\r\n",
                post("99", "second offer"),
                "POST /v1/join/99 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2000000\r\n\r\n",
                "not an HTTP request\r\n\r\n")
                .flatMap(request -> Stream.of(Arguments.of(false, request), Arguments.of(true, request)));
    }

    @Test
    void requestCallbackFailureRemovesThePendingExchangeAndTimeout() {
        signaling.setNewConnectionHandler((connectionId, networkId, sdp) -> {
            offers.add(new Offer(connectionId, networkId, sdp));
            signaling.setSignalHandler(connectionId, errors::add);
            throw new IllegalStateException("negotiation could not start");
        });
        ByteBuf input = receive(post("42", "v=0\r\n"));

        assertFalse(channel.isOpen());
        assertEquals(0, input.refCnt());
        assertNull(signaling.remoteAddressOf(offers.getFirst().connectionId()));
        advance(30);
        assertTrue(errors.isEmpty());
        assertNull(channel.readOutbound());
    }

    @Test
    void disconnectWhileWaitingRemovesThePendingExchangeAndTimeout() {
        receive(post("42", "v=0\r\n"));
        long connectionId = offers.getFirst().connectionId();
        assertNotNull(signaling.remoteAddressOf(connectionId));

        channel.close();
        advance(30);

        assertNull(signaling.remoteAddressOf(connectionId));
        assertTrue(errors.isEmpty());
        assertNull(channel.readOutbound());
    }

    private ByteBuf receive(String request) {
        ByteBuf input = Unpooled.copiedBuffer(request, StandardCharsets.UTF_8);
        channel.writeInbound(input);
        channel.runPendingTasks();
        return input;
    }

    private void advance(long seconds) {
        channel.advanceTimeBy(seconds, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
    }

    private void answer(String sdp) {
        Offer offer = offers.getFirst();
        signaling.sendSignal(offer.networkId(), NetherNetConstants.buildSignalConnectResponse(offer.connectionId(), sdp));
        channel.runPendingTasks();
    }

    private String readResponse() {
        StringBuilder response = new StringBuilder();
        ByteBuf part;
        while ((part = channel.readOutbound()) != null) {
            try {
                response.append(part.toString(StandardCharsets.UTF_8));
            } finally {
                part.release();
            }
        }
        return response.toString();
    }

    private static String post(String networkId, String sdp) {
        return "POST /v1/join/" + networkId + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                + sdp.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + sdp;
    }

    private record Offer(long connectionId, String networkId, String sdp) {
    }
}
