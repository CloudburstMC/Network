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

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The client half of HTTP signaling: connects out to a server's {@code /v1/join} endpoint, as a
 * proxy does toward a downstream server. One instance serves one connection.
 * <p>
 * HTTP signaling is a single request and response, so there is no trickle: the channel gathers
 * every candidate first and hands the finished offer to {@link #sendDescription}, and the answer
 * comes back through the signal handler like any other. The offer's identity assertion is the
 * channel's business too, through {@code NetherChannelOption.NETHER_CLIENT_IDENTITY}.
 * <p>
 * Nothing here runs on the channel's loop, the channel moves itself back onto it.
 */
public class NetherNetHTTPClientSignaling implements NetherNetClientSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetHTTPClientSignaling.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String localNetworkId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
    private final List<IceServerInfo> iceServers;

    private volatile InetSocketAddress address;
    private volatile String connectionId;
    private volatile SignalHandler handler;
    private volatile FailureHandler failure;
    private volatile boolean closed;

    /**
     * Without STUN or TURN, so the offer carries the local interfaces only and goes out as soon
     * as they are gathered.
     */
    public NetherNetHTTPClientSignaling() {
        this(List.of());
    }

    /**
     * @param iceServers The STUN and TURN servers to gather through. Every one of them has to
     *                   answer or time out before the offer can go, since nothing trickles after it.
     */
    public NetherNetHTTPClientSignaling(List<IceServerInfo> iceServers) {
        this.iceServers = List.copyOf(iceServers);
    }

    /**
     * The capability probe. A non 2xx here is how a server says it does not speak NetherNet, which
     * is what drives a fallback to RakNet. A server that requires TLS answers 426, and this
     * signaling only speaks plaintext, so that counts as unreachable too.
     *
     * @param address The server's signaling endpoint
     * @return Whether the endpoint answered as a NetherNet server
     */
    public static CompletableFuture<Boolean> probe(InetSocketAddress address) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(address) + "/v1/join"))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() / 100 == 2)
                .exceptionally(error -> false);
    }

    @Override
    public boolean usesTrickleIce() {
        return false;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        if (!(remoteAddress instanceof InetSocketAddress endpoint)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("HTTP signaling needs an InetSocketAddress, not " + remoteAddress));
        }
        this.address = endpoint;
        return CompletableFuture.completedFuture(this.iceServers);
    }

    @Override
    public void sendDescription(String targetNetworkId, String offer) {
        URI join = URI.create(baseUrl(this.address) + "/v1/join/" + this.localNetworkId);
        HttpRequest request = HttpRequest.newBuilder(join)
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/sdp")
                .header("Accept", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(offer))
                .build();

        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete(this::onAnswer);
    }

    private void onAnswer(HttpResponse<String> response, Throwable error) {
        if (this.closed) {
            return;
        }
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            this.fail("request failed: " + cause);
            return;
        }
        if (response.statusCode() / 100 != 2) {
            this.fail("HTTP " + response.statusCode());
            return;
        }

        String answer = response.body();
        // A rejection can arrive as a 2xx with a short body instead of an SDP, so the shape has to
        // be checked rather than the status alone
        if (answer == null || !answer.startsWith("v=")) {
            this.fail("HTTP " + response.statusCode() + " without an SDP answer: "
                    + (answer == null ? "empty" : answer.strip()));
            return;
        }

        SignalHandler target = this.handler;
        if (target != null) {
            target.onSignal(NetherNetConstants.buildSignalConnectResponse(this.connectionId, answer));
        }
    }

    private void fail(String reason) {
        log.debug("Signaling to {} failed: {}", this.address, reason);
        FailureHandler target = this.failure;
        if (target != null) {
            target.onFailure(reason);
        }
    }

    private static String baseUrl(InetSocketAddress address) {
        // An address that is already resolved is used as it is, so the URL triggers no lookup
        String host = address.isUnresolved() ? address.getHostString() : address.getAddress().getHostAddress();
        return "http://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        // The answer is delivered as a signal for this id, the same way trickle signaling would
        this.connectionId = connectionId;
        this.handler = handler;
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        if (connectionId.equals(this.connectionId)) {
            this.handler = null;
        }
    }

    @Override
    public void setFailureHandler(FailureHandler handler) {
        this.failure = handler;
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public boolean isActive() {
        return !this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
