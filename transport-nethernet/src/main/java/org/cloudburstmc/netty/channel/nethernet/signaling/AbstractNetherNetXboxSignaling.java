package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public abstract class AbstractNetherNetXboxSignaling extends SimpleChannelInboundHandler<TextWebSocketFrame>
        implements NetherNetClientSignaling, NetherNetServerSignaling {

    /** Time to wait for the connect plus credential exchange before giving up. */
    private static final long CONNECT_TIMEOUT_SECONDS = 20;
    /** Interval for WebSocket protocol-level pings. The RFC obliges the server to
     * answer with a pong, so these guarantee inbound traffic on a healthy socket
     * and make {@link #isChannelAlive(long)} reliable even on idle servers. */
    private static final long WS_PING_INTERVAL_SECONDS = 15;

    /** Recurring tasks tied to one WebSocket channel's lifetime, cancelled in
     * channelInactive. Without this, every reconnect would leak the previous
     * channel's ping loops on the shared event loop. */
    private static final AttributeKey<CopyOnWriteArrayList<ScheduledFuture<?>>> CHANNEL_TASKS =
            AttributeKey.valueOf("nethernet-signaling-channel-tasks");

    protected final InternalLogger log = InternalLoggerFactory.getInstance(getClass());

    protected volatile String xboxToken;
    protected final String localNetworkId;
    protected final URI uri;
    protected final EventLoopGroup eventLoopGroup;

    protected volatile Channel channel;
    protected CompletableFuture<List<IceServerInfo>> connectFuture;
    protected volatile List<IceServerInfo> iceServers = new ArrayList<>();
    protected volatile long lastMessageReceivedAt;
    private volatile boolean closed;

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
    public void bind(SocketAddress localAddress) throws ConnectException {
        joinConnect(connectInternal());
    }

    /**
     * Reconnects the WebSocket in place using a fresh authorization token. The
     * signaling instance, its registered handlers, and everything built on top
     * of it (server channel, peer connection factory, live peer connections)
     * are untouched; only the socket to the signaling service is replaced.
     *
     * Must not be called from this signaling instance's own event loop; it
     * blocks until the new socket has completed its credential exchange.
     *
     * @param freshToken the authorization token to connect with
     * @throws ConnectException if the reconnect fails; the instance remains
     *         reconnectable and the call may be retried
     */
    public void reconnect(String freshToken) throws ConnectException {
        CompletableFuture<List<IceServerInfo>> future;
        synchronized (this) {
            if (closed) {
                throw new ConnectException("Signaling has been closed");
            }
            this.xboxToken = freshToken;
            Channel old = this.channel;
            this.channel = null;
            CompletableFuture<List<IceServerInfo>> pending = this.connectFuture;
            this.connectFuture = null;
            if (pending != null && !pending.isDone()) {
                pending.completeExceptionally(new ClosedChannelException());
            }
            if (old != null) {
                old.close();
            }
            future = connectInternal();
        }
        joinConnect(future);
    }

    protected synchronized CompletableFuture<List<IceServerInfo>> connectInternal() {
        if (closed) {
            CompletableFuture<List<IceServerInfo>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ClosedChannelException());
            return failed;
        }
        if (connectFuture != null) return connectFuture;

        CompletableFuture<List<IceServerInfo>> future = new CompletableFuture<>();
        connectFuture = future;
        future.thenAccept(servers -> this.iceServers = servers);

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
                     // dropPongFrames=false: pongs must reach channelRead so they
                     // refresh lastMessageReceivedAt for liveness detection.
                     // Close frames are handled in channelRead, not by the protocol
                     // handler, so the reason the service closed us gets logged.
                     p.addLast("ws-handshake", new WebSocketClientProtocolHandler(handshaker, false, false));
                     p.addLast("ws-aggregator", new WebSocketFrameAggregator(128 * 1024));
                     p.addLast("handler", AbstractNetherNetXboxSignaling.this);
                 }
             });

            // Asynchronous on purpose: connectInternal may be invoked while
            // holding this signaling's lock or (via channelInactive paths) on
            // the event loop itself, where a sync() would deadlock. Callers
            // that need to block use joinConnect on the returned future.
            ChannelFuture connect = b.connect(uri.getHost(), 443);
            this.channel = connect.channel();
            connect.addListener(f -> {
                if (!f.isSuccess() && !future.isDone()) {
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (!future.isDone()) future.completeExceptionally(cause);
        }
        return future;
    }

    /**
     * Blocks until the given connect attempt has completed its credential
     * exchange, translating failures (including a stalled handshake) into a
     * ConnectException after cleaning up the half-open channel.
     */
    protected void joinConnect(CompletableFuture<List<IceServerInfo>> future) throws ConnectException {
        try {
            future.orTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            abortConnect();
            if (cause instanceof ConnectException) throw (ConnectException) cause;
            ConnectException ce = new ConnectException("Failed to connect to Xbox Signaling: " + cause.getMessage());
            ce.initCause(cause);
            throw ce;
        }
    }

    private synchronized void abortConnect() {
        Channel c = this.channel;
        this.channel = null;
        CompletableFuture<List<IceServerInfo>> pending = this.connectFuture;
        this.connectFuture = null;
        if (pending != null && !pending.isDone()) {
            pending.completeExceptionally(new ClosedChannelException());
        }
        if (c != null) {
            c.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.debug("{} WebSocket Connected", getClass().getSimpleName());
            lastMessageReceivedAt = System.currentTimeMillis();
            scheduleRecurring(ctx, "ws-ping", () -> ctx.writeAndFlush(new PingWebSocketFrame()),
                    WS_PING_INTERVAL_SECONDS, WS_PING_INTERVAL_SECONDS);
            onConnected(ctx);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // Track liveness: every inbound frame counts, including pong frames
        // answering our protocol-level pings. Used by isChannelAlive(long)
        // to detect silent half-closed TCP where channel.isActive() lies.
        lastMessageReceivedAt = System.currentTimeMillis();
        if (msg instanceof CloseWebSocketFrame) {
            CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
            try {
                log.warn("Signaling socket closed by the service: {} {}", close.statusCode(), close.reasonText());
            } finally {
                close.release();
            }
            ctx.close();
            return;
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Schedules a recurring task bound to the given channel's lifetime. The
     * task is skipped while the channel is inactive, survives its own
     * exceptions (scheduleAtFixedRate would otherwise cancel silently on the
     * first throw), and is cancelled when the channel goes inactive.
     */
    protected void scheduleRecurring(ChannelHandlerContext ctx, String name, Runnable task,
                                     long initialDelaySeconds, long periodSeconds) {
        ScheduledFuture<?> future = ctx.executor().scheduleAtFixedRate(() -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                task.run();
            } catch (Throwable t) {
                log.warn("{} task threw; loop continues: {}", name, t.getMessage());
            }
        }, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);

        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = ctx.channel().attr(CHANNEL_TASKS).get();
        if (tasks == null) {
            ctx.channel().attr(CHANNEL_TASKS).setIfAbsent(new CopyOnWriteArrayList<>());
            tasks = ctx.channel().attr(CHANNEL_TASKS).get();
        }
        tasks.add(future);
    }

    /**
     * @return true if the signaling WebSocket channel is active. Does NOT
     *         detect silent half-closed TCP (Netty can report active on a
     *         dead socket). For stricter checking use {@link #isChannelAlive(long)}
     *         or compare {@link #getMillisSinceLastMessage()} against your
     *         own threshold.
     */
    public boolean isChannelAlive() {
        Channel c = this.channel;
        return c != null && c.isActive();
    }

    /**
     * @param maxSilenceMillis max tolerated time since last received frame.
     *                         The recurring protocol-level ping guarantees a
     *                         pong at least every {@value #WS_PING_INTERVAL_SECONDS}
     *                         seconds on a healthy socket, so thresholds of
     *                         two to three times that are safe even when no
     *                         signaling traffic is flowing.
     * @return true if the channel is active and has received a frame within
     *         the given window.
     */
    public boolean isChannelAlive(long maxSilenceMillis) {
        if (!isChannelAlive()) return false;
        long silence = getMillisSinceLastMessage();
        return silence >= 0 && silence <= maxSilenceMillis;
    }

    /**
     * @return milliseconds since the last received frame, or -1 if no frame
     *         has been received yet.
     */
    public long getMillisSinceLastMessage() {
        if (lastMessageReceivedAt == 0) return -1;
        return System.currentTimeMillis() - lastMessageReceivedAt;
    }

    /**
     * Called when the WebSocket handshake is complete.
     */
    protected abstract void onConnected(ChannelHandlerContext ctx);

    /**
     * Called when a WebSocket channel of this signaling instance goes
     * inactive, before the base class finishes its own cleanup. Subclasses
     * use this to fail state tied to the dead socket (e.g. pending requests).
     */
    protected void onChannelInactive(ChannelHandlerContext ctx) {
    }

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
        synchronized (this) {
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(cause);
            }
        }
        log.error("Signaling Exception: {}", cause.getMessage(), cause);
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = ctx.channel().attr(CHANNEL_TASKS).get();
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) {
                task.cancel(false);
            }
            tasks.clear();
        }
        synchronized (this) {
            // Only clear state if the channel going inactive is still the
            // current one. During a reconnect the old channel's inactive event
            // arrives after the replacement is already installed and must not
            // tear down the new connection's state.
            if (ctx.channel() == this.channel) {
                if (connectFuture != null && !connectFuture.isDone()) {
                    connectFuture.completeExceptionally(new ClosedChannelException());
                }
                connectFuture = null;
                this.channel = null;
            }
        }
        onChannelInactive(ctx);
        super.channelInactive(ctx);
    }

    @Override
    public void close() {
        Channel c;
        synchronized (this) {
            closed = true;
            c = this.channel;
            this.channel = null;
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(new ClosedChannelException());
            }
            connectFuture = null;
        }
        if (c != null) {
            c.close();
        }
        eventLoopGroup.shutdownGracefully();
    }

    protected void dispatchSignalToPipeline(String sender, String rawMsg) {
        try {
            // Signal Format: <Type> <ConnectionID> <Data>
            String[] parts = rawMsg.split(" ", 3);
            if (parts.length < 2) return;

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
            if (json.has("TurnAuthServers")) servers = json.getAsJsonArray("TurnAuthServers");
            else if (json.has("turnAuthServers")) servers = json.getAsJsonArray("turnAuthServers");

            if (servers != null) {
                for (JsonElement el : servers) {
                    JsonObject server = el.getAsJsonObject();
                    List<String> urls = new ArrayList<>();

                    JsonArray urlsArray = null;
                    if (server.has("Urls")) urlsArray = server.getAsJsonArray("Urls");
                    else if (server.has("urls")) urlsArray = server.getAsJsonArray("urls");

                    if (urlsArray != null) {
                        urlsArray.forEach(u -> urls.add(u.getAsString()));

                        IceServerInfo.Builder info = new IceServerInfo.Builder().setUrls(urls);

                        if (server.has("Username")) info.setUsername(server.get("Username").getAsString());
                        else if (server.has("username")) info.setUsername(server.get("username").getAsString());

                        if (server.has("Password")) info.setPassword(server.get("Password").getAsString());
                        else if (server.has("password")) info.setPassword(server.get("password").getAsString());
                        else if (server.has("Credential")) info.setPassword(server.get("Credential").getAsString());
                        else if (server.has("credential")) info.setPassword(server.get("credential").getAsString());

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

    /**
     * Stores freshly received TURN/ICE servers and completes the pending
     * connect future if this was the credential exchange of a new socket.
     * Called by subclasses whenever the service supplies credentials, both
     * during connect and on later refreshes, so peer connections created at
     * any point get unexpired TURN credentials.
     */
    protected void updateIceServers(List<IceServerInfo> servers) {
        this.iceServers = servers;
        synchronized (this) {
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.complete(servers);
            }
        }
    }
}
