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
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLException;

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

    /** How long a connect, including its TURN credentials, or a request may wait for the service. */
    protected static final long CONNECT_TIMEOUT_SECONDS = 20;

    /**
     * The recurring tasks of one socket, cancelled when it goes inactive, so a reconnect does not
     * leave the previous socket's loops running on the shared event loop.
     */
    private static final AttributeKey<CopyOnWriteArrayList<ScheduledFuture<?>>> CHANNEL_TASKS =
            AttributeKey.valueOf("nethernet-signaling-channel-tasks");

    /**
     * The interval of the WebSocket protocol ping. The service must answer it with a pong (RFC 6455
     * section 5.5.2), so a live socket receives a frame at least this often even when idle.
     */
    private static final long WS_PING_INTERVAL_SECONDS = 15;

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

    protected final Map<String, SignalHandler> handlers = new ConcurrentHashMap<>();
    protected NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    protected volatile NetherNetClientSignaling.FailureHandler failureHandler;

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
            joinConnect(connectInternal());
        } catch (ConnectException e) {
            close();
            throw e;
        }
    }

    /**
     * Replaces the socket to the signaling service with one that connects with a fresh token. Only
     * the socket changes: the handlers, and the server channel and peer connections built on this
     * signaling, stay as they are. A failed attempt leaves the signaling open, so it can be retried.
     * <p>
     * Blocks until the new socket has its TURN credentials, so it must not be called from this
     * signaling's event loop.
     *
     * @param freshToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     * @throws ConnectException If the signaling is closed or the new socket fails to connect.
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

    /**
     * TLS for the signaling websocket, which carries the Xbox token in its upgrade request.
     * <p>
     * Netty leaves {@code endpointIdentificationAlgorithm} unset, which validates the chain but not
     * the name on it, so without this any publicly trusted certificate would be accepted for the
     * signaling host.
     */
    static SslContext signalingSslContext() throws SSLException {
        return SslContextBuilder.forClient()
                .endpointIdentificationAlgorithm("HTTPS")
                .build();
    }

    protected synchronized CompletableFuture<List<IceServerInfo>> connectInternal() {
        if (closed) {
            CompletableFuture<List<IceServerInfo>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new ClosedChannelException());
            return failed;
        }
        if (connectFuture != null) {
            return connectFuture;
        }

        CompletableFuture<List<IceServerInfo>> future = new CompletableFuture<>();
        connectFuture = future;

        try {
            SslContext sslCtx = signalingSslContext();
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
                            // Pongs are passed on, so they count as received frames in channelRead
                            p.addLast("ws-handshake", new WebSocketClientProtocolHandler(handshaker, true, false));
                            p.addLast("ws-aggregator",
                                    new WebSocketFrameAggregator(16 * 1024)); // Allow 16KB aggregations
                            p.addLast("handler", AbstractNetherNetXboxSignaling.this);
                        }
                    });

            // Not waited on while holding the lock, which signals sent meanwhile would wait for.
            // Callers that block wait through joinConnect.
            ChannelFuture connect = b.connect(uri.getHost(), 443);
            this.channel = connect.channel();
            connect.addListener(f -> {
                if (!f.isSuccess()) {
                    future.completeExceptionally(f.cause());
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e.getCause() != null ? e.getCause() : e);
        }
        return future;
    }

    /**
     * Waits for a connect attempt to get its TURN credentials, and closes its socket if it fails or
     * takes longer than {@link #CONNECT_TIMEOUT_SECONDS}.
     */
    protected void joinConnect(CompletableFuture<List<IceServerInfo>> future) throws ConnectException {
        try {
            future.orTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            abortConnect(future);
            if (cause instanceof ConnectException) {
                throw (ConnectException) cause;
            }
            ConnectException ce = new ConnectException("Failed to connect to Xbox Signaling: " + cause.getMessage());
            ce.initCause(cause);
            throw ce;
        }
    }

    /** Closes the socket of a failed attempt, unless another attempt has replaced it since. */
    private synchronized void abortConnect(CompletableFuture<List<IceServerInfo>> attempt) {
        if (connectFuture != attempt) {
            return;
        }
        Channel c = this.channel;
        this.channel = null;
        this.connectFuture = null;
        if (!attempt.isDone()) {
            attempt.completeExceptionally(new ClosedChannelException());
        }
        if (c != null) {
            c.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            synchronized (this) {
                if (!isCurrentChannel(ctx.channel())) {
                    return;
                }
                log.debug("{} WebSocket Connected", getClass().getSimpleName());
                lastMessageReceivedAt = System.currentTimeMillis();
                scheduleRecurring(ctx, "ws-ping", () -> ctx.writeAndFlush(new PingWebSocketFrame()),
                        WS_PING_INTERVAL_SECONDS, WS_PING_INTERVAL_SECONDS);
                onConnected(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // A socket that was replaced can still deliver frames, which must not touch the new one's
        // state. The lock is not held past the check: handlers start peer connections from here.
        synchronized (this) {
            if (!isCurrentChannel(ctx.channel())) {
                ReferenceCountUtil.release(msg);
                return;
            }
            // Pongs included, so a socket without signals still proves it is alive
            lastMessageReceivedAt = System.currentTimeMillis();
        }
        super.channelRead(ctx, msg);
    }

    protected final synchronized boolean isCurrentChannel(@Nullable Channel source) {
        return !closed && source != null && source == channel;
    }

    /**
     * Schedules a task that repeats for as long as the given socket is the current one. It is
     * cancelled when the socket goes inactive, and an exception it throws is logged instead of
     * silently cancelling it, which is what {@code scheduleAtFixedRate} does.
     */
    protected void scheduleRecurring(ChannelHandlerContext ctx, String name, Runnable task,
                                     long initialDelaySeconds, long periodSeconds) {
        ScheduledFuture<?> future = ctx.executor().scheduleAtFixedRate(() -> {
            try {
                synchronized (this) {
                    if (!isCurrentChannel(ctx.channel()) || !ctx.channel().isActive()) {
                        return;
                    }
                    task.run();
                }
            } catch (Throwable t) {
                log.warn("Signaling task {} failed: {}", name, t.getMessage());
            }
        }, initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);

        ctx.channel().attr(CHANNEL_TASKS).setIfAbsent(new CopyOnWriteArrayList<>());
        ctx.channel().attr(CHANNEL_TASKS).get().add(future);
    }

    /**
     * Called when the WebSocket handshake is complete.
     */
    protected abstract void onConnected(ChannelHandlerContext ctx);

    /**
     * Called when one of this signaling's sockets goes inactive, the current one or one that was
     * replaced, so a subclass can fail what was waiting on that socket.
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
    public void setFailureHandler(FailureHandler handler) {
        this.failureHandler = handler;
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        this.handlers.put(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        this.handlers.remove(connectionId);
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        // Nothing to do for Xbox signaling
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        boolean current;
        synchronized (this) {
            current = isCurrentChannel(ctx.channel());
            if (current && connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(cause);
            }
        }
        if (current) {
            log.error("Signaling Exception: {}", cause.getMessage(), cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = ctx.channel().attr(CHANNEL_TASKS).getAndSet(null);
        if (tasks != null) {
            for (ScheduledFuture<?> task : tasks) {
                task.cancel(false);
            }
        }
        synchronized (this) {
            // A replaced socket goes inactive after its replacement is installed, and must leave
            // the replacement's state alone
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

    /**
     * Whether the socket to the signaling service is open. A socket whose connection died without
     * closing still counts as open, see {@link #isChannelAlive(long)}.
     */
    @Override
    public boolean isChannelAlive() {
        Channel ch = this.channel;
        return ch != null && ch.isActive();
    }

    /**
     * Whether the socket is open and received a frame within the given time. The service answers
     * the ping sent every 15 seconds, so two or three times that holds on a live socket even when
     * no signals flow.
     *
     * @param maxSilenceMillis The longest time since the last received frame that still counts.
     * @return true if the socket is open and not silent for longer than that.
     */
    public boolean isChannelAlive(long maxSilenceMillis) {
        if (!isChannelAlive()) {
            return false;
        }
        long silence = getMillisSinceLastMessage();
        return silence >= 0 && silence <= maxSilenceMillis;
    }

    /**
     * @return The milliseconds since the last frame received on the current socket, or -1 if none
     * has arrived yet.
     */
    public long getMillisSinceLastMessage() {
        long last = this.lastMessageReceivedAt;
        return last == 0 ? -1 : System.currentTimeMillis() - last;
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
            NetherNetConstants.Signal signal = NetherNetConstants.parseSignal(rawMsg);
            if (signal == null) {
                return;
            }
            String connectionId = signal.connectionId();

            SignalHandler handler = handlers.get(connectionId);
            if (handler != null) {
                handler.onSignal(rawMsg);
                return;
            }

            if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(signal.type())
                    && newConnectionHandler != null) {
                newConnectionHandler.onConnect(connectionId, sender, signal.payload(), null, null);
            } else {
                log.debug("No handler found for connection ID: {} (Type: {})", connectionId, signal.type());
            }
        } catch (Exception e) {
            log.error("Failed to dispatch signal: {}", rawMsg, e);
        }
    }

    /**
     * Applies TURN credentials the service sent on the given socket, and completes the connect if
     * it was waiting for them. Credentials from a socket that has been replaced are dropped.
     */
    protected void updateIceServers(Channel source, List<IceServerInfo> servers) {
        synchronized (this) {
            if (!isCurrentChannel(source)) {
                return;
            }
            this.iceServers = servers;
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.complete(servers);
            }
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
            if (servers == null || servers.isEmpty()) {
                log.debug("No TURN servers in response.");
                return result;
            }
            for (JsonElement el : servers) {
                IceServerInfo info = parseTurnServer(el);
                if (info != null) {
                    result.add(info);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse TURN servers", e);
        }
        log.debug("Successfully parsed {} ICE servers.", result.size());
        return result;
    }

    protected @Nullable IceServerInfo parseTurnServer(JsonElement el) {
        JsonObject server = el.getAsJsonObject();
        List<String> urls = new ArrayList<>();

        JsonArray urlsArray = null;
        if (server.has("Urls")) {
            urlsArray = server.getAsJsonArray("Urls");
        } else if (server.has("urls")) {
            urlsArray = server.getAsJsonArray("urls");
        }

        if (urlsArray == null) {
            return null;
        }
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

        return info.build();
    }
}
