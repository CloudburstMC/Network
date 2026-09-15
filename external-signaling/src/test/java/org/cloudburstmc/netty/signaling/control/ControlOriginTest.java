package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ControlOriginTest {
    private static JsonObject fixture(String name) throws Exception {
        Path path = Path.of("docs/external-signaling/" + name);
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void allSharedAdversarialOriginsHaveOneExactAcceptanceResult() throws Exception {
        for (var item : fixture("control-v1.origins.fixtures.json").getAsJsonArray("vectors")) {
            JsonObject vector = item.getAsJsonObject();
            String origin = vector.get("origin").getAsString();
            boolean accepted = vector.get("accepted").getAsBoolean();
            assertEquals(accepted, ControlOrigin.isCanonical(origin), origin);
            if (accepted) {
                ControlOrigin.requireCanonical(origin);
                assertNotNull(URI.create(origin).getHost(), "JDK HTTP host must be usable: " + origin);
            } else assertThrows(IllegalArgumentException.class, () -> ControlOrigin.requireCanonical(origin), origin);
        }
        assertFalse(ControlOrigin.isCanonical(null));
    }

    @Test
    void everyDraftCarrierUsesTheSameOriginProfile() throws Exception {
        JsonObject frame = fixture("control-v1.frames.fixtures.json").getAsJsonArray("vectors").get(0).getAsJsonObject().getAsJsonObject("frame");
        JsonObject intent = fixture("control-v1.lifecycle.fixtures.json").getAsJsonArray("vectors").get(0).getAsJsonObject().getAsJsonObject("intent");
        JsonArray sessions = fixture("control-v1.sessions.fixtures.json").getAsJsonArray("vectors");
        JsonObject session = sessions.get(0).getAsJsonObject().getAsJsonObject("envelope");
        JsonObject http = java.util.stream.StreamSupport.stream(sessions.spliterator(), false).map(value -> value.getAsJsonObject())
                .filter(value -> value.get("kind").getAsString().equals("http")).findFirst().orElseThrow().getAsJsonObject("envelope");
        for (var item : fixture("control-v1.origins.fixtures.json").getAsJsonArray("vectors")) {
            JsonObject vector = item.getAsJsonObject();
            String origin = vector.get("origin").getAsString();
            frame.addProperty("audience", origin); intent.addProperty("audience", origin); session.addProperty("audience", origin);
            http.addProperty("audience", origin); http.getAsJsonObject("intent").addProperty("audience", origin);
            if (vector.get("accepted").getAsBoolean()) {
                assertEquals(origin, ControlFrameCodec.decode(frame.toString()).audience());
                assertEquals(origin, ControlLifecycleCodec.decodeIntent(intent.toString()).audience());
                assertEquals(origin, ControlSessionCodec.decodeRequest(session.toString()).audience());
                assertEquals(origin, ControlHttpCodec.decode(http.toString()).audience());
            } else {
                assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(frame.toString()), origin);
                assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.decodeIntent(intent.toString()), origin);
                assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(session.toString()), origin);
                assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.decode(http.toString()), origin);
            }
        }
    }

    @Test
    void narrowerControlProfileDoesNotChangeCoreOriginHandling() {
        String idn = "https://xn--bcher-kva.example", trailingDot = "https://provider.example.";
        assertFalse(ControlOrigin.isCanonical(idn));
        assertFalse(ControlOrigin.isCanonical(trailingDot));
        assertEquals(idn, ProviderCrypto.origin(URI.create(idn)));
        assertEquals(trailingDot, ProviderCrypto.origin(URI.create(trailingDot)));
    }

    public static void main(String[] arguments) throws Exception {
        JsonArray accepted = new JsonArray();
        for (var item : fixture("control-v1.origins.fixtures.json").getAsJsonArray("vectors")) {
            accepted.add(ControlOrigin.isCanonical(item.getAsJsonObject().get("origin").getAsString()));
        }
        System.out.println(accepted);
    }
}
