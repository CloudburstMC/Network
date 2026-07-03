package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
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
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   proceeds with the SDP exchange.
 * - POST /v1/join/{networkId}: the request body is the client's SDP offer;
 *   the response body is the full ICE SDP answer (application/sdp). The
 *   whole exchange fits one round trip, so {@link #fullIceAnswers()} is true
 *   and no candidate signals flow in either direction.
 *
 * Connections are one shot (Connection: close), offers are capped at 1 MiB,
 * and a negotiation that produces no answer within the timeout responds 502
 * and tears the half negotiated connection down.
 *
 * TLS: current clients require HTTPS with a certificate that validates
 * against the server's IP address; pass an SslContext for that. A null
 * SslContext serves plaintext, which works behind a TLS terminating reverse
 * proxy today and directly once clients implement the TOFU trust flow.
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
    private final String localNetworkId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());

    private final Map<Long, SignalHandler> signalHandlers = new ConcurrentHashMap<>();
    private final Map<Long, PendingExchange> pendingExchanges = new ConcurrentHashMap<>();

    private volatile NewConnectionHandler newConnectionHandler;
    private volatile Channel serverChannel;
    // The TCP accept loop. Owned: bind() is called from the NetherNet server
    // channel's event loop, so registering the listener on a caller supplied
    // group and waiting for the bind would deadlock a single threaded group
    // against itself (netty rejects it as a blocking call on the event loop).
    private volatile NioEventLoopGroup acceptGroup;
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
        this.sslContextSupplier = sslContextSupplier;
        this.workerGroup = workerGroup;
    }

    @Override
    public void bind(SocketAddress localAddress) throws ConnectException {
        try {
            this.acceptGroup = new NioEventLoopGroup(1);
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(acceptGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            SslContext sslContext = sslContextSupplier.get();
                            if (sslContext != null) {
                                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                            }
                            ch.pipeline().addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS));
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(MAX_OFFER_BYTES + 8192));
                            ch.pipeline().addLast(new SignalingRequestHandler());
                        }
                    });
            this.serverChannel = bootstrap.bind(localAddress).sync().channel();
            log.info("HTTP signaling listening on {} ({})", localAddress,
                    sslContextSupplier.get() != null ? "TLS" : "plaintext");
        } catch (Exception e) {
            ConnectException ce = new ConnectException("Failed to bind HTTP signaling listener: " + e.getMessage());
            ce.initCause(e);
            throw ce;
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
    public void close() {
        closed = true;
        Channel channel = this.serverChannel;
        if (channel != null) {
            channel.close();
            this.serverChannel = null;
        }
        NioEventLoopGroup accept = this.acceptGroup;
        if (accept != null) {
            accept.shutdownGracefully(0, 3, TimeUnit.SECONDS);
            this.acceptGroup = null;
        }
        for (Long connectionId : pendingExchanges.keySet()) {
            PendingExchange exchange = pendingExchanges.remove(connectionId);
            if (exchange != null) {
                exchange.cancelTimeout();
                respond(exchange.ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "text/plain", "Server shutting down");
            }
        }
        signalHandlers.clear();
    }

    /**
     * One in flight offer/answer exchange: the HTTP context awaiting the
     * answer, the peer's address, and the negotiation timeout reaping it.
     */
    private static final class PendingExchange {
        final ChannelHandlerContext ctx;
        final InetSocketAddress remoteAddress;
        volatile ScheduledFuture<?> timeout;

        PendingExchange(ChannelHandlerContext ctx, InetSocketAddress remoteAddress) {
            this.ctx = ctx;
            this.remoteAddress = remoteAddress;
        }

        void cancelTimeout() {
            ScheduledFuture<?> t = this.timeout;
            if (t != null) {
                t.cancel(false);
            }
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
                    respond(ctx, HttpResponseStatus.OK, "text/plain", "");
                }
                return;
            }
            if (HttpMethod.POST.equals(request.method()) && path.startsWith(OFFER_PATH_PREFIX)) {
                handleOffer(ctx, request, path.substring(OFFER_PATH_PREFIX.length()));
                return;
            }
            respond(ctx, HttpResponseStatus.NOT_FOUND, "text/plain", "Not found");
        }

        private void handleOffer(ChannelHandlerContext ctx, FullHttpRequest request, String networkId) {
            // The NetworkID is opaque but currently always a uint64 rendered
            // as a string; validating mirrors the reference implementation.
            try {
                Long.parseUnsignedLong(networkId);
            } catch (NumberFormatException e) {
                respond(ctx, HttpResponseStatus.BAD_REQUEST, "text/plain", "Network ID must be uint64");
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
            exchange.timeout = ctx.channel().eventLoop().schedule(() -> {
                if (pendingExchanges.remove(connectionId) != null) {
                    log.debug("Negotiation for {} timed out waiting for the answer", Long.toUnsignedString(connectionId));
                    respond(ctx, HttpResponseStatus.BAD_GATEWAY, "text/plain", "Timed out waiting for answer");
                    SignalHandler signalHandler = signalHandlers.get(connectionId);
                    if (signalHandler != null) {
                        signalHandler.onSignal(NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR + " "
                                + Long.toUnsignedString(connectionId) + " negotiation timeout");
                    }
                }
            }, NEGOTIATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

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

            log.debug("Offer for {} from network {} via HTTP", Long.toUnsignedString(connectionId), networkId);
            handler.onConnect(connectionId, networkId, offerSdp);
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
