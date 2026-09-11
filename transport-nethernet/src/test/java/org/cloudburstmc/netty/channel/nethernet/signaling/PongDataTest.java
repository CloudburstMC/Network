package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PongDataTest {

    private static JsonObject json(PongData data) {
        return JsonParser.parseString(data.toJson()).getAsJsonObject();
    }

    @Test
    void carriesEveryFieldTheBuilderTakes() {
        // Each of these used to be settable and then dropped on the way to the wire
        JsonObject json = json(new PongData.Builder()
                .setServerName("Proxy")
                .setProtocol(2187)
                .setVersion("1.26.50")
                .setLevelName("Lobby")
                .setGameType(1)
                .setPlayerCount(3)
                .setMaxPlayerCount(20)
                .setIsEditorWorld(true)
                .setIsHardcore(true)
                .setConnectionType(4)
                .setTransportLayer(2)
                .build());

        assertEquals("Proxy", json.get("name").getAsString());
        assertEquals(2187, json.get("protocol").getAsInt());
        assertEquals("Lobby", json.get("level").getAsString());
        assertEquals(3, json.get("players").getAsInt());
        assertEquals(20, json.get("maxPlayers").getAsInt());
        assertEquals(1, json.get("gameType").getAsInt());
        assertTrue(json.get("editor").getAsBoolean());
        assertTrue(json.get("hardcore").getAsBoolean());
        assertEquals(4, json.get("connection").getAsInt());
        assertEquals(2, json.get("transportLayer").getAsInt());
    }

    @Test
    void defaultsMatchWhatAHostAdvertises() {
        JsonObject json = json(PongData.DEFAULT);

        assertEquals(7, json.get("dataVersion").getAsInt());
        assertTrue(json.get("onlineAuth").getAsBoolean());
        assertFalse(json.get("selfSignedAuth").getAsBoolean());
        assertEquals(16, json.get("nonce").getAsString().length());
    }

    @Test
    void everyKeyOfTheSchemaIsPresent() {
        JsonObject json = json(PongData.DEFAULT);

        for (String key : List.of("dataVersion", "name", "protocol", "version", "level", "players",
                "maxPlayers", "gameType", "editor", "hardcore", "onlineAuth", "selfSignedAuth",
                "nonce", "transportLayer", "connection")) {
            assertTrue(json.has(key), "missing " + key);
        }
    }

    @Test
    void aHostWithoutAuthServiceSaysSo() {
        JsonObject json = json(new PongData.Builder().setOnlineAuth(false).setSelfSignedAuth(true).build());

        assertFalse(json.get("onlineAuth").getAsBoolean());
        assertTrue(json.get("selfSignedAuth").getAsBoolean());
    }
}
