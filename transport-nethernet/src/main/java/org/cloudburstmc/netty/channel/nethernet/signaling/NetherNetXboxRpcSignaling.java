package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Sharable
public class NetherNetXboxRpcSignaling extends AbstractNetherNetXboxSignaling {
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    /** Interval for proactively re-fetching TURN credentials so peer
     * connections created on a long-lived socket never receive expired ones. */
    private static final long TURN_REFRESH_INTERVAL_SECONDS = 30 * 60;

    /** Interval for the self addressed route probe. The service only routes it
     * back to us while our registration is alive, so it exercises the path a
     * joining client uses. Protocol level pings and System_Ping never touch
     * that path: a socket whose registration died keeps answering them. */
    private static final long ROUTE_PROBE_INTERVAL_SECONDS = 30;

    private volatile long lastRouteProvenAt;
    private volatile String routeFailure;
    private volatile boolean routeProbeUnsupported;
    private volatile int routeProbesSent;
    private volatile boolean routeUnansweredWarned;

    /**
     * An in-flight JSON-RPC request, tagged with the WebSocket channel it was
     * written to so that a dying channel only fails its own requests. During
     * a reconnect the old channel's inactive event must not fail requests
     * already sent on the replacement socket.
     */
    private static final class PendingRequest {
        final CompletableFuture<JsonObject> future;
        final Channel channel;

        PendingRequest(CompletableFuture<JsonObject> future, Channel channel) {
            this.future = future;
            this.channel = channel;
        }
    }

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Creates a NetherNetXboxRpcSignaling instance.
     *
     * @param networkId The Network ID to use.
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxRpcSignaling(String networkId, String xboxToken) {
        super(networkId, xboxToken, URI.create("wss://signal.franchise.minecraft-services.net/ws/v1.0/messaging/connect"));
    }

    /**
     * Creates a NetherNetXboxRpcSignaling instance.
     *
     * @param localNetworkId The local Network ID to use.
     * @param xboxToken      The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxRpcSignaling(long localNetworkId, String xboxToken) {
        this(Long.toUnsignedString(localNetworkId), xboxToken);
    }

    /**
     * Creates a NetherNetXboxRpcSignaling instance with a random local Network ID.
     *
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxRpcSignaling(String xboxToken) {
        this(Long.toUnsignedString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)), xboxToken);
    }

    @Override
    protected void onConnected(ChannelHandlerContext ctx) {
        lastRouteProvenAt = 0;
        routeFailure = null;
        routeProbeUnsupported = false;
        routeProbesSent = 0;
        routeUnansweredWarned = false;

        scheduleRecurring(ctx, "rpc-ping", () ->
                sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_PING, new JsonObject()), 30, 50);

        scheduleRecurring(ctx, "route-probe", this::sendRouteProbe,
                ROUTE_PROBE_INTERVAL_SECONDS, ROUTE_PROBE_INTERVAL_SECONDS);

        scheduleRecurring(ctx, "turn-refresh", this::refreshTurnCredentials,
                TURN_REFRESH_INTERVAL_SECONDS, TURN_REFRESH_INTERVAL_SECONDS);

        refreshTurnCredentials();
    }

    /**
     * Fetches TURN credentials and applies them via updateIceServers, which
     * also completes the connect future during the initial exchange. On later
     * refreshes a failure only logs; the previous credentials stay in place.
     */
    private void refreshTurnCredentials() {
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH, new JsonObject())
            .thenAccept(response -> updateIceServers(parseTurnServers(response)))
            .exceptionally(t -> {
                log.error("Failed to fetch TURN credentials", t);
                synchronized (this) {
                    if (connectFuture != null && !connectFuture.isDone()) connectFuture.completeExceptionally(t);
                }
                return null;
            });
    }

    @Override
    protected void onChannelInactive(ChannelHandlerContext ctx) {
        // Fail everything that was waiting on a reply over the dead socket so
        // callers see a prompt error instead of a future that never completes.
        // Only requests written to THIS channel: during a reconnect the old
        // channel's inactive event must not fail the new socket's requests.
        pendingRequests.entrySet().removeIf(entry -> {
            if (entry.getValue().channel == ctx.channel()) {
                entry.getValue().future.completeExceptionally(new ClosedChannelException());
                return true;
            }
            return false;
        });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();

            if (json.has("result") || (json.has("error") && json.has("id"))) {
                handleResponse(json);
            } else if (json.has("method")) {
                handleRequest(json);
            }
        } catch (Exception e) {
            log.error("Error processing signaling frame: " + text, e);
        }
    }

    private void handleResponse(JsonObject json) {
        if (!json.has("id") || json.get("id").isJsonNull()) return;
        String id = json.get("id").getAsString();
        PendingRequest pending = pendingRequests.remove(id);
        CompletableFuture<JsonObject> future = pending != null ? pending.future : null;

        if (future != null) {
            if (json.has("error") && !json.get("error").isJsonNull()) {
                JsonObject error = json.getAsJsonObject("error");
                String msg = error.has("message") ? error.get("message").getAsString() : error.toString();

                boolean isNotFound = msg.contains("Player not registered");
                if (!isNotFound && error.has("data") && error.get("data").isJsonObject()) {
                    JsonObject data = error.getAsJsonObject("data");
                    if (data.has("Code") && "MissingOrExpiredIdentity".equals(data.get("Code").getAsString())) {
                        isNotFound = true;
                    }
                }

                if (isNotFound && notFoundHandler != null) {
                    notFoundHandler.onNotFound(msg);
                }
                future.completeExceptionally(new RuntimeException(msg));
            } else {
                future.complete(json.has("result") && !json.get("result").isJsonNull() ? json.getAsJsonObject("result") : new JsonObject());
            }
        }
    }

    private void handleRequest(JsonObject json) {
        String method = json.get("method").getAsString();
        JsonElement id = json.get("id");

        switch (method) {
            case NetherNetConstants.XBOX_RPC_METHOD_RECEIVE_MESSAGE -> {
                if (id != null) sendJsonRpcResult(id, null);

                JsonElement params = json.get("params");
                if (params != null && params.isJsonArray()) {
                    for (JsonElement el : params.getAsJsonArray()) processIncomingMessage(el.getAsJsonObject());
                } else if (params != null && params.isJsonObject()) {
                    processIncomingMessage(params.getAsJsonObject());
                }
            }
            case NetherNetConstants.XBOX_RPC_METHOD_PONG, NetherNetConstants.XBOX_RPC_METHOD_PING -> {
                if (id != null) sendJsonRpcResult(id, null);
            }
        }
    }

    private void processIncomingMessage(JsonObject msgObj) {
        String from = msgObj.get("From").getAsString();
        String rawInner = msgObj.get("Message").getAsString();
        String msgId = msgObj.has("Id") ? msgObj.get("Id").getAsString() : UUID.randomUUID().toString();

        JsonObject innerJson = null;
        String innerMethod = null;
        try {
            innerJson = JsonParser.parseString(rawInner).getAsJsonObject();
            if (innerJson.has("method")) {
                innerMethod = innerJson.get("method").getAsString();
            }
        } catch (Exception e) {
            log.error("Failed to parse inner signaling message from " + from, e);
        }

        // Our own route probe came back, so the registration is routable. It gets
        // no delivery notification: that would be routed back to us as well.
        if (NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE.equals(innerMethod) && isSelf(from)) {
            if (lastRouteProvenAt == 0) {
                log.debug("Signaling route probe confirmed, the registration is routable");
            }
            lastRouteProvenAt = System.currentTimeMillis();
            routeFailure = null;
            return;
        }

        JsonObject innerParams = new JsonObject();
        innerParams.addProperty("messageId", msgId);
        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", innerParams);
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY);
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, createSendParams(from, innerMsg.toString()));

        if (innerJson != null && NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC.equals(innerMethod)) {
            try {
                String payload = innerJson.getAsJsonObject("params").get("message").getAsString();
                dispatchSignalToPipeline(from, payload);
            } catch (Exception e) {
                log.error("Failed to parse inner signaling message from " + from, e);
            }
        }
    }

    /**
     * The RPC transport addresses players by their PlayFab id, the pmid claim of
     * the MCToken, not by the numeric network id. Read from the current token
     * because a reconnect can install a fresh one.
     *
     * @return our own player id, or null if the token carries none
     */
    private String localPlayerId() {
        String token = this.xboxToken;
        if (token == null) return null;
        String[] parts = token.split(" ", 2);
        if (parts.length < 2) return null;
        String[] jwt = parts[1].split("\\.");
        if (jwt.length < 2) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
            JsonObject claims = JsonParser.parseString(payload).getAsJsonObject();
            return claims.has("pmid") ? claims.get("pmid").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSelf(String from) {
        if (from.equals(localNetworkId)) return true;
        String playerId = localPlayerId();
        return playerId != null && playerId.equalsIgnoreCase(from);
    }

    /**
     * Sends a signal to our own player id. A live registration routes it back
     * through processIncomingMessage. A dead one answers with an error or, as
     * observed with real joins, makes the service close the socket. Either way
     * the watchdog gets something it can see, which is the point: this is the
     * one liveness check that fails when the registration is gone.
     */
    private void sendRouteProbe() {
        if (routeProbeUnsupported) return;

        String playerId = localPlayerId();
        if (playerId == null) {
            routeProbeUnsupported = true;
            log.warn("Signaling route probe disabled, the MCToken carries no pmid to address ourselves by");
            return;
        }

        if (lastRouteProvenAt == 0 && routeProbesSent >= 3 && !routeUnansweredWarned) {
            routeUnansweredWarned = true;
            log.warn("Signaling route probe unanswered after {} probes, route liveness cannot be verified on this socket", routeProbesSent);
        }
        routeProbesSent++;

        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", new JsonObject());
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE);
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, createSendParams(playerId, innerMsg.toString()))
            .exceptionally(t -> {
                if (t instanceof ClosedChannelException) return null;
                if (lastRouteProvenAt == 0) {
                    // A fresh socket has a live registration by definition, so a
                    // rejected first probe means self addressed messages are not
                    // accepted at all. Without a proof the accessor stays optimistic.
                    routeProbeUnsupported = true;
                    log.warn("Signaling route probe rejected on a fresh socket, route liveness disabled: {}", t.getMessage());
                } else {
                    routeFailure = t.getMessage();
                    log.warn("Signaling route probe rejected: {}", t.getMessage());
                }
                return null;
            });
    }

    /**
     * @param maxSilenceMillis max tolerated time since the last probe came back.
     * @return false if the service rejected a probe on a socket that had a
     *         proven route, or if no probe came back within the window. A
     *         socket without any proof yet counts as alive: it is either still
     *         warming up or the service does not route self addressed messages,
     *         and neither is evidence of a dead registration.
     */
    public boolean isRouteAlive(long maxSilenceMillis) {
        if (routeFailure != null) return false;
        long proven = lastRouteProvenAt;
        if (proven == 0) return true;
        return System.currentTimeMillis() - proven <= maxSilenceMillis;
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        var channel = this.channel;
        if (channel == null || !channel.isActive()) throw new IllegalStateException("Signaling channel is not active");

        JsonObject innerParams = new JsonObject();
        innerParams.addProperty("netherNetId", localNetworkId);
        innerParams.addProperty("message", data);

        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", innerParams);
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC);

        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, createSendParams(targetNetworkId, innerMsg.toString()));
    }

    private JsonObject createSendParams(String toPlayerId, String message) {
        JsonObject params = new JsonObject();
        params.addProperty("toPlayerId", toPlayerId);
        params.addProperty("messageId", UUID.randomUUID().toString());
        params.addProperty("message", message);
        return params;
    }

    private CompletableFuture<JsonObject> sendJsonRpcRequest(String method, JsonObject params) {
        String id = UUID.randomUUID().toString();
        JsonObject rpc = new JsonObject();
        rpc.add("params", params);
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("method", method);
        rpc.addProperty("id", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        var channel = this.channel;
        if (channel != null && channel.isActive()) {
            pendingRequests.put(id, new PendingRequest(future, channel));
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(rpc)));
        } else {
            future.completeExceptionally(new ClosedChannelException());
        }
        return future;
    }

    private void sendJsonRpcResult(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        response.add("result", result);
        response.addProperty("jsonrpc", "2.0");
        var channel = this.channel;
        if (channel != null && channel.isActive()) channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }
}
