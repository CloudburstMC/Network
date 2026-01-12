package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Sharable
public class NetherNetXboxSignaling extends SimpleChannelInboundHandler<TextWebSocketFrame> implements NetherNetClientSignaling, NetherNetServerSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetXboxSignaling.class);
    private static final Gson gson = new Gson();

    private final String xboxToken;
    private final String localNetworkId;
    private final URI uri;
    private final EventLoopGroup eventLoopGroup;
    
    private Channel channel;
    private CompletableFuture<List<IceServerInfo>> connectFuture;

    private final Map<Long, SignalHandler> handlers = new ConcurrentHashMap<>();
    private NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    private volatile NetherNetClientSignaling.NotFoundHandler notFoundHandler;

    private volatile List<IceServerInfo> iceServers = new ArrayList<>();

    /**
     * Creates a NetherNetXboxSignaling instance.
     * 
     * @param networkId The Network ID to use.
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxSignaling(String networkId, String xboxToken) {
        this.localNetworkId = networkId;
        this.xboxToken = xboxToken;
        this.uri = URI.create("wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/" + networkId);
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    /**
     * Creates a NetherNetXboxSignaling instance.
     * 
     * @param localNetworkId The local Network ID to use.
     * @param xboxToken      The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxSignaling(long localNetworkId, String xboxToken) {
        this(Long.toUnsignedString(localNetworkId), xboxToken);
    }

    /**
     * Creates a NetherNetXboxSignaling instance with a random local Network ID.
     * 
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxSignaling(String xboxToken) {
        this(Long.toUnsignedString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)), xboxToken);
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public synchronized CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
        // SocketAddress is ignored for Xbox Signaling Service connection
        return connectInternal();
    }

    @Override
    public void bind(SocketAddress localAddress) throws ConnectException {
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

    private synchronized CompletableFuture<List<IceServerInfo>> connectInternal() {
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
                    .add("Session-Id", UUID.randomUUID().toString())
                    .add("Request-Id", UUID.randomUUID().toString())
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
                     p.addLast("handler", NetherNetXboxSignaling.this);
                 }
             });

            this.channel = b.connect(uri.getHost(), 443).sync().channel();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ConnectException) {
                connectFuture.completeExceptionally(cause);
            } else {
                 ConnectException ce = new ConnectException("Failed to connect to Xbox Signaling: " + cause.getMessage());
                 ce.initCause(cause);
                 connectFuture.completeExceptionally(ce);
            }
        }
        return connectFuture;
    }

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
    public void setAdvertisementData(PongData pongData) {
        // No-op for Xbox Signaling. 
        // Advertisement is handled via the Session Directory service (PUT /session/...).
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            log.debug("NetherNet Signaling WebSocket Connected");
            startPingLoop(ctx);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            if (!json.has("Type")) {
                log.debug("Ignored message without Type: {}", text);
                return;
            }

            int type = json.get("Type").getAsInt();
            switch (type) {
                case NetherNetConstants.XBOX_SIGNAL_NOT_FOUND -> handleNotFound(json, text);
                case NetherNetConstants.XBOX_SIGNAL_SIGNAL -> handleSignal(json);
                case NetherNetConstants.XBOX_SIGNAL_CREDENTIALS -> handleCredentials(json, text);
                case NetherNetConstants.XBOX_SIGNAL_ACCEPTED -> log.trace("Signal Accepted: {}", text);
                case NetherNetConstants.XBOX_SIGNAL_ACK -> log.trace("Delivery Ack: {}", text);
                default -> log.debug("Unknown message type {}: {}", type, text);
            }
        } catch (Exception e) {
            log.error("Error processing signaling frame: " + text, e);
        }
    }

    private void handleNotFound(JsonObject json, String rawText) {
        log.debug("Peer Not Found. Payload: {}", rawText);
        if (notFoundHandler != null) {
            String reason = json.has("Message") ? json.get("Message").getAsString() : rawText;
            notFoundHandler.onNotFound(reason);
        }
    }

    private void handleSignal(JsonObject json) {
        log.trace("Received Signal: {}", json.toString());
        String sender = json.has("From") ? json.get("From").getAsString() : "0";
        if (!json.has("Message")) {
            log.warn("Received SIGNAL (1) without Message payload.");
            return;
        }

        String rawMsg = json.get("Message").getAsString();
        dispatchSignalToPipeline(sender, rawMsg);
    }

    private void handleCredentials(JsonObject json, String rawText) {
        log.trace("Received Credentials: {}", rawText);
        if (json.has("Message") && connectFuture != null && !connectFuture.isDone()) {
            connectFuture.complete(parseTurnServers(json.get("Message").getAsString()));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (connectFuture != null && !connectFuture.isDone()) {
            connectFuture.completeExceptionally(cause);
        }
        log.error("Signaling Exception: {}", cause.getMessage(), cause);
        ctx.close();
    }

    private void dispatchSignalToPipeline(String sender, String rawMsg) {
        try {
            // Signal Format: <Type> <ConnectionID> <Data>
            String[] parts = rawMsg.split(" ", 3);
            if (parts.length < 2) return;

            long connectionId = Long.parseUnsignedLong(parts[1]);
            
            // Try specific connection handlers (Existing Connections)
            SignalHandler handler = handlers.get(connectionId);
            if (handler != null) {
                handler.onSignal(rawMsg);
                return;
            }

            // Try New Connection Handler (Server Mode)
            if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(parts[0]) && newConnectionHandler != null) {
                String payload = parts.length > 2 ? parts[2] : "";
                newConnectionHandler.onConnect(connectionId, sender, payload);
            }

        } catch (NumberFormatException e) {
            log.debug("Malformed Connection ID in signal: {}", rawMsg);
        } catch (Exception e) {
            log.error("Failed to dispatch signal: {}", rawMsg, e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        synchronized (this) {
            if (connectFuture != null) {
                if (!connectFuture.isDone()) {
                    connectFuture.completeExceptionally(new ClosedChannelException());
                }
                connectFuture = null;
            }
            this.channel = null;
        }
        super.channelInactive(ctx);
    }

    private void startPingLoop(ChannelHandlerContext ctx) {
        ctx.executor().scheduleAtFixedRate(() -> {
            JsonObject ping = new JsonObject();
            ping.addProperty("Type", 0); 
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(ping)));
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        if (channel != null && channel.isActive()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("Type", 1);
            msg.addProperty("To", targetNetworkId);
            msg.addProperty("Message", data);
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(msg)));
        } else {
            throw new IllegalStateException("Attempted to send signal to " + targetNetworkId + " but WebSocket is closed or null!");
        }
    }

    @Override
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        this.handlers.put(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        this.handlers.remove(connectionId);
    }
    
    private List<IceServerInfo> parseTurnServers(String jsonString) {
        List<IceServerInfo> result = new ArrayList<>();
        try {
            JsonObject root = gson.fromJson(jsonString, JsonObject.class);
            
            JsonArray servers = null;
            if (root.has("TurnAuthServers")) {
                servers = root.getAsJsonArray("TurnAuthServers");
            } else if (root.has("turnAuthServers")) {
                servers = root.getAsJsonArray("turnAuthServers");
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
                        
                        IceServerInfo.Builder info = new IceServerInfo.Builder();
                        info.setUrls(urls);
                        
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
        
        log.debug("Successfully parsed " + result.size() + " ICE servers.");
        return result;
    }

    @Override
    public void close() {
        if (channel != null) channel.close();
        eventLoopGroup.shutdownGracefully();
    }
}