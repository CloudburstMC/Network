package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractNetherNetXboxSignaling extends SimpleChannelInboundHandler<TextWebSocketFrame>
        implements NetherNetClientSignaling, NetherNetServerSignaling {

    protected final InternalLogger log = InternalLoggerFactory.getInstance(getClass());

    protected final String xboxToken;
    protected final String localNetworkId;
    protected final URI uri;
    protected final EventLoopGroup eventLoopGroup;

    protected Channel channel;
    protected CompletableFuture<List<IceServerInfo>> connectFuture;
    protected volatile List<IceServerInfo> iceServers = new ArrayList<>();

    protected final Map<Long, SignalHandler> handlers = new ConcurrentHashMap<>();
    protected NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    protected volatile NetherNetClientSignaling.NotFoundHandler notFoundHandler;

    protected AbstractNetherNetXboxSignaling(String localNetworkId, String xboxToken, URI uri) {
        this.localNetworkId = localNetworkId;
        this.xboxToken = xboxToken;
        this.uri = uri;
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public synchronized CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        return connectInternal();
    }

    @Override
    public void bind(SocketAddress localAddress, EventLoop eventLoop) throws ConnectException {
        try {
            connectInternal().join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            close();
            if (cause instanceof ConnectException) {
                throw (ConnectException) cause;
            }
            ConnectException ce = new ConnectException("Failed to connect to Xbox Signaling: " + cause.getMessage());
            ce.initCause(cause);
            throw ce;
        }
    }

    protected synchronized CompletableFuture<List<IceServerInfo>> connectInternal() {
        if (connectFuture != null) {
            return connectFuture;
        }

        connectFuture = new CompletableFuture<>();
        connectFuture.thenAccept(servers -> this.iceServers = servers);

        try {
            SslContext sslCtx = SslContextBuilder.forClient().build();
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, false,
                    new DefaultHttpHeaders()
                            .add("Authorization", xboxToken)
                            .add("User-Agent", NetherNetConstants.SIGNALING_USER_AGENT)
                            .add("session-id", UUID.randomUUID().toString())
                            .add("request-id", UUID.randomUUID().toString())
            );

            Bootstrap b = new Bootstrap();
            b.group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(sslCtx.newHandler(ch.alloc(), uri.getHost(), 443));
                            p.addLast(new HttpClientCodec(), new HttpObjectAggregator(8192));
                            p.addLast("ws-handshake", new WebSocketClientProtocolHandler(handshaker));
                            p.addLast("ws-aggregator",
                                    new WebSocketFrameAggregator(16 * 1024)); // Allow 16KB aggregations
                            p.addLast("handler", AbstractNetherNetXboxSignaling.this);
                        }
                    });

            this.channel = b.connect(uri.getHost(), 443).sync().channel();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (connectFuture != null) {
                connectFuture.completeExceptionally(cause);
            }
        }
        return connectFuture;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.debug("{} WebSocket Connected", getClass().getSimpleName());
            onConnected(ctx);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * Called when the WebSocket handshake is complete.
     */
    protected abstract void onConnected(ChannelHandlerContext ctx);

    @Override
    public List<IceServerInfo> getIceServers() {
        return this.iceServers;
    }

    @Override
    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    @Override
    public void setNotFoundHandler(NotFoundHandler handler) {
        this.notFoundHandler = handler;
    }

    @Override
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        this.handlers.put(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        this.handlers.remove(connectionId);
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // No-op for Xbox Signaling.
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (connectFuture != null && !connectFuture.isDone()) {
            connectFuture.completeExceptionally(cause);
        }
        log.error("Signaling Exception: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        synchronized (this) {
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(new ClosedChannelException());
            }
            connectFuture = null;
            this.channel = null;
        }
        super.channelInactive(ctx);
    }

    @Override
    public boolean isActive() {
        Channel ch = this.channel;
        return ch != null && ch.isActive();
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
        eventLoopGroup.shutdownGracefully();
    }

    protected void dispatchSignalToPipeline(String sender, String rawMsg) {
        try {
            // Signal Format: <Type> <ConnectionID> <Data>
            String[] parts = rawMsg.split(" ", 3);
            if (parts.length < 2) {
                return;
            }

            long connectionId = Long.parseUnsignedLong(parts[1]);

            SignalHandler handler = handlers.get(connectionId);
            if (handler != null) {
                handler.onSignal(rawMsg);
                return;
            }

            if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(parts[0]) && newConnectionHandler != null) {
                String payload = parts.length > 2 ? parts[2] : "";
                newConnectionHandler.onConnect(connectionId, sender, payload);
            } else {
                log.debug("No handler found for connection ID: {} (Type: {})", connectionId, parts[0]);
            }
        } catch (Exception e) {
            log.error("Failed to dispatch signal: {}", rawMsg, e);
        }
    }

    protected List<IceServerInfo> parseTurnServers(JsonObject json) {
        List<IceServerInfo> result = new ArrayList<>();
        try {
            JsonArray servers = null;
            if (json.has("TurnAuthServers")) {
                servers = json.getAsJsonArray("TurnAuthServers");
            } else if (json.has("turnAuthServers")) {
                servers = json.getAsJsonArray("turnAuthServers");
            }

            if (servers != null) {
                for (JsonElement el : servers) {
                    JsonObject server = el.getAsJsonObject();
                    List<String> urls = new ArrayList<>();

                    JsonArray urlsArray = null;
                    if (server.has("Urls")) {
                        urlsArray = server.getAsJsonArray("Urls");
                    } else if (server.has("urls")) {
                        urlsArray = server.getAsJsonArray("urls");
                    }

                    if (urlsArray != null) {
                        urlsArray.forEach(u -> urls.add(u.getAsString()));

                        IceServerInfo.Builder info = new IceServerInfo.Builder().setUrls(urls);

                        if (server.has("Username")) {
                            info.setUsername(server.get("Username").getAsString());
                        } else if (server.has("username")) {
                            info.setUsername(server.get("username").getAsString());
                        }

                        if (server.has("Password")) {
                            info.setPassword(server.get("Password").getAsString());
                        } else if (server.has("password")) {
                            info.setPassword(server.get("password").getAsString());
                        } else if (server.has("Credential")) {
                            info.setPassword(server.get("Credential").getAsString());
                        } else if (server.has("credential")) {
                            info.setPassword(server.get("credential").getAsString());
                        }

                        result.add(info.build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse TURN servers", e);
        }
        log.debug("Successfully parsed {} ICE servers.", result.size());
        return result;
    }
}
