package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.jspecify.annotations.Nullable;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.util.http.HttpLoggingHandler;
import org.cloudburstmc.netty.util.http.TlsRejectingHandler;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import io.netty.util.AsciiString;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.Set;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
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
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class implements a signaling server using HTTP(S) for the NetherNet protocol.
 * <p>
 * Follows <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/7330880ab78ef001cad0b9cdfedb3aa3eaa6d4af/NetherNetOnboardingGuide.md">...</a>
 */
public class NetherNetHTTPSignaling implements NetherNetServerSignaling {
    private final InternalLogger log = InternalLoggerFactory.getInstance(getClass());

    private final Random random = new Random();
    private final Map<String, Promise<String>> pendingAnswers = new ConcurrentHashMap<>();

    private final PlayerFilter playerFilter;
    private final MotdProvider motdProvider;

    private static final AsciiString FORWARDED_FOR = AsciiString.cached("X-Forwarded-For");

    private static final int ANSWER_TIMEOUT_SECONDS = 30;

    private final IpRangeSet trustedProxies;
    private final boolean iceOnLocalPort;
    private final Set<String> advertisedAddresses;
    private final TokenTrust tokenTrust;
    private final boolean serveHttp;

    private SslContext sslContext;
    private ServerIdentity serverIdentity;
    private NewConnectionHandler newConnectionHandler;

    private Channel serverChannel;
    private volatile EventLoop eventLoop;

    private NetherNetHTTPSignaling(Builder builder) {
        this.playerFilter = builder.playerFilter;
        this.motdProvider = builder.motdProvider;
        this.sslContext = builder.sslContext;
        this.serverIdentity = builder.identity;
        this.trustedProxies = builder.trustedProxies;
        this.iceOnLocalPort = builder.iceOnLocalPort;
        this.advertisedAddresses = builder.advertisedAddresses;
        this.tokenTrust = builder.tokenTrust;
        this.serveHttp = builder.serveHttp;
    }

    @Override
    public void bind(SocketAddress localAddress, EventLoop eventLoop) throws ConnectException {
        if (!(localAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        this.eventLoop = eventLoop;

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

        // Setup a new server bootstrap for http using the existing event loop and channel
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(eventLoop)
                .channelFactory((ChannelFactory<NioServerSocketChannel>) () -> new NioServerSocketChannel(channel))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // Handle ssl or drop it
                        if (sslContext != null) {
                            p.addLast(sslContext.newHandler(ch.alloc()));
                        } else {
                            p.addLast(new TlsRejectingHandler());
                        }

                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(8 * 1024));
                        p.addLast(new HttpLoggingHandler(log));
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

    private class SignalingHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            if (req.decoderResult().isFailure()) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }

            String path = new QueryStringDecoder(req.uri()).path();
            HttpMethod method = req.method();
            String host = req.headers().get(HttpHeaderNames.HOST);
            InetSocketAddress remoteAddress = clientAddress(ctx, req);

            // Respond to the status check
            if (path.equals("/v1/join")) {
                if (!HttpMethod.GET.equals(method)) {
                    respondEmptyWithStatus(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                    return;
                }

                PongData motd;
                try {
                    motd = motdProvider.getMotd(host, remoteAddress);
                } catch (Exception e) {
                    log.error("MOTD provider failed", e);
                    respondEmptyWithStatus(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                }

                respondWithString(ctx, motd.toJson(), "application/json");
                return;
            }

            // Only continue if the path is /v1/join/<networkId>
            if (!path.startsWith("/v1/join/")) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }

            // Only continue if this is a post request
            if (!HttpMethod.POST.equals(method)) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            String networkId = path.substring("/v1/join/".length());

            // Reject empty, or anything with a further path segment
            if (networkId.isEmpty() || networkId.indexOf('/') >= 0) {
                respondEmptyWithStatus(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }

            String sdpOffer = req.content().toString(StandardCharsets.UTF_8);
            log.trace("Received sdp offer: " + sdpOffer);

            acceptOffer(networkId, sdpOffer, remoteAddress, host).whenComplete((sdpAnswer, failure) -> {
                if (failure == null) {
                    log.trace("Signed SDP answer: " + sdpAnswer);
                    respondWithString(ctx, sdpAnswer, "application/sdp");
                    return;
                }

                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                OfferRejected.Reason reason = cause instanceof OfferRejected rejected
                        ? rejected.reason() : OfferRejected.Reason.UNAVAILABLE;
                respondEmptyWithStatus(ctx, switch (reason) {
                    case INVALID_IDENTITY -> HttpResponseStatus.UNAUTHORIZED;
                    case REJECTED -> HttpResponseStatus.FORBIDDEN;
                    case TIMEOUT -> HttpResponseStatus.GATEWAY_TIMEOUT;
                    case UNAVAILABLE -> HttpResponseStatus.SERVICE_UNAVAILABLE;
                });
            });
        }

        /**
         * The peer address, or the address a trusted reverse proxy forwarded on its behalf.
         */
        private InetSocketAddress clientAddress(ChannelHandlerContext ctx, FullHttpRequest req) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            if (trustedProxies.isEmpty() || remote == null || !trustedProxies.contains(remote)) {
                return remote;
            }

            String forwarded = req.headers().get(FORWARDED_FOR);
            if (forwarded == null || forwarded.isBlank()) {
                return remote;
            }

            // Leftmost entry is the originating client
            String first = forwarded.split(",")[0].trim();
            try {
                return new InetSocketAddress(first, remote.getPort());
            } catch (IllegalArgumentException e) {
                return remote;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Signaling handler error", cause);
            ctx.close();
        }
    }

    private void respondEmptyWithStatus(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void respondWithString(ChannelHandlerContext ctx, String body, String contentType) {
        ByteBuf bodyBuf = Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, bodyBuf);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bodyBuf.readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void setNewConnectionHandler(NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // No-op for Web Signaling.
    }

    @Override
    public ServerIdentity serverIdentity() {
        return this.serverIdentity;
    }

    @Override
    public boolean usesTrickleIce() {
        return false;
    }

    /**
     * Whether ICE may gather on the port signalling is bound to. Set it false when another
     * transport already holds the UDP side of that port, so ICE uses its own.
     */
    @Override
    public boolean allowsIceOnLocalPort() {
        return this.iceOnLocalPort;
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
        JwtClaims claims;
        try {
            claims = IdentityUtils.validateSdp(sdpOffer, tokenTrust);
        } catch (Exception e) {
            log.error("Identity validation failed", e);
            return CompletableFuture.failedFuture(
                    new OfferRejected(OfferRejected.Reason.INVALID_IDENTITY, "identity validation failed", e));
        }

        PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"),
                claims.getClaimValueAsString("xname"), networkId, clientAddress, claims);
        log.debug("Identity is valid: " + player.displayName() + " (" + player.xuid() + ")");

        // Let the user reject the player before we start a connection for them
        boolean allowed;
        try {
            allowed = playerFilter.allow(host, player);
        } catch (Exception e) {
            log.error("Player filter failed for " + player.xuid(), e);
            allowed = false;
        }

        if (!allowed) {
            log.debug("Rejected join from " + player.displayName() + " (" + player.xuid() + ")");
            return CompletableFuture.failedFuture(
                    new OfferRejected(OfferRejected.Reason.REJECTED, "rejected by the player filter", null));
        }

        EventLoop loop = this.eventLoop;
        if (loop == null || newConnectionHandler == null) {
            return CompletableFuture.failedFuture(
                    new OfferRejected(OfferRejected.Reason.UNAVAILABLE, "signalling is not bound", null));
        }

        CompletableFuture<String> result = new CompletableFuture<>();

        // Register the pending answer before firing the callback so a fast answer isn't missed
        Promise<String> answer = loop.newPromise();
        pendingAnswers.put(networkId, answer);

        ScheduledFuture<?> timeout = loop.schedule(
                () -> answer.tryFailure(new TimeoutException("Timed out waiting for SDP answer")),
                ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        answer.addListener((FutureListener<String>) future -> {
            pendingAnswers.remove(networkId, answer);
            timeout.cancel(false);

            if (future.isSuccess()) {
                result.complete(future.getNow());
                return;
            }
            log.error("No SDP answer for " + networkId, future.cause());
            result.completeExceptionally(new OfferRejected(OfferRejected.Reason.TIMEOUT,
                    "no answer was produced", future.cause()));
        });

        // We cant use the network ID as the connection ID as they can be out of the bounds of a long
        newConnectionHandler.onConnect(random.nextLong(), networkId, sdpOffer, clientAddress, player);
        return result;
    }

    /** Why an offer did not produce an answer. */
    public static final class OfferRejected extends Exception {
        public enum Reason {
            /** The offer carried no usable identity assertion. */
            INVALID_IDENTITY,
            /** The player filter turned the peer away. */
            REJECTED,
            /** Nothing produced an answer in time. */
            TIMEOUT,
            /** Signalling is not in a state to answer. */
            UNAVAILABLE
        }

        private final Reason reason;

        OfferRejected(Reason reason, String message, @Nullable Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return this.reason;
        }
    }

    @Override
    public void sendFullSdp(String targetNetworkId, String sdp) {
        log.debug("Sending sdp to " + targetNetworkId);

        Promise<String> answer = pendingAnswers.get(targetNetworkId);
        if (answer != null) {
            answer.trySuccess(SdpUtil.withAdvertisedCandidates(sdp, this.advertisedAddresses));
        } else {
            log.debug("No pending join waiting for " + targetNetworkId);
        }
    }

    @Override
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        // No-op for Web Signaling.
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        // No-op for Web Signaling.
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
         * Called on the event loop, so don't block in here. A thrown exception is treated
         * as a rejection.
         *
         * @param host   The host header from the join request, which may be used to identify the server
         * @param player The validated player attempting to join
         * @return true to accept the player, false to reject them with a 403
         */
        boolean allow(String host, PlayerInfo player);
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
         *
         * @param host          The host header from the join request, which may be used to identify the server
         * @param remoteAddress The address the status request came from
         * @return The MOTD to advertise
         */
        PongData getMotd(String host, InetSocketAddress remoteAddress);
    }

    /**
     * Builder for {@link NetherNetHTTPSignaling}.
     * <p>
     * The server is backed by one keystore for the TLS listener and another for the
     * server identity used to sign SDP answers. Both must be PKCS12 files, and only
     * the identity keystore is required.
     */
    public static class Builder {
        private ServerIdentity identity;
        private SslContext sslContext;
        private IpRangeSet trustedProxies = IpRangeSet.empty();
        private boolean iceOnLocalPort = true;
        private Set<String> advertisedAddresses = Set.of();
        private TokenTrust tokenTrust = TokenTrust.MINECRAFT_AUTH;
        private boolean serveHttp = true;
        private PlayerFilter playerFilter = (host, player) -> true;
        private MotdProvider motdProvider = (host, remoteAddress) -> PongData.DEFAULT;

        /**
         * Sets the identity used to sign SDP answers. Required.
         * <p>
         * Load it with {@link ServerIdentity#fromPkcs12}, {@link ServerIdentity#fromPem} or
         * {@link ServerIdentity#generate}, or build one straight from a keypair.
         *
         * @param identity The identity to sign with
         * @return This builder
         */
        public Builder setIdentity(ServerIdentity identity) {
            this.identity = identity;
            return this;
        }

        /**
         * Sets the identity from an unprotected PKCS12 keystore.
         *
         * @param identityKeystore PKCS12 keystore holding the EC P-384 identity key
         * @return This builder
         */
        public Builder setIdentityKeystore(File identityKeystore) {
            return setIdentityKeystore(identityKeystore, "");
        }

        /**
         * Sets the identity from a PKCS12 keystore. The key must be EC P-384, and its certificate
         * CN becomes the identity domain, so set it to something recognisable. Generate one with:
         * <pre>{@code
         * keytool -genkeypair -alias identity -keyalg EC -groupname secp384r1 \
         *         -storetype PKCS12 -keystore identity.p12 -storepass changeit \
         *         -dname "CN=Your Server" -validity 3650
         * }</pre>
         *
         * @param identityKeystore PKCS12 keystore holding the EC P-384 identity key
         * @param identityPassword Password for {@code identityKeystore}, or "" if unprotected
         * @return This builder
         * @throws IllegalArgumentException If the keystore cannot be read
         */
        public Builder setIdentityKeystore(File identityKeystore, String identityPassword) {
            try {
                return setIdentity(ServerIdentity.fromPkcs12(identityKeystore, identityPassword));
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read the identity keystore " + identityKeystore, e);
            }
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
                return setIdentity(ServerIdentity.fromPem(identityPem, domain));
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
         * Sets the reverse proxies whose {@code X-Forwarded-For} header is honoured, as single
         * addresses or CIDR ranges. Requests from anywhere else keep their peer address.
         *
         * @param trustedProxies Addresses or CIDR ranges, such as {@code 10.0.0.0/8}
         * @return This builder
         */
        public Builder setTrustedProxies(Collection<String> trustedProxies) {
            this.trustedProxies = IpRangeSet.parse(trustedProxies);
            return this;
        }

        /**
         * Sets whether ICE may gather on the port signalling binds to. Defaults to true. Set it
         * false when another transport, such as RakNet, already holds the UDP side of that port.
         *
         * @param iceOnLocalPort Whether ICE may use the signalling port
         * @return This builder
         */
        public Builder setIceOnLocalPort(boolean iceOnLocalPort) {
            this.iceOnLocalPort = iceOnLocalPort;
            return this;
        }

        /**
         * Sets the only local addresses that may be announced as ICE candidates. Empty, the
         * default, announces every address ICE gathers.
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
         * Sets whether to serve the HTTP join endpoint. Defaults to true. With it off nothing is
         * listened on and offers have to be handed in through
         * {@link NetherNetHTTPSignaling#acceptOffer}, which is how an endpoint outside this process
         * drives signalling.
         *
         * @param serveHttp Whether to bind the join endpoint
         * @return This builder
         */
        public Builder setServeHttp(boolean serveHttp) {
            this.serveHttp = serveHttp;
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
         * Sets the provider called for each status request.
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
         * Builds the signalling instance.
         *
         * @return A new signalling instance
         * @throws IllegalStateException If no identity was set
         */
        public NetherNetHTTPSignaling build() {
            if (identity == null) {
                throw new IllegalStateException("An identity is required");
            }

            return new NetherNetHTTPSignaling(this);
        }
    }
}
