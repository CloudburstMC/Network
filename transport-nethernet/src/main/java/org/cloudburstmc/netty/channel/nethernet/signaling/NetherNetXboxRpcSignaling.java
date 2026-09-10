package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetXboxRpcSignaling extends AbstractNetherNetXboxSignaling {
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Map<String, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

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
        ctx.executor().scheduleAtFixedRate(() -> {
            if (channel != null && channel.isActive()) {
                sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_PING, new JsonObject());
            }
        }, 30, 50, TimeUnit.SECONDS);

        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_TURN_AUTH, new JsonObject())
                .thenAccept(response -> {
                    List<IceServerInfo> servers = parseTurnServers(response);
                    if (connectFuture != null && !connectFuture.isDone()) {
                        connectFuture.complete(servers);
                    }
                })
                .exceptionally(t -> {
                    log.error("Failed to fetch TURN credentials", t);
                    if (connectFuture != null && !connectFuture.isDone()) {
                        connectFuture.completeExceptionally(t);
                    }
                    return null;
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
        if (!json.has("id") || json.get("id").isJsonNull()) {
            return;
        }
        String id = json.get("id").getAsString();
        CompletableFuture<JsonObject> future = pendingRequests.remove(id);

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
                future.complete(
                        json.has("result") && !json.get("result").isJsonNull() ? json.getAsJsonObject("result") :
                                new JsonObject());
            }
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

                if (json.isJsonArray()) {
                    JsonArray params = json.getAsJsonArray("params");
                    if (params != null) {
                        for (JsonElement el : params) {
                            processIncomingMessage(el.getAsJsonObject());
                        }
                    }
                } else if (json.isJsonObject()) {
                    JsonObject params = json.getAsJsonObject("params");
                    if (params != null) {
                        processIncomingMessage(params);
                    }
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

        JsonObject innerParams = new JsonObject();
        innerParams.addProperty("messageId", msgId);
        JsonObject innerMsg = new JsonObject();
        innerMsg.add("params", innerParams);
        innerMsg.addProperty("jsonrpc", "2.0");
        innerMsg.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_DELIVERY);
        sendJsonRpcRequest(NetherNetConstants.XBOX_RPC_METHOD_SEND_MESSAGE,
                createSendParams(from, innerMsg.toString()));

        try {
            JsonObject innerJson = JsonParser.parseString(rawInner).getAsJsonObject();
            if (innerJson.has("method") && NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC.equals(
                    innerJson.get("method").getAsString())) {
                String payload = innerJson.getAsJsonObject("params").get("message").getAsString();
                dispatchSignalToPipeline(from, payload);
            }
        } catch (Exception e) {
            log.error("Failed to parse inner signaling message from " + from, e);
        }
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
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

    private CompletableFuture<JsonObject> sendJsonRpcRequest(String method, JsonObject params) {
        String id = UUID.randomUUID().toString();
        JsonObject rpc = new JsonObject();
        rpc.add("params", params);
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("method", method);
        rpc.addProperty("id", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        if (channel != null && channel.isActive()) {
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
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
        }
    }
}
