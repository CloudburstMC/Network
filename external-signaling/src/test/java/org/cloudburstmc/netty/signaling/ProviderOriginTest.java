package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOriginTest {
    private static JsonObject fixture(String name) throws Exception {
        try (var reader = new InputStreamReader(
                ProviderOriginTest.class.getResourceAsStream("/nxs/" + name), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void allSharedAdversarialOriginsHaveOneExactAcceptanceResult() throws Exception {
        for (var item : fixture("origins.fixtures.json").getAsJsonArray("vectors")) {
            JsonObject vector = item.getAsJsonObject();
            String origin = vector.get("origin").getAsString();
            boolean accepted = vector.get("accepted").getAsBoolean();
            assertEquals(accepted, ProviderOrigin.isCanonical(origin), origin);
            if (accepted) {
                ProviderOrigin.requireCanonical(origin);
                assertNotNull(
                        URI.create(origin).getHost(), "JDK HTTP host must be usable: " + origin);
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ProviderOrigin.requireCanonical(origin),
                        origin);
            }
        }
        assertFalse(ProviderOrigin.isCanonical(null));
    }

    @Test
    void coreProviderOriginsUseTheSameProfile() {
        for (String origin : new String[]{"https://xn--bcher-kva.example", "https://provider.example."}) {
            assertFalse(ProviderOrigin.isCanonical(origin));
            assertThrows(IllegalArgumentException.class, () -> ProviderOrigin.canonicalize(URI.create(origin)));
        }
    }
}
