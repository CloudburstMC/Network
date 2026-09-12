package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.NetherNetServerStatus;
import org.cloudburstmc.netty.channel.nethernet.NetherNetOfferValidator;
import org.cloudburstmc.netty.util.nethernet.ClientAssertionValidator;
import org.cloudburstmc.netty.util.nethernet.ClientIdentity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * NetherNet's HTTP signaling front end: the direct connection model that
 * replaces RakNet for updated clients, per Mojang's NetherNet onboarding
 * guide. Listens on TCP under the same port RakNet serves on UDP; the client
 * probes it before falling back to RakNet, so every failure mode here lands
 * on a working RakNet join.
 *
 * Endpoints:
 * - GET /v1/join: any 2xx means NetherNet is supported and the client
 *   proceeds with the SDP exchange. Carries the
 *   {@link NetherNetServerStatus} document when a supplier is set, which
 *   vanilla 1.26.50 and later answer with as their equivalent of the RakNet
 *   unconnected pong; older clients ignore the body.
 * - POST /v1/join/{networkId}: the request body is the client's SDP offer;
 *   the response body is the full ICE SDP answer (application/sdp). The
 *   whole exchange fits one round trip, so {@link #fullIceAnswers()} is true
 *   and no candidate signals flow in either direction.
 *
 * Connections are one shot (Connection: close), offers are capped at 1 MiB,
 * and a negotiation that produces no answer within the timeout responds 502
 * and tears the half negotiated connection down.
 *
 * TLS: pre 26.40 clients require HTTPS with a certificate that validates
 * against the server's IP address; pass an SslContext for that. 26.40 and
 * later also accept plain HTTP, trusting the server identity through their
 * TOFU flow. The listener serves both on the one port: each connection's
 * protocol is selected from its first bytes, so with a certificate present
 * TLS and plaintext both work, and with none (or behind a TLS terminating
 * reverse proxy) plaintext alone is served and TLS speakers are closed
 * immediately.
 */
public class NetherNetHttpSignaling implements NetherNetServerSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetHttpSignaling.class);

    private static final String JOIN_PATH = "/v1/join";
    private static final String OFFER_PATH_PREFIX = "/v1/join/";
    /** Mirrors the go-nethernet reference cap for SDP bodies. */
    private static final int MAX_OFFER_BYTES = 1 << 20;
    private static final int READ_TIMEOUT_SECONDS = 10;
    private static final long NEGOTIATION_TIMEOUT_SECONDS = 15;

    private final Supplier<SslContext> sslContextSupplier;
    private final EventLoopGroup workerGroup;
    private final Supplier<? extends EventLoopGroup> acceptGroupFactory;
    private final Supplier<? extends ExecutorService> validationExecutorFactory;
    private final Object lifecycleLock = new Object();
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private final String localNetworkId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());

    private final Map<Long, SignalHandler> signalHandlers = new ConcurrentHashMap<>();
    private final Map<Long, PendingExchange> pendingExchanges = new ConcurrentHashMap<>();

    private volatile Supplier<NetherNetServerStatus> statusSupplier;
    // Racing a duplicate first warn is harmless; this only bounds the spam.
    private volatile boolean statusSupplierWarned;
    private volatile NewConnectionHandler newConnectionHandler;
    private volatile NetherNetOfferValidator offerValidator = new ClientAssertionValidator();
    private ExecutorService validationExecutor;
    private volatile Channel serverChannel;
    // The TCP accept loop. Owned: bind() is called from the NetherNet server
    // channel's event loop, so registering the listener on a caller supplied
    // group and waiting for the bind would deadlock a single threaded group
    // against itself (netty rejects it as a blocking call on the event loop).
    private EventLoopGroup acceptGroup;
    private volatile boolean closed;

    /**
     * @param sslContext  server TLS context, or null to serve plaintext
     * @param workerGroup connection I/O loops; not owned, never shut down here
     */
    public NetherNetHttpSignaling(SslContext sslContext, EventLoopGroup workerGroup) {
        this(() -> sslContext, workerGroup);
    }

    /**
     * @param sslContextSupplier consulted per accepted connection: non null
     *                           enables TLS with that context, null serves
     *                           plaintext. Lets certificate rotation (or late
     *                           issuance) apply to new connections without a
     *                           rebind; signaling connections are one shot,
     *                           so nothing needs draining.
     * @param workerGroup        connection I/O loops; not owned, never shut
     *                           down here
     */
    public NetherNetHttpSignaling(Supplier<SslContext> sslContextSupplier, EventLoopGroup workerGroup) {
        this(sslContextSupplier, workerGroup, () -> new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()));
    }

    NetherNetHttpSignaling(Supplier<SslContext> sslContextSupplier, EventLoopGroup workerGroup,
                          Supplier<? extends EventLoopGroup> acceptGroupFactory) {
        this(sslContextSupplier, workerGroup, acceptGroupFactory, NetherNetHttpSignaling::newValidationExecutor);
    }

    NetherNetHttpSignaling(Supplier<SslContext> sslContextSupplier, EventLoopGroup workerGroup,
                          Supplier<? extends EventLoopGroup> acceptGroupFactory,
                          Supplier<? extends ExecutorService> validationExecutorFactory) {
        this.sslContextSupplier = Objects.requireNonNull(sslContextSupplier, "sslContextSupplier");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.acceptGroupFactory = Objects.requireNonNull(acceptGroupFactory, "acceptGroupFactory");
        this.validationExecutorFactory = Objects.requireNonNull(validationExecutorFactory, "validationExecutorFactory");
    }

    /**
     * Configures offer validation and optional application authorization. The default
     * verifies Minecraft-issued tokens and their SDP fingerprint signatures. Set null
     * to explicitly opt out. Changes apply to subsequent offers; validators run outside
     * I/O loops and must not log bearer tokens or include them in exception messages.
     */
    public void setOfferValidator(NetherNetOfferValidator validator) {
        this.offerValidator = validator;
    }

    private static ExecutorService newValidationExecutor() {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new DefaultThreadFactory("nethernet-identity", true));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ExecutorService validationExecutor() {
        synchronized (lifecycleLock) {
            if (closed) throw new RejectedExecutionException("HTTP signaling is closed");
            if (validationExecutor == null) {
                validationExecutor = Objects.requireNonNull(validationExecutorFactory.get(), "validation executor");
            }
            return validationExecutor;
        }
    }

    /**
     * Sets the source of the server status answered on the capability check,
     * or null to answer with an empty body as before. Consulted per request,
     * so live values (the player count above all) need no republishing; the
     * consumer owns any caching, since this endpoint is reachable by anyone
     * who can open a TCP connection.
     *
     * Called from connection I/O threads; implementations must be thread
     * safe and return promptly. A failure is treated as no status being
     * available and never turns the capability check negative.
     */
    public void setStatusSupplier(Supplier<NetherNetServerStatus> statusSupplier) {
        this.statusSupplier = statusSupplier;
    }

    @Override
    public void bind(SocketAddress localAddress) throws ConnectException {
        Objects.requireNonNull(localAddress, "localAddress");
        EventLoopGroup accept;
        synchronized (lifecycleLock) {
            if (closed) {
                throw new ConnectException("HTTP signaling is closed");
            }
            if (acceptGroup != null) {
                throw new ConnectException("HTTP signaling is already bound or binding");
            }
            accept = Objects.requireNonNull(acceptGroupFactory.get(), "acceptGroupFactory returned null");
            acceptGroup = accept;
        }

        ChannelFuture bind = null;
        boolean bound = false;
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(accept, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            initConnection(ch);
                        }
                    });
            bind = bootstrap.bind(localAddress);
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new ConnectException("HTTP signaling closed during bind");
                }
                serverChannel = bind.channel();
            }
            bind.sync();
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new ConnectException("HTTP signaling closed during bind");
                }
                bound = true;
            }
            log.info("HTTP signaling listening on {}", bind.channel().localAddress());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ConnectException ce = new ConnectException("Failed to bind HTTP signaling listener: " + e.getMessage());
            ce.initCause(e);
            throw ce;
        } finally {
            if (!bound) {
                try {
                    if (bind != null) {
                        bind.channel().close();
                    }
                } finally {
                    close();
                }
            }
        }
    }

    void initConnection(Channel channel) {
        connections.add(channel);
        if (closed) {
            channel.close();
            return;
        }
        ReadTimeoutHandler requestTimeout = new ReadTimeoutHandler(READ_TIMEOUT_SECONDS);
        HttpServerCodec httpCodec = new HttpServerCodec();
        channel.pipeline().addLast(requestTimeout);
        channel.pipeline().addLast(new ProtocolSelectingHandler());
        channel.pipeline().addLast(httpCodec);
        channel.pipeline().addLast(new SingleRequestHandler(requestTimeout, httpCodec));
        channel.pipeline().addLast(new HttpObjectAggregator(MAX_OFFER_BYTES + 8192));
        channel.pipeline().addLast(new SignalingRequestHandler());
    }

    private static final class SingleRequestHandler extends ChannelInboundHandlerAdapter {
        private final ReadTimeoutHandler requestTimeout;
        private final HttpServerCodec httpCodec;
        private boolean complete;

        private SingleRequestHandler(ReadTimeoutHandler requestTimeout, HttpServerCodec httpCodec) {
            this.requestTimeout = requestTimeout;
            this.httpCodec = httpCodec;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (complete) {
                ReferenceCountUtil.release(message);
                return;
            }
            if (message instanceof LastHttpContent) {
                complete = true;
                ctx.pipeline().remove(requestTimeout);
                // Keep the response encoder while discarding subsequent pipelined input.
                // The pending offer now has its own negotiation deadline.
                httpCodec.removeInboundHandler();
            }
            ctx.fireChannelRead(message);
        }
    }

    /**
     * The address the HTTP listener actually bound, resolving an ephemeral
     * port request to the assigned port. Null before {@link #bind}.
     */
    public InetSocketAddress boundAddress() {
        Channel channel = this.serverChannel;
        return channel != null ? (InetSocketAddress) channel.localAddress() : null;
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // No MOTD endpoint exists on the HTTP path yet; server list ping
        // stays with RakNet. Kept as a hook for a future /v1/motd.
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        String[] parts = data.split(" ", 3);
        if (parts.length < 2) {
            return;
        }
        long connectionId;
        try {
            connectionId = Long.parseUnsignedLong(parts[1]);
        } catch (NumberFormatException e) {
            return;
        }

        switch (parts[0]) {
            case NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE -> {
                PendingExchange exchange = pendingExchanges.remove(connectionId);
                if (exchange == null) {
                    log.debug("Answer for {} arrived after its exchange completed or timed out",
                            Long.toUnsignedString(connectionId));
                    return;
                }
                exchange.cancelTimeout();
                respond(exchange.ctx, HttpResponseStatus.OK, "application/sdp", parts.length > 2 ? parts[2] : "");
            }
            case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                PendingExchange exchange = pendingExchanges.remove(connectionId);
                if (exchange != null) {
                    exchange.cancelTimeout();
                    respond(exchange.ctx, HttpResponseStatus.BAD_REQUEST, "text/plain",
                            "Negotiation failed" + (parts.length > 2 ? ": " + parts[2] : ""));
                }
            }
            case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                // Full ICE: candidates ride inside the answer; nothing to
                // trickle on a request/response medium.
            }
            default -> log.debug("Dropping unsupported outbound signal type {} for {}",
                    parts[0], Long.toUnsignedString(connectionId));
        }
    }

    @Override
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        signalHandlers.put(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        signalHandlers.remove(connectionId);
    }

    @Override
    public String getLocalNetworkId() {
        return localNetworkId;
    }

    @Override
    public boolean fullIceAnswers() {
        return true;
    }

    @Override
    public InetSocketAddress remoteAddressOf(long connectionId) {
        PendingExchange exchange = pendingExchanges.get(connectionId);
        return exchange != null ? exchange.remoteAddress : null;
    }

    @Override
    public ClientIdentity clientIdentityOf(long connectionId) {
        PendingExchange exchange = pendingExchanges.get(connectionId);
        return exchange != null ? exchange.identity : null;
    }

    @Override
    public boolean isConnectionPending(long connectionId) {
        return pendingExchanges.containsKey(connectionId);
    }

    /** Closes the listener and accepted connections. This instance cannot be rebound. */
    @Override
    public void close() {
        Channel channel;
        EventLoopGroup accept;
        ExecutorService validation;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            channel = serverChannel;
            accept = acceptGroup;
            validation = validationExecutor;
            this.serverChannel = null;
            this.acceptGroup = null;
            this.validationExecutor = null;
        }
        try {
            if (channel != null) {
                channel.close();
            }
            for (Long connectionId : pendingExchanges.keySet()) {
                PendingExchange exchange = pendingExchanges.remove(connectionId);
                if (exchange != null) {
                    exchange.cancelTimeout();
                    respond(exchange.ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Server shutting down");
                }
            }
            signalHandlers.clear();
        } finally {
            try {
                if (validation != null) validation.shutdownNow();
                connections.close();
            } finally {
                if (accept != null) {
                    accept.shutdownGracefully(0, 3, TimeUnit.SECONDS);
                }
            }
        }
    }

    /**
     * One in flight offer/answer exchange: the HTTP context awaiting the
     * answer, the peer's address, and the negotiation timeout reaping it.
     */
    static final class PendingExchange {
        final ChannelHandlerContext ctx;
        final InetSocketAddress remoteAddress;
        private ScheduledFuture<?> timeout;
        private Future<?> validationTask;
        private volatile ClientIdentity identity;
        private boolean completed;

        PendingExchange(ChannelHandlerContext ctx, InetSocketAddress remoteAddress) {
            this.ctx = ctx;
            this.remoteAddress = remoteAddress;
        }

        synchronized void setTimeout(ScheduledFuture<?> timeout) {
            // Close can complete an exchange between its map insertion and timer assignment.
            if (completed) {
                timeout.cancel(false);
            } else {
                this.timeout = timeout;
            }
        }

        synchronized void cancelTimeout() {
            completed = true;
            if (validationTask != null) {
                validationTask.cancel(true);
                validationTask = null;
            }
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }

        synchronized void setValidationTask(Future<?> task) {
            if (completed) task.cancel(true);
            else validationTask = task;
        }
    }

    private final class SignalingRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (!request.decoderResult().isSuccess()) {
                respond(ctx, HttpResponseStatus.BAD_REQUEST, "text/plain", "Malformed request");
                return;
            }
            String path = request.uri();
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }

            if (HttpMethod.GET.equals(request.method()) && JOIN_PATH.equals(path)) {
                // Any 2xx tells the client NetherNet is supported here.
                if (newConnectionHandler == null || closed) {
                    respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Service unavailable");
                } else {
                    respondStatus(ctx);
                }
                return;
            }
            if (HttpMethod.POST.equals(request.method()) && path.startsWith(OFFER_PATH_PREFIX)) {
                handleOffer(ctx, request, path.substring(OFFER_PATH_PREFIX.length()));
                return;
            }
            respond(ctx, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found");
        }

        private void handleOffer(ChannelHandlerContext ctx, FullHttpRequest request, String encodedNetworkId) {
            String networkId;
            try {
                if (encodedNetworkId.isEmpty() || encodedNetworkId.indexOf('/') >= 0
                        || encodedNetworkId.indexOf('#') >= 0) {
                    throw new IllegalArgumentException("Expected one Network ID path segment");
                }
                // Decode to bytes first so invalid UTF-8 cannot collapse distinct IDs.
                // Path decoding preserves literal '+' and decodes percent escapes once.
                String decodedBytes = new QueryStringDecoder("/" + encodedNetworkId, StandardCharsets.ISO_8859_1)
                        .path().substring(1);
                networkId = StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(decodedBytes.getBytes(StandardCharsets.ISO_8859_1))).toString();
            } catch (IllegalArgumentException | CharacterCodingException e) {
                respond(ctx, HttpResponseStatus.BAD_REQUEST, "text/plain", "Invalid Network ID path segment");
                return;
            }
            NewConnectionHandler handler = newConnectionHandler;
            if (handler == null || closed) {
                respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Service unavailable");
                return;
            }
            String offerSdp = request.content().toString(StandardCharsets.UTF_8);
            if (offerSdp.isEmpty()) {
                respond(ctx, HttpResponseStatus.BAD_REQUEST, "text/plain", "Missing SDP offer in request body");
                return;
            }

            long connectionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            PendingExchange exchange = new PendingExchange(ctx, (InetSocketAddress) ctx.channel().remoteAddress());
            pendingExchanges.put(connectionId, exchange);

            // Reap a negotiation that produces no answer in time: 502 to the
            // client, CONNECTERROR inward so the server channel closes the
            // half negotiated child.
            exchange.setTimeout(ctx.channel().eventLoop().schedule(() -> {
                if (pendingExchanges.remove(connectionId, exchange)) {
                    exchange.cancelTimeout();
                    log.debug("Negotiation for {} timed out waiting for the answer", Long.toUnsignedString(connectionId));
                    respond(ctx, HttpResponseStatus.BAD_GATEWAY, "text/plain", "Timed out waiting for answer");
                    SignalHandler signalHandler = signalHandlers.get(connectionId);
                    if (signalHandler != null) {
                        signalHandler.onSignal(NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR + " "
                                + Long.toUnsignedString(connectionId) + " negotiation timeout");
                    }
                }
            }, NEGOTIATION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

            // A client that disconnects mid negotiation leaves the answer with
            // nowhere to go; drop the exchange so the timeout does not fire a
            // response into a dead channel. Completed exchanges were already
            // removed by then and this is a no-op.
            ctx.channel().closeFuture().addListener(future -> {
                PendingExchange orphan = pendingExchanges.remove(connectionId);
                if (orphan != null) {
                    orphan.cancelTimeout();
                }
            });

            NetherNetOfferValidator validator = offerValidator;
            if (validator == null) {
                dispatchOffer(connectionId, exchange, networkId, offerSdp, handler, null, null);
                return;
            }
            FutureTask<Void> task = new FutureTask<>(() -> {
                ClientIdentity identity = null;
                Exception failure = null;
                try {
                    identity = Objects.requireNonNull(validator.validate(offerSdp), "Validator returned no identity");
                } catch (Exception e) {
                    failure = e;
                }
                ClientIdentity verified = identity;
                Exception error = failure;
                try {
                    ctx.executor().execute(() -> dispatchOffer(connectionId, exchange, networkId, offerSdp,
                            handler, verified, error));
                } catch (RejectedExecutionException e) {
                    // The existing negotiation deadline still reaps the exchange if the loop recovers.
                    log.debug("Identity completion could not reach the HTTP event loop for {}",
                            Long.toUnsignedString(connectionId));
                }
                return null;
            });
            exchange.setValidationTask(task);
            try {
                if (!task.isCancelled()) validationExecutor().execute(task);
            } catch (RejectedExecutionException e) {
                if (pendingExchanges.remove(connectionId, exchange)) {
                    exchange.cancelTimeout();
                    respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Identity validation unavailable");
                }
            }
        }

        private void dispatchOffer(long connectionId, PendingExchange exchange, String networkId, String offerSdp,
                                   NewConnectionHandler handler, ClientIdentity identity, Exception failure) {
            if (closed || !exchange.ctx.channel().isActive() || pendingExchanges.get(connectionId) != exchange) return;
            if (failure != null) {
                if (pendingExchanges.remove(connectionId, exchange)) {
                    exchange.cancelTimeout();
                    respond(exchange.ctx, HttpResponseStatus.BAD_REQUEST, "text/plain", "Client assertion rejected");
                }
                return;
            }
            exchange.identity = identity;
            log.debug("Offer for {} from network {} via HTTP", Long.toUnsignedString(connectionId), networkId);
            try {
                handler.onConnect(connectionId, networkId, offerSdp);
            } catch (Exception e) {
                if (pendingExchanges.remove(connectionId, exchange)) {
                    exchange.cancelTimeout();
                    signalHandlers.remove(connectionId);
                    exchange.ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Routine noise: TLS handshakes from probing clients without our
            // certificate trusted, port scanners, read timeouts.
            log.debug("HTTP signaling connection error: {}", cause.toString());
            ctx.close();
        }
    }

    /**
     * Selects the connection's protocol from its first bytes, so TLS and
     * plaintext are both served on the one port a client ever derives from
     * the join address. A TLS record installs the SslHandler when a
     * certificate is available, and closes the connection when none is,
     * before any handshake byte can reach the HTTP codec. Everything else
     * proceeds as plaintext HTTP, which keeps signaling reachable for
     * clients whose TLS attempt failed against a broken certificate: 26.40
     * and later retry over plain HTTP and trust the server identity through
     * their TOFU flow, so certificate rot degrades to a confirmation prompt
     * instead of losing NetherNet entirely.
     */
    private final class ProtocolSelectingHandler extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            // A TLS record is recognizable from its first five bytes.
            if (in.readableBytes() < 5) {
                return;
            }
            if (SslHandler.isEncrypted(in, false)) {
                SslContext sslContext = sslContextSupplier.get();
                if (sslContext == null) {
                    // TLS spoken at a certificate-less server: close instead
                    // of letting handshake bytes reach the HTTP codec. The
                    // immediate close is also the fastest signal for the
                    // client's own fallback ladder.
                    ctx.close();
                    return;
                }
                ctx.pipeline().addAfter(ctx.name(), null, sslContext.newHandler(ctx.alloc()));
            }
            // Removing the decoder forwards the buffered bytes to whichever
            // handler now follows: the SslHandler for TLS, the HTTP codec
            // for plaintext.
            ctx.pipeline().remove(this);
        }
    }

    /**
     * Answers the capability check, carrying the server status document when
     * the consumer supplies one. A supplier that is absent or that fails
     * leaves the historical empty body, which every client accepts: the guide
     * defines the body as ignored, and only 1.26.50 and later read it.
     */
    private void respondStatus(ChannelHandlerContext ctx) {
        Supplier<NetherNetServerStatus> supplier = this.statusSupplier;
        if (supplier == null) {
            respond(ctx, HttpResponseStatus.OK, "text/plain", "");
            return;
        }
        String body;
        try {
            NetherNetServerStatus status = supplier.get();
            body = status == null ? null : status.toJson();
        } catch (Throwable e) {
            // The probe decides whether the client attempts NetherNet at all,
            // so it must still answer 2xx when the consumer cannot describe
            // itself. Older clients ignore the body outright; what a reading
            // client makes of an absent one is not established. Throwable
            // rather than Exception: an Error escaping here reaches
            // exceptionCaught, which closes the connection with no response
            // at all, and a client reads that as no NetherNet rather than as
            // a server that cannot currently describe itself.
            //
            // Warn once, then debug: the endpoint is unauthenticated TCP, so
            // per request warns would let anyone drive log volume by
            // hammering the probe against a broken supplier.
            if (statusSupplierWarned) {
                log.debug("Server status supplier failed: {}", e.getMessage());
            } else {
                statusSupplierWarned = true;
                log.warn("Server status supplier failed (further failures log at debug): {}", e.getMessage());
            }
            body = null;
        }
        if (body == null) {
            respond(ctx, HttpResponseStatus.OK, "text/plain", "");
        } else {
            respond(ctx, HttpResponseStatus.OK, "application/json", body);
        }
    }

    /**
     * Writes a one shot response: Connection: close and the channel closed
     * after the write, matching the one request per connection model. Safe
     * from any thread.
     */
    private static void respond(ChannelHandlerContext ctx, HttpResponseStatus status, String contentType, String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
