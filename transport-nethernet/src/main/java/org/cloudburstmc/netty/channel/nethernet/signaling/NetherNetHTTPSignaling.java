package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.util.http.HttpLoggingHandler;
import org.cloudburstmc.netty.util.http.TlsRejectingHandler;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
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

    private SslContext sslContext;
    private ServerIdentity serverIdentity;
    private NewConnectionHandler newConnectionHandler;

    private Channel serverChannel;

    private NetherNetHTTPSignaling(Builder builder) {
        this.playerFilter = builder.playerFilter;
        this.motdProvider = builder.motdProvider;

        if (builder.httpsKeystore != null) {
            try {
                char[] passwordChars = builder.httpsPassword.toCharArray();

                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(builder.httpsKeystore)) {
                    ks.load(fis, passwordChars);
                }

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, passwordChars);

                this.sslContext = SslContextBuilder.forServer(kmf).build();
            } catch (Exception ex) {
                log.error("Error loading https keystore: " + ex.getMessage(), ex);
            }
        }

        try {
            this.serverIdentity = ServerIdentity.fromKeystore(builder.identityKeystore, builder.identityPassword);
        } catch (Exception ex) {
            log.error("Error loading identity keystore: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void bind(SocketAddress localAddress, EventLoop eventLoop) throws ConnectException {
        if (!(localAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
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
            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

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

            JwtClaims claims;
            try {
                claims = IdentityUtils.validateSdp(sdpOffer);
            } catch (Exception e) {
                log.error("Identity validation failed", e);
                respondEmptyWithStatus(ctx, HttpResponseStatus.UNAUTHORIZED);
                return;
            }

            PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"), claims.getClaimValueAsString("xname"), networkId, remoteAddress, claims);
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
                respondEmptyWithStatus(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }

            // Register the pending answer before firing the callback so a fast answer isn't missed.
            Promise<String> answer = ctx.executor().newPromise();
            pendingAnswers.put(networkId, answer);

            // Cancel the answer promise if we have waited 30s
            ScheduledFuture<?> timeout = ctx.executor().schedule(() -> {
                answer.tryFailure(new TimeoutException("Timed out waiting for SDP answer"));
            }, 30, TimeUnit.SECONDS);

            answer.addListener((FutureListener<String>) future -> {
                pendingAnswers.remove(networkId, answer);
                timeout.cancel(false);

                if (!future.isSuccess()) {
                    log.error("No SDP answer for " + networkId, future.cause());
                    respondEmptyWithStatus(ctx, HttpResponseStatus.GATEWAY_TIMEOUT);
                    return;
                }

                String sdpAnswer = future.getNow();
                log.trace("Received SDP answer: " + sdpAnswer);

                // Sign the answer with the server identity
                String signedAnswer;
                try {
                    signedAnswer = serverIdentity.augmentAnswer(sdpAnswer);
                    log.trace("Signed SDP answer: " + signedAnswer);
                } catch (Exception e) {
                    log.error("Failed to attach server identity to answer", e);
                    respondEmptyWithStatus(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    return;
                }

                log.debug("Sending SDP answer");

                respondWithString(ctx, signedAnswer, "application/sdp");
            });

            // We cant use the network ID as the connection ID as they can be out of the bounds of a long
            newConnectionHandler.onConnect(random.nextLong(), networkId, sdpOffer);
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
    public void sendFullSdp(String targetNetworkId, String sdp) {
        log.debug("Sending sdp to " + targetNetworkId);

        Promise<String> answer = pendingAnswers.get(targetNetworkId);
        if (answer != null) {
            answer.trySuccess(sdp);
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
        if (serverChannel != null) serverChannel.close();
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
         * @param host The host header from the join request, which may be used to identify the server
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
         * @param host The host header from the join request, which may be used to identify the server
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
        private File identityKeystore;
        private String identityPassword = "";
        private File httpsKeystore;
        private String httpsPassword = "";
        private PlayerFilter playerFilter = (host, player) -> true;
        private MotdProvider motdProvider = (host, remoteAddress) -> PongData.DEFAULT;

        /**
         * Sets the unprotected keystore holding the identity key. Required.
         *
         * @param identityKeystore PKCS12 keystore holding the EC P-384 identity key
         * @return This builder
         */
        public Builder setIdentityKeystore(File identityKeystore) {
            return setIdentityKeystore(identityKeystore, "");
        }

        /**
         * Sets the keystore holding the identity key used to sign SDP answers. Required.
         * <p>
         * The key must be EC P-384, and its certificate CN is surfaced as the identity
         * domain, so set it to something recognisable.
         * Generate one with:
         * <pre>{@code
         * keytool -genkeypair -alias identity -keyalg EC -groupname secp384r1 \
         *         -storetype PKCS12 -keystore identity.p12 -storepass changeit \
         *         -dname "CN=Your Server" -validity 3650
         * }</pre>
         *
         * @param identityKeystore PKCS12 keystore holding the EC P-384 identity key
         * @param identityPassword Password for {@code identityKeystore}, or "" if unprotected
         * @return This builder
         */
        public Builder setIdentityKeystore(File identityKeystore, String identityPassword) {
            this.identityKeystore = identityKeystore;
            this.identityPassword = identityPassword;
            return this;
        }

        /**
         * Sets the unprotected keystore holding the TLS certificate and key.
         *
         * @param httpsKeystore PKCS12 keystore holding the TLS certificate and key
         * @return This builder
         */
        public Builder setHttpsKeystore(File httpsKeystore) {
            return setHttpsKeystore(httpsKeystore, "");
        }

        /**
         * Sets the keystore holding the TLS certificate and key.
         * If unset the server listens in plaintext.
         *
         * @param httpsKeystore PKCS12 keystore holding the TLS certificate and key
         * @param httpsPassword Password for {@code httpsKeystore}, or "" if unprotected
         * @return This builder
         */
        public Builder setHttpsKeystore(File httpsKeystore, String httpsPassword) {
            this.httpsKeystore = httpsKeystore;
            this.httpsPassword = httpsPassword;
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
         * @throws IllegalStateException If no identity keystore was set
         */
        public NetherNetHTTPSignaling build() {
            if (identityKeystore == null) {
                throw new IllegalStateException("An identity keystore is required");
            }

            return new NetherNetHTTPSignaling(this);
        }
    }
}
