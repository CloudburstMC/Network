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
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Sharable because every socket a reconnect opens adds this same handler to its pipeline
@Sharable
public class NetherNetXboxRpcSignaling extends AbstractNetherNetXboxSignaling {
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    /**
     * How often TURN credentials are fetched again, so peers created late on a long-lived socket
     * are not handed expired ones.
     */
    private static final long TURN_REFRESH_INTERVAL_SECONDS = 30 * 60;

    /**
     * How often a message is sent to this host's own player id.
     * <p>
     * Microsoft's signaling service can keep a socket open after the registration behind it has
     * died. It keeps answering pings, and only closes the socket once it has to route a message
     * through it. Without a probe, that message is the first player who tries to join, so until
     * then the socket looks alive while no join can arrive.
     * <p>
     * The service routes the probe back only while the registration is alive, over the same route
     * a joining player's signals take, so a dead registration shows without waiting for a player.
     */
    private static final long ROUTE_PROBE_INTERVAL_SECONDS = 30;

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private volatile long lastRouteProvenAt;
    private volatile @Nullable String routeFailure;
    private volatile boolean routeProbeUnsupported;
    private volatile int routeProbesSent;
    private volatile boolean routeUnansweredWarned;

    /**
     * A request waiting for its reply, with the socket it was written to. A reply only settles it
     * on that socket, and only that socket going inactive fails it.
     */
    private static final class PendingRequest {
        final CompletableFuture<JsonObject> future;
        final Channel channel;
        private ScheduledFuture<?> timeout;

        PendingRequest(CompletableFuture<JsonObject> future, Channel channel) {
            this.future = future;
            this.channel = channel;
        }

        synchronized void setTimeout(ScheduledFuture<?> timeout) {
            if (future.isDone()) {
                timeout.cancel(false);
            } else {
                this.timeout = timeout;
            }
        }

        synchronized void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }
    }

    /** The service answered a request with an error. */
    private static final class RpcResponseException extends RuntimeException {
        RpcResponseException(String message) {
            super(message);
        }
    }

    /**
     * Creates a NetherNetXboxRpcSignaling instance.
     *
     * @param networkId The Network ID to use.
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxRpcSignaling(String networkId, String xboxToken) {
        super(networkId, xboxToken,
                URI.create("wss://signal.franchise.minecraft-services.net/ws/v1.0/messaging/connect"));
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
     * Fetches TURN credentials over the current socket and applies them. A failure fails the
     * connect if it is still waiting for them, and otherwise keeps the previous credentials.
     */
    private void refreshTurnCredentials() {
        Channel source = channel;
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH, new JsonObject())
                .thenAccept(response -> updateIceServers(source, parseTurnServers(response)))
                .exceptionally(t -> {
                    synchronized (this) {
                        if (!isCurrentChannel(source)) {
                            return null;
                        }
                        log.error("Failed to fetch TURN credentials", t);
                        if (connectFuture != null && !connectFuture.isDone()) {
                            connectFuture.completeExceptionally(t);
                        }
                    }
                    return null;
                });
    }

    @Override
    protected void onChannelInactive(ChannelHandlerContext ctx) {
        // Fails what was sent on this socket, but not what a replacement has sent since
        pendingRequests.forEach((id, request) -> {
            if (request.channel == ctx.channel()) {
                request.future.completeExceptionally(new ClosedChannelException());
            }
        });
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            pendingRequests.forEach((id, request) -> request.future.completeExceptionally(new ClosedChannelException()));
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();

            if (json.has("result") || (json.has("error") && json.has("id"))) {
                handleResponse(ctx.channel(), json);
            } else if (json.has("method")) {
                handleRequest(json);
            }
        } catch (Exception e) {
            log.error("Error processing signaling frame: " + text, e);
        }
    }

    private void handleResponse(Channel source, JsonObject json) {
        if (!json.has("id") || json.get("id").isJsonNull()) {
            return;
        }
        String id = json.get("id").getAsString();
        PendingRequest pending = pendingRequests.get(id);
        if (pending == null || pending.channel != source) {
            return;
        }
        CompletableFuture<JsonObject> future = pending.future;

        try {
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

                // Reported once, and not for a request that already timed out or a replaced socket
                boolean completed = future.completeExceptionally(new RpcResponseException(msg));
                if (completed && isNotFound && isCurrentChannel(source) && failureHandler != null) {
                    failureHandler.onFailure(msg);
                }
            } else {
                future.complete(
                        json.has("result") && !json.get("result").isJsonNull() ? json.getAsJsonObject("result") :
                                new JsonObject());
            }
        } catch (RuntimeException e) {
            future.completeExceptionally(new IllegalArgumentException("Invalid signaling RPC response", e));
        }
    }

    private void handleRequest(JsonObject json) {
        String method = json.get("method").getAsString();
        JsonElement id = json.get("id");

        switch (method) {
            case NetherNetConstants.XBOX_RPC_METHOD_RECEIVE_MESSAGE -> {
                if (id != null) {
                    sendJsonRpcResult(id, null);
                }

                // Several messages at once come as an array, a single one as an object
                JsonElement params = json.get("params");
                if (params != null && params.isJsonArray()) {
                    for (JsonElement el : params.getAsJsonArray()) {
                        processIncomingMessage(el.getAsJsonObject());
                    }
                } else if (params != null && params.isJsonObject()) {
                    processIncomingMessage(params.getAsJsonObject());
                }
            }
            case NetherNetConstants.XBOX_RPC_METHOD_PONG, NetherNetConstants.XBOX_RPC_METHOD_PING -> {
                if (id != null) {
                    sendJsonRpcResult(id, null);
                }
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

        // A delivery notification ends the exchange. Acknowledging it too starts an endless loop
        // with a peer that does the same.
        if (NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY.equals(innerMethod)) {
            return;
        }

        // Our own probe came back, so the registration is routable. It gets no delivery
        // notification, which would be routed back to us as well.
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
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE,
                createSendParams(from, innerMsg.toString()));

        if (NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC.equals(innerMethod)) {
            try {
                String payload = innerJson.getAsJsonObject("params").get("message").getAsString();
                dispatchSignalToPipeline(from, payload);
            } catch (Exception e) {
                log.error("Failed to parse inner signaling message from " + from, e);
            }
        }
    }

    /**
     * The RPC signaling addresses players by the pmid claim of the MCToken, not by network id. Read
     * from the current token, since a reconnect can install a new one.
     *
     * @return This host's own player id, or null if the token carries none.
     */
    private @Nullable String localPlayerId() {
        String token = this.xboxToken;
        if (token == null) {
            return null;
        }
        String[] parts = token.split(" ", 2);
        if (parts.length < 2) {
            return null;
        }
        String[] jwt = parts[1].split("\\.");
        if (jwt.length < 2) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
            JsonObject claims = JsonParser.parseString(payload).getAsJsonObject();
            return claims.has("pmid") ? claims.get("pmid").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSelf(String from) {
        if (from.equals(localNetworkId)) {
            return true;
        }
        String playerId = localPlayerId();
        return playerId != null && playerId.equalsIgnoreCase(from);
    }

    /**
     * Sends a message to this host's own player id. While the registration lives, it comes back
     * through processIncomingMessage. Once it has died, the service answers with an error instead,
     * or closes the socket.
     */
    private void sendRouteProbe() {
        Channel source = channel;
        if (routeProbeUnsupported) {
            return;
        }

        String playerId = localPlayerId();
        if (playerId == null) {
            routeProbeUnsupported = true;
            log.warn("Signaling route probe disabled, the MCToken carries no pmid to address this host by");
            return;
        }

        if (lastRouteProvenAt == 0 && routeProbesSent >= 3 && !routeUnansweredWarned) {
            routeUnansweredWarned = true;
            log.warn("Signaling route probe unanswered after {} probes, the route cannot be verified on this socket",
                    routeProbesSent);
        }
        routeProbesSent++;

        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", new JsonObject());
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_ROUTE_PROBE);
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE, createSendParams(playerId, innerMsg.toString()))
                .exceptionally(t -> {
                    synchronized (this) {
                        // A lost reply or a failed write does not show that the service refused the route
                        if (!isCurrentChannel(source) || !(t instanceof RpcResponseException)) {
                            return null;
                        }
                        if (lastRouteProvenAt == 0) {
                            // Without one probe that came back, a refusal does not show that the
                            // route to this host is broken, only that the probe is not accepted
                            routeProbeUnsupported = true;
                            log.warn("Signaling route probe refused on a new socket, route checks disabled: {}",
                                    t.getMessage());
                        } else {
                            routeFailure = t.getMessage();
                            log.warn("Signaling route probe refused: {}", t.getMessage());
                        }
                    }
                    return null;
                });
    }

    /**
     * Whether the service still routes messages to this host, the check that fails when the
     * registration died while the socket stayed open. A socket with no probe back yet counts as
     * alive: it is either new, or the service does not route messages a host sends to itself, and
     * neither shows that the registration died.
     *
     * @param maxSilenceMillis The longest time since the last probe came back that still counts.
     * @return false if the service refused a probe after an earlier one came back, or if no probe
     * came back within the given time.
     */
    public boolean isRouteAlive(long maxSilenceMillis) {
        if (routeFailure != null) {
            return false;
        }
        long proven = lastRouteProvenAt;
        return proven == 0 || System.currentTimeMillis() - proven <= maxSilenceMillis;
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        Channel channel = this.channel;
        if (channel == null || !channel.isActive()) {
            throw new IllegalStateException("Signaling channel is not active");
        }

        JsonObject innerParams = new JsonObject();
        innerParams.addProperty("netherNetId", localNetworkId);
        innerParams.addProperty("message", data);

        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", innerParams);
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC);

        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE,
                createSendParams(targetNetworkId, innerMsg.toString()));
    }

    private JsonObject createSendParams(String toPlayerId, String message) {
        JsonObject params = new JsonObject();
        params.addProperty("toPlayerId", toPlayerId);
        params.addProperty("messageId", UUID.randomUUID().toString());
        params.addProperty("message", message);
        return params;
    }

    /**
     * Sends a request on the current socket. It fails if that socket closes first, or if no reply
     * arrives within {@link #CONNECT_TIMEOUT_SECONDS}.
     */
    synchronized CompletableFuture<JsonObject> sendJsonRpcRequest(String method, JsonObject params) {
        String id = UUID.randomUUID().toString();
        JsonObject rpc = new JsonObject();
        rpc.add("params", params);
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("method", method);
        rpc.addProperty("id", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        Channel source = this.channel;
        if (!isCurrentChannel(source) || !source.isActive()) {
            future.completeExceptionally(new ClosedChannelException());
            return future;
        }

        PendingRequest pending = new PendingRequest(future, source);
        pendingRequests.put(id, pending);
        future.whenComplete((result, error) -> {
            pendingRequests.remove(id, pending);
            pending.cancelTimeout();
        });
        TextWebSocketFrame frame = null;
        try {
            pending.setTimeout(source.eventLoop().schedule(() -> {
                future.completeExceptionally(new TimeoutException("Signaling RPC timed out: " + method));
            }, CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            frame = new TextWebSocketFrame(gson.toJson(rpc));
            source.writeAndFlush(frame).addListener(write -> {
                if (write.isCancelled()) {
                    future.cancel(false);
                } else if (!write.isSuccess()) {
                    future.completeExceptionally(write.cause());
                }
            });
        } catch (RuntimeException e) {
            if (frame != null && frame.refCnt() > 0) {
                frame.release();
            }
            future.completeExceptionally(e);
        }
        return future;
    }

    private void sendJsonRpcResult(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        response.add("result", result);
        response.addProperty("jsonrpc", "2.0");
        Channel channel = this.channel;
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
        }
    }
}
