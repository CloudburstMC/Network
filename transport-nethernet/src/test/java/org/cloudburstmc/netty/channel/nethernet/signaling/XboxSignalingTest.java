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

import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The older signaling protocol's failures, read through an in-memory websocket. */
public class XboxSignalingTest {

    private NetherNetXboxSignaling signaling;
    private EmbeddedChannel socket;

    @BeforeEach
    void open() {
        this.signaling = new NetherNetXboxSignaling("7", "MCToken test");
        this.socket = new EmbeddedChannel(this.signaling);
    }

    @AfterEach
    void close() {
        this.socket.finishAndReleaseAll();
        this.signaling.close();
    }

    @Test
    void reportsAMissingPeer() {
        List<String> failures = new ArrayList<>();
        this.signaling.setFailureHandler(failures::add);

        this.notFound();

        assertEquals(List.of("Player not found"), failures);
    }

    private void notFound() {
        JsonObject message = new JsonObject();
        message.addProperty("Type", NetherNetConstants.XBOX_SIGNAL_NOT_FOUND);
        message.addProperty("Message", "Player not found");
        this.socket.writeInbound(new TextWebSocketFrame(message.toString()));
    }
}
