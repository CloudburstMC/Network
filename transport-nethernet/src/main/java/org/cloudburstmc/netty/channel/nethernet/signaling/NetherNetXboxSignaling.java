package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Sharable
public class NetherNetXboxSignaling extends AbstractNetherNetXboxSignaling {
    private static final Gson gson = new Gson();

    /**
     * Creates a NetherNetXboxSignaling instance.
     * 
     * @param networkId The Network ID to use.
     * @param xboxToken The Minecraft Bedrock Session authorization header ('MCToken ***').
     */
    public NetherNetXboxSignaling(String networkId, String xboxToken) {
        super(networkId, xboxToken, URI.create("wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/" + networkId));
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
    protected void onConnected(ChannelHandlerContext ctx) {
        ctx.executor().scheduleAtFixedRate(() -> {
            JsonObject ping = new JsonObject();
            ping.addProperty("Type", 0); 
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(ping)));
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            if (!json.has("Type")) return;

            int type = json.get("Type").getAsInt();
            switch (type) {
                case NetherNetConstants.XBOX_SIGNAL_NOT_FOUND -> {
                    log.debug("Peer Not Found: {}", text);
                    if (notFoundHandler != null) {
                        String reason = json.has("Message") ? json.get("Message").getAsString() : text;
                        notFoundHandler.onNotFound(reason);
                    }
                }
                case NetherNetConstants.XBOX_SIGNAL_SIGNAL -> {
                    String sender = json.has("From") ? json.get("From").getAsString() : "0";
                    if (json.has("Message")) {
                        dispatchSignalToPipeline(sender, json.get("Message").getAsString());
                    }
                }
                case NetherNetConstants.XBOX_SIGNAL_CREDENTIALS -> {
                    log.trace("Received Credentials");
                    if (json.has("Message") && connectFuture != null && !connectFuture.isDone()) {
                        String rawMsg = json.get("Message").getAsString();
                        JsonObject credentials = JsonParser.parseString(rawMsg).getAsJsonObject();
                        
                        connectFuture.complete(parseTurnServers(credentials));
                    }
                }
                case NetherNetConstants.XBOX_SIGNAL_ACCEPTED, NetherNetConstants.XBOX_SIGNAL_ACK -> log.trace("Signal Ack: {}", text);
                default -> log.debug("Unknown message type {}: {}", type, text);
            }
        } catch (Exception e) {
            log.error("Error processing signaling frame: " + text, e);
        }
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
            throw new IllegalStateException("Attempted to send signal to " + targetNetworkId + " but WebSocket is closed!");
        }
    }
}