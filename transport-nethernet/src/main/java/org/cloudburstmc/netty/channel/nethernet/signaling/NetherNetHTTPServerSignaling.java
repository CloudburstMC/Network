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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.nethernet.AddressSource;
import org.cloudburstmc.netty.channel.nethernet.SourcedAddress;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.util.http.HttpLoggingHandler;
import org.cloudburstmc.netty.util.http.TlsRejectingHandler;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.cloudburstmc.netty.util.nethernet.StunObserver;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * This class implements a signaling server using HTTP(S) for the NetherNet protocol.
 * <p>
 * Follows <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/7330880ab78ef001cad0b9cdfedb3aa3eaa6d4af/NetherNetOnboardingGuide.md">...</a>
 */
public class NetherNetHTTPServerSignaling implements NetherNetServerSignaling {
    private static final AsciiString FORWARDED_FOR = AsciiString.cached("X-Forwarded-For");
    /** Headers signaling sets or that frame the response, see {@link Builder#setResponseHeaders}. */
    private static final Set<String> RESERVED_HEADERS = Set.of("content-length", "content-type", "content-encoding",
            "transfer-encoding", "connection", "keep-alive", "upgrade");
    /** How long a kept connection may sit unused before it is closed. */
    private static final int IDLE_SECONDS = 30;

    private final InternalLogger log = InternalLoggerFactory.getInstance(getClass());

    private final OperatorIdentity serverIdentity;
    private final SslContext sslContext;
    private final IpRangeSet forwardedForProxies;
    private final IpRangeSet proxyProtocolProxies;
    /** Both proxy lists, since every client behind a proxy shares its address. */
    private final IpRangeSet connectionLimitExempt;
    private final int maxConnectionsPerAddress;
    private final int maxPendingJoins;
    private final int answerTimeoutSeconds;
    private final boolean iceOnLocalPort;
    /** As configured, so a name is looked up when binding. */
    private final List<InetSocketAddress> configuredAddresses;
    /** What the configured ones resolved to when binding. */
    private volatile List<InetSocketAddress> advertisedAddresses = List.of();
    private volatile boolean mediaRelayed;
    private final List<InetSocketAddress> stunServers;
    private final Duration stunMaxAge;
    private final TokenTrust tokenTrust;
    private final boolean serveHttp;
    private final boolean requiresTls;
    private final PlayerFilter playerFilter;
    private volatile MotdProvider motdProvider;
    /** Null when no headers are configured. */
    private final @Nullable ResponseHeaders responseHeaders;

    private final Random random = new SecureRandom();
    private final Map<String, PendingJoin> pendingAnswers = new ConcurrentHashMap<>();
    /** The joins whose child has not answered yet, by the connection id the channel was handed. */
    private final Map<String, Promise<String>> pendingByConnection = new ConcurrentHashMap<>();
    private final Map<InetAddress, Integer> connectionsPerAddress = new ConcurrentHashMap<>();
    /** Every connection served, so closing ends those a host handed in along with the accepted ones. */
    private final ChannelGroup connections =
            new DefaultChannelGroup("NetherNet signaling", GlobalEventExecutor.INSTANCE, true);
    private final ChannelInitializer<Channel> connectionInitializer = new ConnectionInitializer();

    private NewConnectionHandler newConnectionHandler;
    private volatile NetherServerMetrics metrics;

    private Channel serverChannel;
    /** The HTTP listener's own loop, so the channel's loop can be of any transport. */
    private volatile EventLoopGroup acceptor;
    private volatile EventLoop eventLoop;
    /** Identity validation runs here rather than on the loop, since a trust anchor may fetch keys. */
    private volatile ExecutorService validation;
    /** Where ICE binds, as the channel told it, null when every connection takes its own port. */
    private volatile @Nullable InetSocketAddress iceAddress;
    private volatile @Nullable DiscoveredEndpoints discovered;
    private volatile @Nullable ScheduledFuture<?> discoveryTask;
    private volatile boolean closed;

    /**
     * A join waiting for its answer, with the endpoint the player typed in when it is one to announce.
     */
    private record PendingJoin(Promise<String> answer, @Nullable InetSocketAddress typed) {
    }

    private NetherNetHTTPServerSignaling(Builder builder) {
        this.serverIdentity = builder.identity;
        this.sslContext = builder.sslContext;
        this.forwardedForProxies = builder.forwardedForProxies;
        this.proxyProtocolProxies = builder.proxyProtocolProxies;
        this.connectionLimitExempt = forwardedForProxies.union(proxyProtocolProxies);
        this.maxConnectionsPerAddress = builder.maxConnectionsPerAddress;
        this.maxPendingJoins = builder.maxPendingJoins;
        this.answerTimeoutSeconds = builder.answerTimeoutSeconds;
        this.iceOnLocalPort = builder.iceOnLocalPort;
        this.configuredAddresses = builder.advertisedAddresses;
        this.stunServers = builder.stunServers;
        this.stunMaxAge = builder.stunMaxAge;
        this.tokenTrust = builder.tokenTrust;
        this.serveHttp = builder.serveHttp;
        this.requiresTls = builder.requiresTls;
        this.playerFilter = builder.playerFilter;
        this.motdProvider = builder.motdProvider;
        this.responseHeaders = builder.responseHeaders.isEmpty() ? null : new ResponseHeaders(builder.responseHeaders);
    }

    @Override
    public void bind(SocketAddress localAddress, @Nullable InetSocketAddress iceAddress, EventLoop eventLoop)
            throws ConnectException {
        // Whatever reached it would be closed on arrival, so a second life would serve nothing
        if (this.closed) {
            throw new IllegalStateException("Signaling cannot be bound again once closed");
        }
        if (!(localAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        // Discovery would announce this host, which players only reach through the relays
        if (this.mediaRelayed && !this.stunServers.isEmpty()) {
            throw new ConnectException("PROXY protocol relays front the media port, so STUN discovery would "
                    + "announce an address players cannot reach");
        }
        if (this.mediaRelayed && this.configuredAddresses.isEmpty()) {
            log.warn("PROXY protocol relays front the media port, but no advertised addresses name them, so answers "
                    + "carry this host's own addresses. Players can only connect if something in front of signaling "
                    + "rewrites the answers to name the relays.");
        }
        try {
            this.advertisedAddresses = AdvertisedAddresses.resolve(this.configuredAddresses);
        } catch (UnknownHostException | IllegalArgumentException e) {
            ConnectException failure = new ConnectException(e.getMessage());
            failure.initCause(e);
            throw failure;
        }
        if (this.mediaRelayed && iceAddress != null) {
            for (InetSocketAddress relay : this.advertisedAddresses) {
                if (isMediaEndpoint(relay, iceAddress)) {
                    throw new ConnectException(NetUtil.toSocketAddressString(relay) + " is this host's own media "
                            + "endpoint, which players only reach through the PROXY protocol relays");
                }
            }
        }
        this.eventLoop = eventLoop;
        this.iceAddress = iceAddress;

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

        // Without it offers arrive through acceptOffer instead, so there is nothing to listen on
        if (this.serveHttp) {
            listen(localAddress);
        }
        if (!this.stunServers.isEmpty()) {
            try {
                this.discovered = startDiscovery(iceAddress, eventLoop);
            } catch (ConnectException e) {
                close();
                throw e;
            }
        }
    }

    /**
     * Starts STUN discovery on the ICE port, which it has to share with the peers, so a port that
     * is not fixed fails the bind rather than discovering a mapping nobody uses.
     */
    private DiscoveredEndpoints startDiscovery(@Nullable InetSocketAddress ice, EventLoop eventLoop)
            throws ConnectException {
        if (ice == null) {
            throw new ConnectException("STUN discovery needs ICE on a fixed port, set NETHER_SERVER_ICE_ADDRESS "
                    + "or let ICE use the signaling port");
        }
        // Unresolved or wildcard, ICE binds the wildcard, which the monitors have to match
        InetAddress host = ice.getAddress();
        InetAddress bind = host == null || host.isAnyLocalAddress() ? null : host;
        DiscoveredEndpoints discovered;
        try {
            discovered = new DiscoveredEndpoints(this.stunServers, this.stunMaxAge, ice.getPort(),
                    StunObserver.nativeMonitors(bind, ice.getPort()), System::nanoTime);
        } catch (RuntimeException e) {
            ConnectException failure = new ConnectException(
                    "Could not start STUN discovery on udp/" + ice.getPort() + ": " + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
        this.discoveryTask = eventLoop.scheduleAtFixedRate(discovered::refresh,
                DiscoveredEndpoints.REFRESH_SECONDS, DiscoveredEndpoints.REFRESH_SECONDS, TimeUnit.SECONDS);
        return discovered;
    }

    private void listen(SocketAddress localAddress) throws ConnectException {
        // Bind the listening socket ourselves so a failure throws correctly
        ServerSocketChannel channel;
        try {
            channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.bind(localAddress, 128);
        } catch (IOException e) {
            throw new ConnectException("Failed to bind HTTP signaling to " + localAddress + ": " + e.getMessage());
        }

        // The listener needs an NIO loop for its socket, which the owning channel's loop need not
        // be, so it brings one; joins still reach the channel on the loop given above
        EventLoopGroup acceptor = new NioEventLoopGroup(1, task -> {
            Thread thread = new Thread(task, "NetherNet signaling acceptor");
            thread.setDaemon(true);
            return thread;
        });
        this.acceptor = acceptor;

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(acceptor)
                .channelFactory((ChannelFactory<NioServerSocketChannel>) () -> new NioServerSocketChannel(channel))
                .childHandler(this.connectionInitializer);

        ChannelFuture regFuture = bootstrap.register();
        serverChannel = regFuture.channel();
        if (regFuture.isDone() && !regFuture.isSuccess()) {
            regFuture.channel().close();
            throw new ConnectException("Failed to register HTTP signaling: " + regFuture.cause());
        }
        regFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                log.error("Failed to register HTTP signaling channel", future.cause());
                future.channel().close();
            }
        });
    }

    /**
     * Serves signaling on a TCP connection the host accepted itself, such as one on a port it
     * shares with another protocol, usually with {@code serveHttp} off. Call it on the connection's
     * event loop. What the host already read may be passed on after this returns, untouched and
     * PROXY header included, for instance by removing the decoder that held it. A connection handed
     * in after {@link #close} is closed.
     *
     * @param channel The accepted connection
     * @throws IllegalArgumentException If the channel is not an IP connection
     */
    public void initChannel(Channel channel) {
        if (!(channel.remoteAddress() instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Signaling needs an IP connection, not " + channel.remoteAddress());
        }
        channel.pipeline().addLast(this.connectionInitializer);
    }

    /**
     * Stops serving for good. Every connection is closed, those a host handed in included, and the
     * signaling cannot be bound again.
     */
    @Override
    public void close() {
        this.closed = true;
        if (serverChannel != null) {
            serverChannel.close();
        }
        connections.close();
        EventLoopGroup acceptor = this.acceptor;
        if (acceptor != null) {
            acceptor.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        ExecutorService validation = this.validation;
        if (validation != null) {
            validation.shutdownNow();
        }
        ScheduledFuture<?> discoveryTask = this.discoveryTask;
        if (discoveryTask != null) {
            discoveryTask.cancel(false);
        }
        DiscoveredEndpoints discovered = this.discovered;
        if (discovered != null) {
            discovered.close();
        }
    }

    @Override
    public boolean isActive() {
        Channel ch = this.serverChannel;
        return ch != null && ch.isActive();
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    /**
     * Serves this as the status document from here on, in place of whatever the builder set.
     */
    @Override
    public void setAdvertisementData(PongData pongData) {
        this.motdProvider = (host, remoteAddress, client) -> pongData;
    }

    @Override
    public void setMetrics(@Nullable NetherServerMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public OperatorIdentity serverIdentity() {
        return this.serverIdentity;
    }

    /**
     * Whether ICE may gather on the port signaling is bound to. Set it false when another
     * transport already holds the UDP side of that port, so ICE uses its own.
     */
    @Override
    public boolean allowsIceOnLocalPort() {
        return this.iceOnLocalPort;
    }

    @Override
    public boolean usesTrickleIce() {
        return false;
    }

    @Override
    public String getLocalNetworkId() {
        return "";
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
     * The configured advertised addresses as resolved when binding. When there are any, answers
     * announce these, see {@link Builder#setAdvertisedAddresses}. Safe to call from any thread.
     *
     * @return The endpoints, port 0 for an entry that names none, which answers announce on the
     *         port ICE uses. Empty before binding or when none are configured
     */
    public List<InetSocketAddress> advertisedAddresses() {
        return this.advertisedAddresses;
    }

    @Override
    public void setMediaRelayed(boolean relayed) {
        this.mediaRelayed = relayed;
    }

    /** Whether the endpoint is the ICE socket itself, port 0 standing for its port. */
    private static boolean isMediaEndpoint(InetSocketAddress endpoint, InetSocketAddress ice) {
        if (endpoint.getPort() != 0 && endpoint.getPort() != ice.getPort()) {
            return false;
        }
        InetAddress bound = ice.getAddress();
        if (bound != null && !bound.isAnyLocalAddress()) {
            return bound.equals(endpoint.getAddress());
        }
        try {
            return endpoint.getAddress().isLoopbackAddress() || NetworkInterface.getByInetAddress(endpoint.getAddress()) != null;
        } catch (SocketException e) {
            return false;
        }
    }

    /**
     * The public mappings STUN discovery currently finds, see {@link Builder#setStunServers}. Answers
     * announce these along with what ICE gathers and the endpoint the player typed in. Safe to call
     * from any thread.
     *
     * @return The endpoints, empty when discovery is off or no server has answered recently
     */
    public List<InetSocketAddress> discoveredEndpoints() {
        DiscoveredEndpoints discovered = this.discovered;
        return discovered == null ? List.of() : discovered.fresh();
    }

    /**
     * Answers an SDP offer, whether it arrived over this server's HTTP endpoint or was handed in
     * from outside it.
     * <p>
     * The returned description already carries the ICE candidates and the server identity
     * assertion, so it can be written back to the peer verbatim.
     * <p>
     * Callable from any thread. The identity is validated on the signaling's own thread and the
     * rest happens on the event loop, which is also where the future completes.
     *
     * @param networkId     The peer's network ID
     * @param sdpOffer      The raw SDP offer
     * @param clientAddress The address the offer came from, used for the peer's identity and to
     *                      seed the child channel as {@link AddressSource#HOST}, or null if it is
     *                      not known
     * @param host          The host the peer asked for, passed to the player filter
     * @param client        What the peer said about itself alongside the offer, or null if it said
     *                      nothing usable
     * @return The signed SDP answer, or a failure carrying an {@link OfferRejected}
     */
    public CompletableFuture<String> acceptOffer(String networkId, String sdpOffer,
                                                 @Nullable InetSocketAddress clientAddress,
                                                 @Nullable String host, @Nullable ClientInfo client) {
        SourcedAddress sourced = clientAddress == null ? null
                : new SourcedAddress(clientAddress, AddressSource.HOST, null);
        return acceptOffer(networkId, sdpOffer, sourced, host, client, null);
    }

    /** As the public one, with the endpoint the player typed in when its answer should carry it. */
    private CompletableFuture<String> acceptOffer(String networkId, String sdpOffer,
                                                  @Nullable SourcedAddress sourced, @Nullable String host,
                                                  @Nullable ClientInfo client, @Nullable InetSocketAddress typed) {
        InetSocketAddress clientAddress = sourced == null ? null : sourced.address();
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
            return admit(networkId, sdpOffer, sourced, host, client, claims, typed);
        }, loop).thenCompose(Function.identity());
    }

    @Override
    public void sendDescription(String targetNetworkId, String sdp) {
        log.debug("Sending sdp to " + targetNetworkId);

        PendingJoin join = pendingAnswers.get(targetNetworkId);
        if (join != null) {
            join.answer().trySuccess(announced(sdp, join.typed()));
        } else {
            log.debug("No pending join waiting for " + targetNetworkId);
        }
    }

    /**
     * The answer with what it announces besides what ICE gathered: the configured addresses when
     * there are any, else the mappings STUN discovered and the endpoint the player typed in.
     */
    private String announced(String sdp, @Nullable InetSocketAddress typed) {
        if (!this.advertisedAddresses.isEmpty()) {
            return this.mediaRelayed ? SdpUtil.withRelayedCandidates(sdp, this.advertisedAddresses)
                    : SdpUtil.withAdvertisedCandidates(sdp, this.advertisedAddresses);
        }
        List<InetSocketAddress> added = new ArrayList<>();
        DiscoveredEndpoints discovered = this.discovered;
        if (discovered != null) {
            added.addAll(discovered.fresh());
        }
        if (typed != null) {
            log.debug("Announcing {} from the Host header", typed);
            added.add(typed);
        }
        return SdpUtil.withAddedEndpoints(sdp, added);
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

    /**
     * The second half of a join, on the loop, once the identity is known to be good.
     */
    private CompletableFuture<String> admit(String networkId, String sdpOffer,
                                            @Nullable SourcedAddress sourced, @Nullable String host,
                                            @Nullable ClientInfo client, JwtClaims claims,
                                            @Nullable InetSocketAddress typed) {
        InetSocketAddress clientAddress = sourced == null ? null : sourced.address();
        PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"),
                claims.getClaimValueAsString("xname"), networkId, clientAddress, client, claims);
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
        PendingJoin join = new PendingJoin(answer, typed);
        // Never replace the answer owned by another offer for this network ID.
        if (pendingAnswers.putIfAbsent(networkId, join) != null) {
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
            pendingAnswers.remove(networkId, join);
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

        handler.onConnect(connectionId, networkId, sdpOffer, sourced, player);
        return result;
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

    /** The query's parameters, or none when it does not decode. */
    private static Map<String, List<String>> parameters(QueryStringDecoder uri) {
        try {
            return uri.parameters();
        } catch (IllegalArgumentException e) {
            return Map.of(); // A bad escape costs the client its info and nothing more
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

    /**
     * The peer address, or the address a trusted proxy declared or forwarded on its behalf.
     */
    private SourcedAddress clientAddress(ChannelHandlerContext ctx, HttpRequest req) {
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        // Only ever set for a PROXY header proxy, and the more trustworthy of the two, so it wins
        InetSocketAddress proxied = ctx.channel().attr(OptionalProxyProtocol.PROXIED_SOURCE).get();
        if (proxied != null) {
            return new SourcedAddress(proxied, AddressSource.SIGNALING_PROXY_PROTOCOL, remote);
        }

        SourcedAddress direct = new SourcedAddress(remote, AddressSource.SIGNALING, null);
        if (!forwardedForProxies.contains(remote)) {
            return direct;
        }

        String forwarded = req.headers().get(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return direct;
        }

        // Each trusted proxy appends where it took the request from, and anything left of that is
        // whatever the client sent, so the client is the rightmost entry no trusted proxy accounts for
        String[] entries = forwarded.split(",");
        InetAddress client = null;
        for (int i = entries.length - 1; i >= 0; i--) {
            client = literal(entries[i]);
            if (client == null) {
                return direct;
            }
            if (!forwardedForProxies.contains(client)) {
                break;
            }
        }
        return new SourcedAddress(new InetSocketAddress(client, remote.getPort()),
                AddressSource.SIGNALING_FORWARDED_FOR, remote);
    }

    /**
     * An address literal only. Resolving a name here would block this event loop on DNS and let
     * the header stand for whatever the answer happened to be.
     */
    private static @Nullable InetAddress literal(String entry) {
        String trimmed = entry.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return NetUtil.createInetAddressFromIpAddressString(trimmed);
    }

    /** Adds the configured headers to every response, see {@link Builder#setResponseHeaders}. */
    @ChannelHandler.Sharable
    private static final class ResponseHeaders extends ChannelOutboundHandlerAdapter {
        private final HttpHeaders headers;

        ResponseHeaders(HttpHeaders headers) {
            this.headers = headers;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof HttpResponse response) {
                response.headers().add(headers);
            }
            ctx.write(msg, promise);
        }
    }

    /** Builds the pipeline of every connection, accepted here or handed in by a host. */
    private class ConnectionInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            // Ahead of the handlers, so one handed in after close is closed before they look at it
            connections.add(ch);

            ChannelPipeline p = ch.pipeline();
            // Counted before any of what it sent is passed on, so a peer holding sockets open is
            // capped whatever it goes on to send
            p.addLast(new ConnectionLimiter(connectionLimitExempt, maxConnectionsPerAddress,
                    connectionsPerAddress, () -> metrics));

            // A PROXY header precedes the TLS handshake, so it is read before any of this
            if (!proxyProtocolProxies.isEmpty()) {
                p.addLast(new OptionalProxyProtocol(proxyProtocolProxies));
            }

            // Both schemes reach one port: the first bytes say which this is, and a
            // client that finds no TLS falls back to plaintext on the same port
            if (sslContext != null) {
                p.addLast(new OptionalSslHandler(sslContext));
            } else {
                p.addLast(new TlsRejectingHandler());
            }

            p.addLast(new HttpServerCodec());
            // Next to the codec, so responses the aggregator writes itself carry them as well
            if (responseHeaders != null) {
                p.addLast(responseHeaders);
            }
            p.addLast(new UnreadAnswersGuard());
            p.addLast(new HttpObjectAggregator(8 * 1024));
            p.addLast(new HttpLoggingHandler(log, (ctx, req) -> {
                SourcedAddress client = clientAddress(ctx, req);
                return client.via() == null ? client.address() : client.address() + " via " + client.via();
            }));
            // A kept connection that goes quiet is one nobody will come back to
            p.addLast(new IdleStateHandler(IDLE_SECONDS, 0, 0));
            p.addLast(new SignalingHandler());
        }
    }

    /**
     * Answers pile up past the write buffer only when the client stops reading them, and one that keeps
     * asking meanwhile would grow the queue without end, so it is dropped instead. Ahead of the aggregator,
     * which answers some requests itself.
     */
    private class UnreadAnswersGuard extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (ctx.channel().isWritable()) {
                ctx.fireChannelRead(msg);
                return;
            }
            ReferenceCountUtil.release(msg);
            if (ctx.channel().isOpen()) {
                log.debug("Closing the signaling connection from {}: it does not read its answers",
                        ctx.channel().remoteAddress());
                ctx.close();
            }
        }
    }

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

            QueryStringDecoder uri = new QueryStringDecoder(req.uri());
            String path = uri.path();
            HttpMethod method = req.method();
            String host = req.headers().get(HttpHeaderNames.HOST);
            SourcedAddress sourced = clientAddress(ctx, req);
            InetSocketAddress remoteAddress = sourced.address();

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

                ClientInfo client = ClientInfo.fromQuery(parameters(uri));

                PongData motd;
                try {
                    motd = motdProvider.getMotd(host, remoteAddress, client);
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

            ClientInfo client = ClientInfo.fromQuery(parameters(uri));
            InetSocketAddress typed = typedEndpoint(ctx, host);
            acceptOffer(networkId, sdpOffer, sourced, host, client, typed).whenComplete((sdpAnswer, failure) -> {
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
         * The endpoint the peer typed in, from its Host header, which is what an operator would
         * otherwise advertise. Only when none are configured, never through a proxy, whose Host names
         * the proxy, nor behind PROXY protocol relays, which players reach the media through, and only
         * an IP literal a peer could reach: a name would need a lookup per join.
         * <p>
         * When ICE shares the port the request arrived on, one forward carries both, so the typed
         * port is the outside one for media too. Otherwise the typed port is signaling's alone, and
         * the media port is assumed forwarded as it is.
         */
        private @Nullable InetSocketAddress typedEndpoint(ChannelHandlerContext ctx, @Nullable String host) {
            if (host == null || !advertisedAddresses.isEmpty() || mediaRelayed) {
                return null;
            }
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            if (forwardedForProxies.contains(remote) || proxyProtocolProxies.contains(remote)) {
                return null;
            }
            InetSocketAddress endpoint;
            try {
                endpoint = EndpointAddress.parseEndpoint(host);
            } catch (IllegalArgumentException notALiteral) {
                return null;
            }
            if (!EndpointAddress.advertisable(endpoint.getAddress(), false)) {
                return null;
            }
            InetSocketAddress ice = iceAddress;
            if (ice == null) {
                // Port 0 leaves each host candidate's own, for ICE without a fixed port
                return new InetSocketAddress(endpoint.getAddress(), 0);
            }
            boolean sharedPort = ctx.channel().localAddress() instanceof InetSocketAddress local
                    && local.getPort() == ice.getPort();
            int port = sharedPort && endpoint.getPort() != 0 ? endpoint.getPort() : ice.getPort();
            return new InetSocketAddress(endpoint.getAddress(), port);
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

    /**
     * Builder for {@link NetherNetHTTPServerSignaling}.
     * <p>
     * Only the identity is required, see {@link #setIdentity}. TLS is optional and comes from a
     * PEM pair or a PKCS12 keystore. The defaults suit a retail client on the open internet:
     * tokens are checked against Minecraft's auth service, TLS once served is required, and ICE
     * gathers on the signaling port's UDP side.
     */
    public static class Builder {
        private OperatorIdentity identity;
        private SslContext sslContext;
        private IpRangeSet forwardedForProxies = IpRangeSet.empty();
        private IpRangeSet proxyProtocolProxies = IpRangeSet.empty();
        private int maxConnectionsPerAddress = 8;
        private int maxPendingJoins = 64;
        private int answerTimeoutSeconds = 30;
        private boolean iceOnLocalPort = true;
        private List<InetSocketAddress> advertisedAddresses = List.of();
        private List<InetSocketAddress> stunServers = List.of();
        private Duration stunMaxAge = DiscoveredEndpoints.DEFAULT_MAX_AGE;
        private TokenTrust tokenTrust = TokenTrust.MINECRAFT_AUTH;
        private boolean serveHttp = true;
        private boolean requiresTls = true;
        private PlayerFilter playerFilter = (host, player) -> null;
        private MotdProvider motdProvider = (host, remoteAddress, client) -> PongData.DEFAULT;
        private HttpHeaders responseHeaders = new DefaultHttpHeaders();

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
         * @param domain      The name the identity carries, see {@link OperatorIdentity}
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
            // Netty reports an unreadable file as missing, so each is opened here first to say which and why
            requireReadable(certificateChain, "certificate chain");
            requireReadable(privateKey, "private key");
            try {
                return setSslContext(SslContextBuilder.forServer(certificateChain, privateKey, keyPassword).build());
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot use the TLS certificate chain " + certificateChain
                        + " with the private key " + privateKey, e);
            }
        }

        private static void requireReadable(File file, String what) {
            try (InputStream ignored = Files.newInputStream(file.toPath())) {
                // Readable
            } catch (NoSuchFileException e) {
                throw new IllegalArgumentException("The TLS " + what + " " + file + " does not exist");
            } catch (AccessDeniedException e) {
                throw new IllegalArgumentException("The TLS " + what + " " + file
                        + " cannot be read with the permissions this server runs with");
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read the TLS " + what + " " + file, e);
            }
        }

        /**
         * Caps the connections one address may hold at once. Defaults to 8, which is one player's
         * worth with room for a status check: players behind one NAT share an address, so a host
         * expecting a household or a LAN party behind one raises it. The proxies of either header
         * are exempt, since every client behind one shares its address.
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
         * Sets the reverse proxies whose {@code X-Forwarded-For} header is honored, such as an HTTP
         * load balancer. Requests from anywhere else keep their peer address. Empty by default.
         * <p>
         * The client is the rightmost entry that none of these proxies accounts for, as entries left
         * of it are whatever the client sent. Behind a chain of proxies, list every one of them.
         *
         * @param proxies Addresses or CIDR ranges, such as {@code 10.0.0.0/8}
         * @return This builder
         */
        public Builder setForwardedForProxies(Collection<String> proxies) {
            return setForwardedForProxies(IpRangeSet.parse(proxies));
        }

        /**
         * Sets the reverse proxies whose {@code X-Forwarded-For} header is honored, from a set
         * that is already parsed.
         *
         * @param proxies The proxies to trust
         * @return This builder
         */
        public Builder setForwardedForProxies(IpRangeSet proxies) {
            this.forwardedForProxies = proxies == null ? IpRangeSet.empty() : proxies;
            return this;
        }

        /**
         * Sets the proxies whose HAProxy PROXY header, v1 or v2, is read. Empty by default, which
         * reads none. A connection from one that carries no header is served normally, so a
         * listener can take both. The header is read before TLS, which is where a proxy puts it.
         *
         * @param proxies Addresses or CIDR ranges, such as {@code 10.0.0.0/8}
         * @return This builder
         */
        public Builder setProxyProtocolProxies(Collection<String> proxies) {
            return setProxyProtocolProxies(IpRangeSet.parse(proxies));
        }

        /**
         * Sets the proxies whose PROXY header is read, from a set that is already parsed.
         *
         * @param proxies The proxies to trust
         * @return This builder
         */
        public Builder setProxyProtocolProxies(IpRangeSet proxies) {
            this.proxyProtocolProxies = proxies == null ? IpRangeSet.empty() : proxies;
            return this;
        }

        /**
         * Sets whether ICE may gather on the port signaling binds to. Defaults to true, so a host
         * needs one port open for both. Set it false when another transport already holds the UDP
         * side of that port, which is the case for RakNet on the usual 19132, and give ICE its own
         * port through {@code NetherChannelOption.NETHER_SERVER_ICE_ADDRESS}.
         *
         * @param iceOnLocalPort Whether ICE may use the signaling port
         * @return This builder
         */
        public Builder setIceOnLocalPort(boolean iceOnLocalPort) {
            this.iceOnLocalPort = iceOnLocalPort;
            return this;
        }

        /**
         * Sets the endpoints announced as ICE candidates. Each is an IP literal or a host name, alone
         * or with a port as {@code host:port}, {@code IPv4:port} or {@code [IPv6]:port}; one without a
         * port takes the media port. Listed endpoints this host holds narrow its host candidates to
         * those; any other is announced as the public side of a NAT forwarding it to the media port
         * here. A signaling proxy's address does not belong here unless it also forwards the media
         * port.
         * <p>
         * A name stands for every address it resolves to. It is looked up once, when binding, which
         * fails if it does not resolve, so a change takes a new bind. Binding also fails beyond
         * {@value AdvertisedAddresses#MAX_ENDPOINTS} endpoints.
         * <p>
         * Empty, the default, announces everything ICE gathers, plus what {@link #setStunServers}
         * finds and the IP literal a player typed in, when those are not among them. The typed one
         * reaches a host behind NAT or in a container nobody configured, but not a player who typed
         * a name or arrived through a trusted proxy. Behind PROXY protocol relays, list the relays.
         * Left empty there, answers carry this host's own addresses, which only works when a front
         * rewrites them to the relays, and binding warns.
         *
         * @param advertisedAddresses Endpoints reachable by connecting peers
         * @return This builder
         * @throws IllegalArgumentException If an entry is none of those forms, or an address no player
         *                                  can reach
         * @see SdpUtil#withAdvertisedCandidates
         */
        public Builder setAdvertisedAddresses(Collection<String> advertisedAddresses) {
            this.advertisedAddresses = advertisedAddresses == null ? List.of()
                    : AdvertisedAddresses.parse(advertisedAddresses, 0);
            return this;
        }

        /**
         * Finds this host's public address through these STUN servers and adds it to every answer,
         * the way {@link #setAdvertisedAddresses} adds an endpoint this host does not hold. Off by
         * default. Each is a {@code stun:host[:port]} URL, port 3478 when it names none.
         * <p>
         * They are asked from the ICE port in the background, every 15 seconds, which also keeps the
         * NAT mapping open, so no join waits for them. A mapping is announced until
         * {@link #setStunMaxAge} after its last answer, or until the server reports another. That
         * suits a router that keeps one mapping for all incoming traffic, as a port forward does. A
         * monitor that fails is opened again after {@link StunObserver#RETRY_DELAY}, see
         * {@link StunObserver}. A router that moved the port on the way
         * out, which it does when the port is taken outside, often by a forward to this host, gets
         * the address announced on the ICE port as well, costing a peer one failed check when there
         * is no such forward.
         * <p>
         * Binding fails without a fixed ICE port, see {@link #setIceOnLocalPort}, or behind PROXY
         * protocol relays. Building fails when advertised addresses are set too.
         *
         * @param stunServers The servers to ask, empty for none
         * @return This builder
         * @throws IllegalArgumentException If an entry is not a {@code stun:} URL
         */
        public Builder setStunServers(Collection<String> stunServers) {
            this.stunServers = stunServers == null ? List.of() : DiscoveredEndpoints.servers(stunServers);
            return this;
        }

        /**
         * Sets how long a mapping found through {@link #setStunServers} is still announced after its
         * last answer, as long as the server reports no other. Defaults to 5 minutes.
         *
         * @param maxAge The age past which a mapping is dropped, must be positive
         * @return This builder
         */
        public Builder setStunMaxAge(Duration maxAge) {
            if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
                throw new IllegalArgumentException("maxAge");
            }
            this.stunMaxAge = maxAge;
            return this;
        }

        /**
         * Sets who to trust to have signed the token in a joining peer's identity assertion.
         * Defaults to {@link TokenTrust#MINECRAFT_AUTH}, which is what a retail client presents.
         * A peer built with this library, such as a proxy presenting
         * {@link OperatorIdentity#forPlayer}, signs its own token, which only
         * {@link TokenTrust#ANY} accepts.
         *
         * @param tokenTrust The trust policy
         * @return This builder
         */
        public Builder setTokenTrust(TokenTrust tokenTrust) {
            this.tokenTrust = tokenTrust;
            return this;
        }

        /**
         * Sets whether to serve the HTTP join endpoint. Defaults to true. With it off nothing is
         * listened on: a host hands in the connections it accepted through
         * {@link NetherNetHTTPServerSignaling#initChannel}, or an endpoint outside this process hands
         * in offers through {@link NetherNetHTTPServerSignaling#acceptOffer}.
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
         * Sets a fixed MOTD to advertise for every status request. The same as
         * {@code setAdvertisementData} on the built signaling.
         *
         * @param motd The MOTD to advertise
         * @return This builder
         */
        public Builder setMotd(PongData motd) {
            return setMotdProvider((host, remoteAddress, client) -> motd);
        }

        /**
         * Adds headers to every signaling response, refusals included, for example to tell a proxy in
         * front what this host supports. None by default. {@code Content-Type}, {@code Content-Length},
         * {@code Content-Encoding}, {@code Transfer-Encoding}, {@code Connection}, {@code Keep-Alive}
         * and {@code Upgrade} are reserved.
         *
         * @param headers Header names to values
         * @return This builder
         * @throws IllegalArgumentException If a name or value is invalid, or the name is reserved
         */
        public Builder setResponseHeaders(Map<String, String> headers) {
            HttpHeaders validated = new DefaultHttpHeaders();
            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("Signaling sets the " + name + " header itself");
                    }
                    validated.add(name, value);
                });
            }
            this.responseHeaders = validated;
            return this;
        }

        /**
         * Builds the signaling instance.
         *
         * @return A new signaling instance
         * @throws IllegalStateException If no identity was set, or STUN discovery and advertised
         *                               addresses both were
         */
        public NetherNetHTTPServerSignaling build() {
            if (identity == null) {
                throw new IllegalStateException("An identity is required");
            }
            if (!stunServers.isEmpty() && !advertisedAddresses.isEmpty()) {
                throw new IllegalStateException("STUN discovery and advertised addresses cannot both be set");
            }

            return new NetherNetHTTPServerSignaling(this);
        }
    }
}
