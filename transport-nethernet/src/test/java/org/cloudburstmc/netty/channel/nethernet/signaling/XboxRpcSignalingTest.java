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
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON-RPC signaling read and written through an in-memory websocket. */
public class XboxRpcSignalingTest {

    private static final String SERVER = "2535000000000001";

    private NetherNetXboxRpcSignaling signaling;
    private EmbeddedChannel socket;

    @BeforeEach
    void open() {
        this.signaling = new NetherNetXboxRpcSignaling("7", "MCToken test");
        this.socket = new EmbeddedChannel(this.signaling);
    }

    @AfterEach
    void close() {
        this.socket.finishAndReleaseAll();
        this.signaling.close();
    }

    @Test
    void readsOneMessage() {
        List<String> received = new ArrayList<>();
        this.signaling.setSignalHandler("11", received::add);

        this.receive(message(NetherNetConstants.buildSignalCandidateAdd("11", "a")));

        assertEquals(List.of(NetherNetConstants.buildSignalCandidateAdd("11", "a")), received);
    }

    /** The service can deliver several messages in one request, as a params array. */
    @Test
    void readsABatchOfMessages() {
        List<String> received = new ArrayList<>();
        this.signaling.setSignalHandler("11", received::add);
        this.signaling.setSignalHandler("22", received::add);

        JsonArray batch = new JsonArray();
        batch.add(message(NetherNetConstants.buildSignalCandidateAdd("11", "a")));
        batch.add(message(NetherNetConstants.buildSignalCandidateAdd("22", "b")));
        this.receive(batch);

        assertEquals(List.of(NetherNetConstants.buildSignalCandidateAdd("11", "a"),
                NetherNetConstants.buildSignalCandidateAdd("22", "b")), received);
    }

    @Test
    void reportsAPeerThatIsNotRegistered() {
        this.signaling.channel = this.socket;
        List<String> failures = new ArrayList<>();
        this.signaling.setFailureHandler(failures::add);

        this.signaling.sendSignal(SERVER, NetherNetConstants.buildSignalConnectRequest("11", "offer"));
        this.respondNotRegistered(this.sentRequest());

        assertEquals(List.of("Player not registered"), failures);
    }

    /** Acknowledging a message with no socket used to leave the acknowledgement pending for good. */
    @Test
    void aRequestThatCannotGoOutLeavesNothingPending() {
        this.signaling.setSignalHandler("11", signal -> { });

        this.receive(message(NetherNetConstants.buildSignalCandidateAdd("11", "a")));

        assertEquals(0, this.signaling.pendingRequestCount());
    }

    @Test
    void theSocketClosingFailsTheRequestsInFlight() {
        this.signaling.channel = this.socket;
        this.signaling.sendSignal(SERVER, NetherNetConstants.buildSignalConnectRequest("11", "offer"));
        assertEquals(1, this.signaling.pendingRequestCount());

        this.socket.pipeline().fireChannelInactive();

        assertEquals(0, this.signaling.pendingRequestCount());
    }

    /** Each reconnect used to add a keepalive on top of the ones before it. */
    @Test
    void theKeepaliveStopsWithTheSocket() {
        this.signaling.channel = this.socket;
        this.socket.pipeline().fireUserEventTriggered(ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);
        assertNotEquals(-1, this.socket.runScheduledPendingTasks(), "no keepalive was scheduled");

        this.socket.pipeline().fireChannelInactive();

        assertEquals(-1, this.socket.runScheduledPendingTasks(), "the keepalive outlived the socket");
    }

    /** A channel from {@code NetherNetChannelFactory.client} closes it, which used to fail obscurely. */
    @Test
    void aClosedSignalingSaysSoWhenConnected() {
        this.signaling.close();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> this.signaling.connect(null).join());

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("closed"), failure.getCause().getMessage());
    }

    /** The id of the next request this side sent, skipping its responses to the service. */
    private String sentRequest() {
        TextWebSocketFrame frame;
        while ((frame = this.socket.readOutbound()) != null) {
            try {
                JsonObject json = JsonParser.parseString(frame.text()).getAsJsonObject();
                if (json.has("method")) {
                    return json.get("id").getAsString();
                }
            } finally {
                frame.release();
            }
        }
        throw new AssertionError("no request was sent");
    }

    private void respondNotRegistered(String id) {
        JsonObject error = new JsonObject();
        error.addProperty("code", 404);
        error.addProperty("message", "Player not registered");
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", id);
        response.add("error", error);
        this.socket.writeInbound(new TextWebSocketFrame(response.toString()));
    }

    private void receive(JsonElement params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", NetherNetConstants.XBOX_RPC_METHOD_RECEIVE_MESSAGE);
        request.addProperty("id", UUID.randomUUID().toString());
        request.add("params", params);
        this.socket.writeInbound(new TextWebSocketFrame(request.toString()));
    }

    /** A signal from the server, wrapped the way the service relays it. */
    private static JsonObject message(String signal) {
        JsonObject webRtc = new JsonObject();
        webRtc.addProperty("netherNetId", SERVER);
        webRtc.addProperty("message", signal);
        JsonObject inner = new JsonObject();
        inner.addProperty("jsonrpc", "2.0");
        inner.addProperty("method", NetherNetConstants.XBOX_RPC_INNER_METHOD_WEBRTC);
        inner.add("params", webRtc);

        JsonObject message = new JsonObject();
        message.addProperty("Id", UUID.randomUUID().toString());
        message.addProperty("From", SERVER);
        message.addProperty("Message", inner.toString());
        return message;
    }
}
