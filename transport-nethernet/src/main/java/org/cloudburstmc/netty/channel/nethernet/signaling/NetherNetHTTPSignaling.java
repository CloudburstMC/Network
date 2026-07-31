package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.util.http.HttpLoggingHandler;
import org.cloudburstmc.netty.util.http.TlsRejectingHandler;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
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

    private SslContext sslContext;
    private ServerIdentity serverIdentity;
    private NewConnectionHandler newConnectionHandler;

    private Channel serverChannel;

    public NetherNetHTTPSignaling(File identityKeystore, String identityPassword) {
        this(identityKeystore, identityPassword, null, "");
    }

    public NetherNetHTTPSignaling(File identityKeystore, File httpsKeystore) {
        this(identityKeystore, "", httpsKeystore, "");
    }

    /**
     * Creates an HTTP(S) signalling server, backed by one keystore for the TLS
     * listener and another for the server identity used to sign SDP answers.
     * <p>
     * Both must be PKCS12 files. The identity key must be EC P-384, and its certificate
     * CN is surfaced as the identity domain, so set it to something recognisable.
     * Generate one with:
     * <pre>{@code
     * keytool -genkeypair -alias identity -keyalg EC -groupname secp384r1 \
     *         -storetype PKCS12 -keystore identity.p12 -storepass changeit \
     *         -dname "CN=Your Server" -validity 3650
     * }</pre>
     *
     * @param identityKeystore PKCS12 keystore holding the EC P-384 identity key
     * @param identityPassword Password for {@code identityKeystore}, or "" if unprotected
     * @param httpsKeystore    PKCS12 keystore holding the TLS certificate and key, or null for plaintext
     * @param httpsPassword    Password for {@code httpsKeystore}, or "" if unprotected
     */
    public NetherNetHTTPSignaling(File identityKeystore, String identityPassword, File httpsKeystore, String httpsPassword) {
        if (httpsKeystore != null) {
            try {
                char[] passwordChars = httpsPassword.toCharArray();

                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(httpsKeystore)) {
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
            this.serverIdentity = ServerIdentity.fromKeystore(identityKeystore, identityPassword);
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
        ServerSocketChannel javaChannel;
        try {
            javaChannel = ServerSocketChannel.open();
            javaChannel.configureBlocking(false);
            javaChannel.bind(localAddress, 128);
        } catch (IOException e) {
            throw new ConnectException("Failed to bind HTTP signaling to " + localAddress + ": " + e.getMessage());
        }

        // Setup a new server bootstrap for http using the existing event loop and channel
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(eventLoop)
            .channelFactory((ChannelFactory<NioServerSocketChannel>) () -> new NioServerSocketChannel(javaChannel))
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

            // Respond to the status check
            if (path.equals("/v1/join")) {
                respondEmptyWithStatus(ctx, HttpMethod.GET.equals(method) ? HttpResponseStatus.OK : HttpResponseStatus.METHOD_NOT_ALLOWED);
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

            try {
                JwtClaims claims = IdentityUtils.validateSdp(sdpOffer);

                // TODO Some form of callback if people want to do filtering of xuid etc at this point?
                log.debug("Identity is valid: " + claims.getClaimValueAsString("xname") + " (" + claims.getClaimValueAsString("xid") + ")");
            } catch (Exception e) {
                log.error("Identity validation failed", e);
                respondEmptyWithStatus(ctx, HttpResponseStatus.UNAUTHORIZED);
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

                ByteBuf body = Unpooled.wrappedBuffer(signedAnswer.getBytes(StandardCharsets.UTF_8));
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/sdp");
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
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
        log.debug("Sending sdp to " + targetNetworkId + ": " + sdp);

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
}
