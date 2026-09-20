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

import org.jspecify.annotations.Nullable;
import org.cloudburstmc.netty.util.http.HttpLoggingHandler;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.util.http.TlsRejectingHandler;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import io.netty.handler.codec.ProtocolDetectionResult;
import io.netty.handler.codec.ProtocolDetectionState;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Function;
import java.nio.channels.ClosedChannelException;
import javax.net.ssl.SSLException;
import io.netty.handler.codec.DecoderException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import java.util.Set;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jose4j.jwt.JwtClaims;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class implements a signaling server using HTTP(S) for the NetherNet protocol.
 * <p>
 * Follows <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/7330880ab78ef001cad0b9cdfedb3aa3eaa6d4af/NetherNetOnboardingGuide.md">...</a>
 */
public class NetherNetHTTPServerSignaling implements NetherNetServerSignaling {
    private final InternalLogger log = InternalLoggerFactory.getInstance(getClass());

    private final Random random = new SecureRandom();
    private final Map<String, Promise<String>> pendingAnswers = new ConcurrentHashMap<>();
    private final Map<InetAddress, Integer> connectionsPerAddress = new ConcurrentHashMap<>();

    private final PlayerFilter playerFilter;
    private final MotdProvider motdProvider;

    private static final AsciiString FORWARDED_FOR = AsciiString.cached("X-Forwarded-For");

    /** The joins whose child has not answered yet, by the connection id the channel was handed. */
    private final Map<String, Promise<String>> pendingByConnection = new ConcurrentHashMap<>();

    /** The source a trusted proxy declared in its PROXY header. */
    private static final AttributeKey<InetSocketAddress> PROXIED_SOURCE =
            AttributeKey.valueOf(NetherNetHTTPServerSignaling.class, "proxiedSource");

    private final IpRangeSet trustedProxies;
    private final boolean iceOnLocalPort;
    private final Set<String> advertisedAddresses;
    private final List<IceServerInfo> iceServers;
    private final TokenTrust tokenTrust;
    private final boolean serveHttp;
    private final boolean proxyProtocol;
    private final boolean requiresTls;
    private final int maxConnectionsPerAddress;
    private final int maxPendingJoins;
    private final int answerTimeoutSeconds;

    private SslContext sslContext;
    private OperatorIdentity serverIdentity;
    private NewConnectionHandler newConnectionHandler;
    private volatile NetherServerMetrics metrics;

    private Channel serverChannel;
    private volatile EventLoop eventLoop;
    /** Identity validation runs here rather than on the loop, since a trust anchor may fetch keys. */
    private volatile ExecutorService validation;

    @Override
    public void setMetrics(@Nullable NetherServerMetrics metrics) {
        this.metrics = metrics;
    }

    private NetherNetHTTPServerSignaling(Builder builder) {
        this.playerFilter = builder.playerFilter;
        this.motdProvider = builder.motdProvider;
        this.sslContext = builder.sslContext;
        this.serverIdentity = builder.identity;
        this.trustedProxies = builder.trustedProxies;
        this.maxConnectionsPerAddress = builder.maxConnectionsPerAddress;
        this.maxPendingJoins = builder.maxPendingJoins;
        this.answerTimeoutSeconds = builder.answerTimeoutSeconds;
        this.iceOnLocalPort = builder.iceOnLocalPort;
        this.advertisedAddresses = builder.advertisedAddresses;
        this.iceServers = builder.iceServers;
        this.tokenTrust = builder.tokenTrust;
        this.serveHttp = builder.serveHttp;
        this.proxyProtocol = builder.proxyProtocol;
        this.requiresTls = builder.requiresTls;
    }

    @Override
    public void bind(SocketAddress localAddress, EventLoop eventLoop) throws ConnectException {
        if (!(localAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        this.eventLoop = eventLoop;

        // One thread and a queue no longer than the joins that may wait keep validation as serial
        // as the loop was, so a flood of bad offers is refused rather than piled up
        ThreadPoolExecutor validation = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, this.maxPendingJoins)), task -> {
                    Thread thread = new Thread(task, "NetherNet identity validation");
                    thread.setDaemon(true);
                    return thread;
                });
        this.validation = validation;
        // Fetches the trust anchor's keys now rather than under the first join
        validation.execute(this.tokenTrust::prepare);

        // Offers arrive through acceptOffer instead, so there is nothing to listen on
        if (!this.serveHttp) {
            return;
        }

        // Bind the listening socket ourselves so a failure throws correctly
        ServerSocketChannel channel;
        try {
            channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.bind(localAddress, 128);
        } catch (IOException e) {
            throw new ConnectException("Failed to bind HTTP signaling to " + localAddress + ": " + e.getMessage());
        }

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(eventLoop)
                .channelFactory((ChannelFactory<NioServerSocketChannel>) () -> new NioServerSocketChannel(channel))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Counted before anything is read, so a peer holding sockets open is capped
                        // whatever it goes on to send
                        p.addLast(new ConnectionLimiter());

                        // A PROXY header precedes the TLS handshake, so it is read before any of this
                        if (proxyProtocol) {
                            p.addLast(new OptionalProxyProtocol());
                        }

                        // Both schemes reach one port: the first bytes say which this is, and a
                        // client that finds no TLS falls back to plaintext on the same port
                        if (sslContext != null) {
                            p.addLast(new OptionalSslHandler(sslContext));
                        } else {
                            p.addLast(new TlsRejectingHandler());
                        }

                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(8 * 1024));
                        p.addLast(new HttpLoggingHandler(log));
                        // A kept connection that goes quiet is one nobody will come back to
                        p.addLast(new IdleStateHandler(IDLE_SECONDS, 0, 0));
                        p.addLast(new SignalingHandler());
                    }
                });

        ChannelFuture regFuture = bootstrap.register();
        serverChannel = regFuture.channel();
        regFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Failed to register HTTP signaling channel", future.cause());
                future.channel().close();
            }
        });
    }

    /**
     * Caps how many connections one address may hold open at once.
     * <p>
     * Anyone on the internet can reach this endpoint, and a kept connection costs a socket until
     * it goes idle, so without a cap a single peer can hold as many as the host has descriptors.
     * A trusted reverse proxy is exempt, since every client behind it shares its address and
     * counting them together would throttle all of them at once.
     */
    private class ConnectionLimiter extends ChannelInboundHandlerAdapter {
        private InetAddress counted;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            InetAddress peer = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
            if (trustedProxies.contains(peer)) {
                ctx.fireChannelActive();
                return;
            }

            if (connectionsPerAddress.merge(peer, 1, Integer::sum) > maxConnectionsPerAddress) {
                release(peer);
                log.debug("Refused a connection from {}, already holding {}", peer, maxConnectionsPerAddress);
                NetherServerMetrics metrics = NetherNetHTTPServerSignaling.this.metrics;
                if (metrics != null) {
                    metrics.addressRefused((InetSocketAddress) ctx.channel().remoteAddress());
                }
                ctx.close();
                return;
            }
            this.counted = peer;
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (this.counted != null) {
                release(this.counted);
                this.counted = null;
            }
            ctx.fireChannelInactive();
        }

        private void release(InetAddress peer) {
            connectionsPerAddress.computeIfPresent(peer, (address, held) -> held <= 1 ? null : held - 1);
        }
    }

    /**
     * Reads a PROXY header from a trusted proxy, and steps aside for anything else.
     */
    private class OptionalProxyProtocol extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            InetSocketAddress peer = (InetSocketAddress) ctx.channel().remoteAddress();
            if (!trustedProxies.contains(peer)) {
                ctx.pipeline().remove(this);
                return;
            }

            ProtocolDetectionResult<HAProxyProtocolVersion> detected = HAProxyMessageDecoder.detectProtocol(in);
            if (detected.state() == ProtocolDetectionState.NEEDS_MORE_DATA) {
                return;
            }
            if (detected.state() == ProtocolDetectionState.INVALID) {
                // A trusted proxy is allowed to speak plain HTTP too
                ctx.pipeline().remove(this);
                return;
            }

            ctx.pipeline().addAfter(ctx.name(), null, new SimpleChannelInboundHandler<HAProxyMessage>() {
                @Override
                protected void channelRead0(ChannelHandlerContext inner, HAProxyMessage message) {
                    if (message.sourceAddress() != null) {
                        inner.channel().attr(PROXIED_SOURCE)
                                .set(new InetSocketAddress(message.sourceAddress(), message.sourcePort()));
                        log.debug("Got PROXY header: (from " + peer + ") " + message.sourceAddress());
                    }
                    inner.pipeline().remove(this);
                }
            });
            ctx.pipeline().replace(this, null, new HAProxyMessageDecoder());
        }
    }

    /** How long a kept connection may sit unused before it is closed. */
    private static final int IDLE_SECONDS = 30;

    private class SignalingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (req.decoderResult().isFailure()) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.BAD_REQUEST, false);
                return;
            }

            // A client sends its status check and its join on one connection, so what it asked for
            // here decides whether the next request has anywhere to land
            boolean keepAlive = HttpUtil.isKeepAlive(req);

            String path = new QueryStringDecoder(req.uri()).path();
            HttpMethod method = req.method();
            String host = req.headers().get(HttpHeaderNames.HOST);
            InetSocketAddress remoteAddress = clientAddress(ctx, req);

            if (requiresTls && sslContext != null && ctx.pipeline().get(SslHandler.class) == null) {
                log.debug("Refused a plaintext request from {}", remoteAddress);
                NetherServerMetrics metrics = NetherNetHTTPServerSignaling.this.metrics;
                if (metrics != null) {
                    metrics.plaintextRefused(remoteAddress);
                }
                respondUpgradeRequired(ctx, keepAlive);
                return;
            }

            if (path.equals("/v1/join")) {
                if (!HttpMethod.GET.equals(method)) {
                    respondEmptyWithStatus(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, keepAlive);
                    return;
                }

                PongData motd;
                try {
                    motd = motdProvider.getMotd(host, remoteAddress);
                } catch (Exception e) {
                    log.error("MOTD provider failed", e);
                    respondEmptyWithStatus(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, keepAlive);
                    return;
                }

                // A host with no status to give is not serving NetherNet here. A client reads the
                // 404 as the endpoint not being there at all and falls back to RakNet if supports it.
                if (motd == null) {
                    log.debug("Declined NetherNet for {} from {}", host, remoteAddress);
                    respondEmptyWithStatus(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
                    return;
                }

                respondWithString(ctx, motd.toJson(), "application/json", keepAlive);
                return;
            }

            if (!path.startsWith("/v1/join/")) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }

            if (!HttpMethod.POST.equals(method)) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, keepAlive);
                return;
            }

            String networkId = path.substring("/v1/join/".length());

            // Reject empty, or anything with a further path segment
            if (networkId.isEmpty() || networkId.indexOf('/') >= 0) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.NOT_FOUND, keepAlive);
                return;
            }

            String sdpOffer = req.content().toString(StandardCharsets.UTF_8);
            log.trace("Received sdp offer: " + sdpOffer);

            acceptOffer(networkId, sdpOffer, remoteAddress, host).whenComplete((sdpAnswer, failure) -> {
                if (!ctx.channel().isActive()) {
                    return; // The peer left while the join was in flight
                }
                if (failure == null) {
                    log.trace("Signed SDP answer: " + sdpAnswer);
                    respondWithString(ctx, sdpAnswer, "application/sdp", keepAlive);
                    return;
                }

                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                OfferRejected rejected = cause instanceof OfferRejected offer ? offer : null;
                HttpResponseStatus status = (rejected == null ? JoinRefusal.ERROR : rejected.refusal()).status();
                NetherServerMetrics metrics = NetherNetHTTPServerSignaling.this.metrics;
                if (metrics != null) {
                    metrics.joinRefused(status.code());
                }
                respondEmptyWithStatus(ctx, status, keepAlive);
            });
        }

        /**
         * The peer address, or the address a trusted reverse proxy forwarded on its behalf.
         */
        private InetSocketAddress clientAddress(ChannelHandlerContext ctx, FullHttpRequest req) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            if (!trustedProxies.contains(remote)) {
                return remote;
            }

            // A PROXY header is the more trustworthy of the two, so it wins
            InetSocketAddress proxied = ctx.channel().attr(PROXIED_SOURCE).get();
            if (proxied != null) {
                return proxied;
            }

            String forwarded = req.headers().get(FORWARDED_FOR);
            if (forwarded == null || forwarded.isBlank()) {
                return remote;
            }

            // Leftmost entry is the originating client
            String first = forwarded.split(",")[0].trim();
            if (first.startsWith("[") && first.endsWith("]")) {
                first = first.substring(1, first.length() - 1);
            }

            // An address literal only. Resolving a name here would block this event loop on DNS and
            // let the header stand for whatever the answer happened to be
            InetAddress literal = NetUtil.createInetAddressFromIpAddressString(first);
            return literal == null ? remote : new InetSocketAddress(literal, remote.getPort());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                ctx.close(); // Routine, so it closes without the noise of an exception
                return;
            }
            ctx.fireUserEventTriggered(event);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // A peer's malformed request or dropped connection is routine on an internet facing
            // port, and a stack trace per peer is a way to fill the log from outside
            if (cause instanceof IOException || cause instanceof DecoderException || cause instanceof SSLException) {
                log.debug("Closing the signaling connection from {}: {}", ctx.channel().remoteAddress(),
                        cause.toString());
            } else {
                log.error("Signaling handler error", cause);
            }
            ctx.close();
        }
    }

    /** A refusal carries no body: the client shows none, and the status says what it needs to know. */
    private void respondEmptyWithStatus(ChannelHandlerContext ctx, HttpResponseStatus status, boolean keepAlive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        respond(ctx, response, keepAlive);
    }

    /**
     * A 426 has to name what to upgrade to, and TLS on this same port is what it means.
     */
    private void respondUpgradeRequired(ChannelHandlerContext ctx, boolean keepAlive) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.UPGRADE_REQUIRED, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.UPGRADE, "TLS/1.2, HTTP/1.1");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        respond(ctx, response, keepAlive);
    }

    private void respondWithString(ChannelHandlerContext ctx, String body, String contentType, boolean keepAlive) {
        ByteBuf bodyBuf = Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, bodyBuf);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bodyBuf.readableBytes());
        respond(ctx, response, keepAlive);
    }

    /**
     * Closing a connection the client still believes is open leaves its next request unanswered:
     * TCP accepts the bytes into a half-closed socket, so the client waits for a reply that can
     * never come. Either the connection is kept, or the response says it is not.
     */
    private void respond(ChannelHandlerContext ctx, FullHttpResponse response, boolean keepAlive) {
        HttpUtil.setKeepAlive(response, keepAlive);
        ChannelFuture written = ctx.writeAndFlush(response);
        if (!keepAlive) {
            written.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // Nothing to do for HTTP signaling
    }

    @Override
    public OperatorIdentity serverIdentity() {
        return this.serverIdentity;
    }

    @Override
    public boolean usesTrickleIce() {
        return false;
    }

    /**
     * Whether ICE may gather on the port signaling is bound to. Set it false when another
     * transport already holds the UDP side of that port, so ICE uses its own.
     */
    @Override
    public boolean allowsIceOnLocalPort() {
        return this.iceOnLocalPort;
    }

    /**
     * The STUN and TURN servers ICE may use, which this signaling takes from its configuration
     * rather than from a handshake, since it speaks to nothing that would hand them out.
     */
    @Override
    public List<IceServerInfo> getIceServers() {
        return this.iceServers;
    }

    /**
     * How many joins are waiting for an answer, which is what {@code setMaxPendingJoins} caps.
     *
     * @return The joins in flight
     */
    public int pendingJoins() {
        return this.pendingAnswers.size();
    }

    /**
     * Answers an SDP offer, whether it arrived over this server's HTTP endpoint or was handed in
     * from outside it.
     * <p>
     * The returned description already carries the ICE candidates and the server identity
     * assertion, so it can be written back to the peer verbatim.
     *
     * @param networkId     The peer's network ID
     * @param sdpOffer      The raw SDP offer
     * @param clientAddress The address the offer came from, used for the peer's identity and to
     *                      seed the child channel, or null if it is not known
     * @param host          The host the peer asked for, passed to the player filter
     * @return The signed SDP answer, or a failure carrying an {@link OfferRejected}
     */
    public CompletableFuture<String> acceptOffer(String networkId, String sdpOffer,
                                                 @Nullable InetSocketAddress clientAddress,
                                                 @Nullable String host) {
        // Validation needs the thread that bind starts. Whether a channel is listening is checked
        // once the offer has earned it, so a bad offer is refused for what it is
        EventLoop loop = this.eventLoop;
        ExecutorService validation = this.validation;
        if (loop == null || validation == null) {
            return CompletableFuture.failedFuture(
                    new OfferRejected(JoinRefusal.ERROR, "signaling is not bound", null));
        }

        // Ahead of the signature check, so a flood cannot make us verify its way to the limit
        if (pendingAnswers.size() >= maxPendingJoins) {
            log.warn("Refusing joins, {} are already waiting for an answer", pendingAnswers.size());
            return CompletableFuture.failedFuture(
                    new OfferRejected(JoinRefusal.FULL, "too many joins in flight", null));
        }

        CompletableFuture<JwtClaims> validated = new CompletableFuture<>();
        try {
            validation.execute(() -> {
                try {
                    validated.complete(IdentityUtils.validateSdp(sdpOffer, tokenTrust));
                } catch (Throwable e) {
                    validated.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Refusing joins, too many offers are waiting for validation");
            return CompletableFuture.failedFuture(
                    new OfferRejected(JoinRefusal.FULL, "too many offers waiting for validation", null));
        }

        return validated.handleAsync((claims, failure) -> {
            if (failure != null) {
                String reason = refusalReason(failure);
                log.debug("Refused the offer from {}: {} ({})", clientAddress, reason, failure.toString());
                throw new CompletionException(new OfferRejected(JoinRefusal.INVALID_IDENTITY, reason, failure));
            }
            return admit(networkId, sdpOffer, clientAddress, host, claims);
        }, loop).thenCompose(Function.identity());
    }

    /**
     * What a refused peer is told, coarse on purpose: the endpoint faces the internet, and the
     * exception text describes the trust anchor rather than the offer.
     */
    private static String refusalReason(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (failure instanceof InvalidJwtException || message.startsWith("Token is not trusted")) {
            return "the identity token is not trusted here";
        }
        if (message.contains("missing identity")) {
            return "the offer carries no identity assertion";
        }
        if (message.contains("no fingerprints")) {
            return "the offer carries no fingerprint";
        }
        if (message.startsWith("Fingerprint")) {
            return "the identity assertion does not match the offer";
        }
        return "the identity assertion could not be validated";
    }

    /**
     * The second half of a join, on the loop, once the identity is known to be good.
     */
    private CompletableFuture<String> admit(String networkId, String sdpOffer,
                                            @Nullable InetSocketAddress clientAddress, @Nullable String host,
                                            JwtClaims claims) {
        PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"),
                claims.getClaimValueAsString("xname"), networkId, clientAddress, claims);
        log.debug("Identity is valid: " + player.displayName() + " (" + player.xuid() + ")");

        // Let the user reject the player before we start a connection for them
        JoinRefusal refusal;
        try {
            refusal = playerFilter.refuse(host, player);
        } catch (Exception e) {
            log.error("Player filter failed for " + player.xuid(), e);
            refusal = JoinRefusal.REJECTED;
        }

        if (refusal != null) {
            log.debug("Rejected join from " + player.displayName() + " (" + player.xuid() + "): " + refusal);
            return CompletableFuture.failedFuture(
                    new OfferRejected(refusal, "turned away by the player filter", null));
        }

        EventLoop loop = this.eventLoop;
        NewConnectionHandler handler = this.newConnectionHandler;
        if (loop == null || handler == null) {
            return CompletableFuture.failedFuture(
                    new OfferRejected(JoinRefusal.ERROR, "signaling is not bound", null));
        }

        // Validation took a hop, so the cap is checked again where the count is authoritative
        if (pendingAnswers.size() >= maxPendingJoins) {
            return CompletableFuture.failedFuture(
                    new OfferRejected(JoinRefusal.FULL, "too many joins in flight", null));
        }

        CompletableFuture<String> result = new CompletableFuture<>();

        // Register the pending answer before firing the callback so a fast answer isn't missed
        Promise<String> answer = loop.newPromise();
        // Never replace the answer owned by another offer for this network ID.
        if (pendingAnswers.putIfAbsent(networkId, answer) != null) {
            return CompletableFuture.failedFuture(new OfferRejected(JoinRefusal.DUPLICATE,
                    "network ID already pending", null));
        }

        // The network id is the peer's own; the connection id is ours to choose, a uint64 as text
        String connectionId = Long.toUnsignedString(random.nextLong());
        // Mapped before the channel sees the id, so a child that fails at once still finds its join
        pendingByConnection.put(connectionId, answer);

        ScheduledFuture<?> timeout = loop.schedule(
                () -> answer.tryFailure(new TimeoutException("Timed out waiting for SDP answer")),
                answerTimeoutSeconds, TimeUnit.SECONDS);

        answer.addListener((FutureListener<String>) future -> {
            pendingAnswers.remove(networkId, answer);
            pendingByConnection.remove(connectionId);
            timeout.cancel(false);

            if (future.isSuccess()) {
                result.complete(future.getNow());
                return;
            }
            boolean timedOut = future.cause() instanceof TimeoutException;
            log.warn("No SDP answer for {} from {}: {}", networkId, clientAddress, future.cause().toString());
            result.completeExceptionally(new OfferRejected(timedOut ? JoinRefusal.TIMEOUT : JoinRefusal.ERROR,
                    timedOut ? "no answer was produced in time" : "the connection failed before an answer was produced",
                    future.cause()));
        });

        handler.onConnect(connectionId, networkId, sdpOffer, clientAddress, player);
        return result;
    }

    /**
     * Why a join did not happen, and the status it is refused with.
     * <p>
     * The constants are what this signaling raises on its own. A host answering something else
     * builds one. Only the status reaches the peer for now, since the client shows nothing else; a
     * message can be added here later without changing what callers build.
     */
    public static class JoinRefusal {
        /** The offer carried no usable identity assertion. */
        public static final JoinRefusal INVALID_IDENTITY = new JoinRefusal(HttpResponseStatus.UNAUTHORIZED);
        /** The player filter turned the peer away. */
        public static final JoinRefusal REJECTED = new JoinRefusal(HttpResponseStatus.FORBIDDEN);
        /** There is no room for another player. */
        public static final JoinRefusal FULL = new JoinRefusal(HttpResponseStatus.SERVICE_UNAVAILABLE);
        /** Another join for this network ID is already waiting for an answer. */
        public static final JoinRefusal DUPLICATE = new JoinRefusal(HttpResponseStatus.CONFLICT);
        /** Nothing produced an answer in time. */
        public static final JoinRefusal TIMEOUT = new JoinRefusal(HttpResponseStatus.GATEWAY_TIMEOUT);
        /** Signaling is not in a state to answer, or something failed while answering. */
        public static final JoinRefusal ERROR = new JoinRefusal(HttpResponseStatus.INTERNAL_SERVER_ERROR);

        private final HttpResponseStatus status;

        public JoinRefusal(HttpResponseStatus status) {
            // A 2xx leaves the client parsing an answer we never wrote
            if (status.code() >= 200 && status.code() < 300) {
                throw new IllegalArgumentException("A refusal cannot tell a client the join worked: " + status);
            }
            this.status = status;
        }

        public HttpResponseStatus status() {
            return this.status;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JoinRefusal refusal && this.status.equals(refusal.status);
        }

        @Override
        public int hashCode() {
            return this.status.hashCode();
        }

        @Override
        public String toString() {
            return this.status.toString();
        }
    }

    public static final class OfferRejected extends Exception {
        private final JoinRefusal refusal;

        OfferRejected(JoinRefusal refusal, String message, @Nullable Throwable cause) {
            super(message, cause);
            this.refusal = refusal;
        }

        public JoinRefusal refusal() {
            return this.refusal;
        }
    }

    @Override
    public void sendDescription(String targetNetworkId, String sdp) {
        log.debug("Sending sdp to " + targetNetworkId);

        Promise<String> answer = pendingAnswers.get(targetNetworkId);
        if (answer != null) {
            answer.trySuccess(SdpUtil.withAdvertisedCandidates(sdp, this.advertisedAddresses));
        } else {
            log.debug("No pending join waiting for " + targetNetworkId);
        }
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        // Nothing to do for HTTP signaling
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        // The channel calls this as its child closes, which before an answer means the join failed
        Promise<String> answer = pendingByConnection.remove(connectionId);
        if (answer != null) {
            answer.tryFailure(new ClosedChannelException());
        }
    }

    @Override
    public boolean isActive() {
        Channel ch = this.serverChannel;
        return ch != null && ch.isActive();
    }

    @Override
    public String getLocalNetworkId() {
        return "";
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        ExecutorService validation = this.validation;
        if (validation != null) {
            validation.shutdownNow();
        }
    }

    /**
     * Functional interface for filtering players before a connection is created for them.
     */
    @FunctionalInterface
    public interface PlayerFilter {
        /**
         * Called once the identity attached to an SDP offer has been validated, before
         * the connection is handed to the {@link NewConnectionHandler}.
         * <p>
         * Called on the event loop, so don't block in here. A thrown exception turns the player
         * away as {@link JoinRefusal#REJECTED} does.
         *
         * @param host   The host header from the join request, which may be used to identify the server
         * @param player The validated player attempting to join
         * @return Why to turn the player away, or null to let them in
         */
        @Nullable JoinRefusal refuse(String host, PlayerInfo player);
    }

    /**
     * Functional interface providing the MOTD returned to clients querying the server.
     */
    @FunctionalInterface
    public interface MotdProvider {
        /**
         * Called for every status request, so the returned data can change over time.
         * <p>
         * Called on the event loop, so don't block in here. The discovery-only fields of
         * {@link PongData} are ignored, as they have no place in the status response.
         * <p>
         * Answering null serves no status at all, which is how a host says it does not take
         * NetherNet for this request. A join sent anyway still reaches the {@link PlayerFilter}.
         *
         * @param host          The host header from the join request, which may be used to identify the server
         * @param remoteAddress The address the status request came from
         * @return The MOTD to advertise, or null to leave the client to its other transport
         */
        @Nullable PongData getMotd(String host, InetSocketAddress remoteAddress);
    }

    /**
     * Builder for {@link NetherNetHTTPServerSignaling}.
     * <p>
     * The server is backed by one keystore for the TLS listener and another for the
     * server identity used to sign SDP answers. Both must be PKCS12 files, and only
     * the identity keystore is required.
     */
    public static class Builder {
        private OperatorIdentity identity;
        private SslContext sslContext;
        private IpRangeSet trustedProxies = IpRangeSet.empty();
        private int maxConnectionsPerAddress = 8;
        private int maxPendingJoins = 64;
        private int answerTimeoutSeconds = 30;
        private boolean iceOnLocalPort = true;
        private Set<String> advertisedAddresses = Set.of();
        private List<IceServerInfo> iceServers = List.of();
        private TokenTrust tokenTrust = TokenTrust.MINECRAFT_AUTH;
        private boolean serveHttp = true;
        private boolean proxyProtocol = false;
        private boolean requiresTls = true;
        private PlayerFilter playerFilter = (host, player) -> null;
        private MotdProvider motdProvider = (host, remoteAddress) -> PongData.DEFAULT;

        /**
         * Sets the identity used to sign SDP answers. Required.
         * <p>
         * Load it with {@link OperatorIdentity#fromPemOrCreate}, {@link OperatorIdentity#fromPem} or
         * {@link OperatorIdentity#generate}, or build one straight from a keypair.
         *
         * @param identity The identity to sign with
         * @return This builder
         */
        public Builder setIdentity(OperatorIdentity identity) {
            this.identity = identity;
            return this;
        }

        /**
         * Sets the identity from an unencrypted PEM private key, which carries no certificate and
         * so no domain of its own.
         *
         * @param identityPem PEM file holding the EC P-384 identity key
         * @param domain      The identity domain, surfaced to players in the first use prompt
         * @return This builder
         * @throws IllegalArgumentException If the key cannot be read
         */
        public Builder setIdentityPem(File identityPem, String domain) {
            try {
                return setIdentity(OperatorIdentity.fromPem(identityPem, domain));
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read the identity key " + identityPem, e);
            }
        }

        /**
         * Sets the TLS context for the listener. If unset the server listens in plaintext.
         *
         * @param sslContext The context to serve TLS with
         * @return This builder
         */
        public Builder setSslContext(SslContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Serves TLS using an unprotected PKCS12 keystore.
         *
         * @param httpsKeystore PKCS12 keystore holding the TLS certificate and key
         * @return This builder
         */
        public Builder setHttpsKeystore(File httpsKeystore) {
            return setHttpsKeystore(httpsKeystore, "");
        }

        /**
         * Serves TLS using a PKCS12 keystore.
         *
         * @param httpsKeystore PKCS12 keystore holding the TLS certificate and key
         * @param httpsPassword Password for {@code httpsKeystore}, or "" if unprotected
         * @return This builder
         * @throws IllegalArgumentException If the keystore cannot be read
         */
        public Builder setHttpsKeystore(File httpsKeystore, String httpsPassword) {
            try {
                char[] password = httpsPassword == null ? new char[0] : httpsPassword.toCharArray();

                KeyStore store = KeyStore.getInstance("PKCS12");
                try (FileInputStream input = new FileInputStream(httpsKeystore)) {
                    store.load(input, password);
                }

                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, password);
                return setSslContext(SslContextBuilder.forServer(factory).build());
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read the TLS keystore " + httpsKeystore, e);
            }
        }

        /**
         * Serves TLS using PEM files, the form most certificate authorities hand out.
         *
         * @param certificateChain PEM certificate chain, leaf first
         * @param privateKey       PEM private key for the leaf certificate
         * @return This builder
         * @throws IllegalArgumentException If either file cannot be read
         */
        public Builder setHttpsPem(File certificateChain, File privateKey) {
            return setHttpsPem(certificateChain, privateKey, null);
        }

        /**
         * Serves TLS using PEM files.
         *
         * @param certificateChain PEM certificate chain, leaf first
         * @param privateKey       PEM private key for the leaf certificate
         * @param keyPassword      Password for {@code privateKey}, or null if unencrypted
         * @return This builder
         * @throws IllegalArgumentException If either file cannot be read
         */
        public Builder setHttpsPem(File certificateChain, File privateKey, @Nullable String keyPassword) {
            try {
                return setSslContext(SslContextBuilder.forServer(certificateChain, privateKey, keyPassword).build());
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read the TLS certificate " + certificateChain, e);
            }
        }

        /**
         * Caps the connections one address may hold at once. Trusted proxies are exempt, since
         * every client behind one shares its address.
         *
         * @param maxConnectionsPerAddress Connections per address, must be positive
         * @return This builder
         */
        public Builder setMaxConnectionsPerAddress(int maxConnectionsPerAddress) {
            if (maxConnectionsPerAddress < 1) {
                throw new IllegalArgumentException("maxConnectionsPerAddress");
            }
            this.maxConnectionsPerAddress = maxConnectionsPerAddress;
            return this;
        }

        /**
         * Caps how many joins may be waiting for an answer at once. Each one holds a peer
         * connection open until it is answered or times out, so this bounds what the host spends
         * on connections nobody has completed. Joins past it are refused as unavailable, whoever
         * they came from: it guards a finite resource rather than one peer's share of it.
         *
         * @param maxPendingJoins Joins in flight, must be positive
         * @return This builder
         */
        public Builder setMaxPendingJoins(int maxPendingJoins) {
            if (maxPendingJoins < 1) {
                throw new IllegalArgumentException("maxPendingJoins");
            }
            this.maxPendingJoins = maxPendingJoins;
            return this;
        }

        /**
         * Sets how long a join may wait for the channel to answer before it is refused as
         * {@link JoinRefusal#TIMEOUT}. Defaults to 30 seconds, which covers gathering through
         * STUN and TURN servers. A child that fails sooner ends the wait at once.
         *
         * @param answerTimeoutSeconds The wait in seconds
         * @return This builder
         */
        public Builder setAnswerTimeoutSeconds(int answerTimeoutSeconds) {
            this.answerTimeoutSeconds = answerTimeoutSeconds;
            return this;
        }

        /**
         * Sets the reverse proxies whose {@code X-Forwarded-For} header is honoured, as single
         * addresses or CIDR ranges. Requests from anywhere else keep their peer address.
         *
         * @param trustedProxies Addresses or CIDR ranges, such as {@code 10.0.0.0/8}
         * @return This builder
         */
        public Builder setTrustedProxies(Collection<String> trustedProxies) {
            return setTrustedProxies(IpRangeSet.parse(trustedProxies));
        }

        /**
         * Sets the reverse proxies whose {@code X-Forwarded-For} header is honoured, from a set that
         * is already parsed, for callers that match the same addresses elsewhere.
         *
         * @param trustedProxies The addresses to trust
         * @return This builder
         */
        public Builder setTrustedProxies(IpRangeSet trustedProxies) {
            this.trustedProxies = trustedProxies == null ? IpRangeSet.empty() : trustedProxies;
            return this;
        }

        /**
         * Sets whether ICE may gather on the port signaling binds to. Defaults to true. Set it
         * false when another transport, such as RakNet, already holds the UDP side of that port.
         *
         * @param iceOnLocalPort Whether ICE may use the signaling port
         * @return This builder
         */
        public Builder setIceOnLocalPort(boolean iceOnLocalPort) {
            this.iceOnLocalPort = iceOnLocalPort;
            return this;
        }

        /**
         * Sets the addresses announced as ICE candidates; empty, the default, announces everything
         * ICE gathers. Listed addresses this host holds narrow its host candidates to those; one it
         * does not hold is announced as the public side of a NAT forwarding the media port here.
         * A signaling proxy's address does not belong here unless it also forwards the media port.
         *
         * @param advertisedAddresses Addresses reachable by connecting peers
         * @return This builder
         * @see SdpUtil#withAdvertisedCandidates
         */
        public Builder setAdvertisedAddresses(Collection<String> advertisedAddresses) {
            this.advertisedAddresses = advertisedAddresses == null ? Set.of() : Set.copyOf(advertisedAddresses);
            return this;
        }

        /**
         * Sets the STUN and TURN servers ICE may use, empty by default.
         * <p>
         * A host behind NAT gathers only the addresses its interfaces carry, none of which a peer
         * elsewhere can reach. A STUN server is what turns that into the address the peer sees,
         * and a TURN server relays when no direct path exists. Neither is needed when the host
         * holds a reachable address itself.
         *
         * @param iceServers The servers to offer ICE
         * @return This builder
         */
        public Builder setIceServers(Collection<IceServerInfo> iceServers) {
            this.iceServers = iceServers == null ? List.of() : List.copyOf(iceServers);
            return this;
        }

        /**
         * Sets who to trust to have signed the token in a joining peer's identity assertion.
         * Defaults to {@link TokenTrust#MINECRAFT_AUTH}, which is what a retail client presents.
         *
         * @param tokenTrust The trust policy
         * @return This builder
         */
        public Builder setTokenTrust(TokenTrust tokenTrust) {
            this.tokenTrust = tokenTrust;
            return this;
        }

        /**
         * Sets whether to read a HAProxy PROXY header, v1 or v2, from connections that arrive from
         * a trusted proxy. Defaults to false.
         * <p>
         * Only connections from {@link #setTrustedProxies} are looked at, and a connection that
         * carries no header is served normally, so a listener can take both. The header is read
         * before TLS, which is where a proxy puts it.
         *
         * @param proxyProtocol Whether to accept PROXY headers
         * @return This builder
         */
        public Builder setProxyProtocol(boolean proxyProtocol) {
            this.proxyProtocol = proxyProtocol;
            return this;
        }

        /**
         * Sets whether to serve the HTTP join endpoint. Defaults to true. With it off nothing is
         * listened on and offers have to be handed in through
         * {@link NetherNetHTTPServerSignaling#acceptOffer}, which is how an endpoint outside this process
         * drives signaling.
         *
         * @param serveHttp Whether to bind the join endpoint
         * @return This builder
         */
        public Builder setServeHttp(boolean serveHttp) {
            this.serveHttp = serveHttp;
            return this;
        }

        /**
         * Sets whether plaintext requests are answered while TLS is served. Defaults to true, so
         * they are not: a plaintext request gets a 426 and the peer is left to fall back to
         * whatever other transport it has, rather than joining over plaintext, where a client
         * shows its first use trust prompt. Has no effect without a TLS context.
         *
         * @param requiresTls Whether to refuse plaintext requests
         * @return This builder
         */
        public Builder setRequiresTls(boolean requiresTls) {
            this.requiresTls = requiresTls;
            return this;
        }

        /**
         * Sets the filter consulted for each join once its identity has been validated.
         * Defaults to allowing everyone.
         *
         * @param playerFilter The filter to consult
         * @return This builder
         */
        public Builder setPlayerFilter(PlayerFilter playerFilter) {
            this.playerFilter = playerFilter;
            return this;
        }

        /**
         * Sets the provider called for each status request, which answers null to leave a
         * client to its other transport.
         * Defaults to {@link PongData#DEFAULT}.
         *
         * @param motdProvider The provider to call
         * @return This builder
         */
        public Builder setMotdProvider(MotdProvider motdProvider) {
            this.motdProvider = motdProvider;
            return this;
        }

        /**
         * Sets a fixed MOTD to advertise for every status request.
         *
         * @param motd The MOTD to advertise
         * @return This builder
         */
        public Builder setMotd(PongData motd) {
            return setMotdProvider((host, remoteAddress) -> motd);
        }

        /**
         * Builds the signaling instance.
         *
         * @return A new signaling instance
         * @throws IllegalStateException If no identity was set
         */
        public NetherNetHTTPServerSignaling build() {
            if (identity == null) {
                throw new IllegalStateException("An identity is required");
            }

            return new NetherNetHTTPServerSignaling(this);
        }
    }
}
