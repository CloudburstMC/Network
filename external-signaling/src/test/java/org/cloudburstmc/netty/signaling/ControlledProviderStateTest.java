package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ControlledProviderStateTest {
    static ProviderControlConfiguration config(String origin) throws Exception {
        var routes = new ControlClientCoordinator.Config(origin, URI.create(origin + "/prepare"), URI.create(origin + "/activate"),
                URI.create(origin + "/status"), URI.create(origin.replaceFirst("http", "ws") + "/upgrade"), URI.create(origin + "/authority"),
                Map.of("heartbeat", URI.create(origin + "/heartbeat"), "outcomes", URI.create(origin + "/outcomes"), "rotate", URI.create(origin + "/rotate"),
                        "retire", URI.create(origin + "/retire"), "deregister", URI.create(origin + "/deregister")), "https", List.of("request-response"), 3600000, 30000, 200, 30000);
        return new ProviderControlConfiguration(routes, List.of(new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL,
                "provider_fixture", ProviderCrypto.generate().getPublic(), 0, 9007199254740991L)), new ProviderControlConfiguration.ReportingSeed(1, 0, "serving"));
    }
    static void seed(ProviderStateStore store, String origin) throws Exception {
        var root = new JsonObject(); root.addProperty("provider", origin); root.addProperty("sequence", 17);
        var pair = ProviderCrypto.generate(); root.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded())); root.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic()));
        var registration = new JsonObject(); registration.addProperty("provider", origin); registration.addProperty("profile", "nxs-admission-v1");
        registration.addProperty("instanceId", "fixture_host"); registration.addProperty("registrationId", "fixture_registration");
        registration.addProperty("keyId", "fixture_machine_key"); registration.addProperty("leaseGeneration", 1);
        root.add("registration", registration); root.add("ticketKeys", new JsonArray()); store.write(root);
    }
    @Test void migrationMovesSoleIdentityAndPreservesSequence(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            seed(store, "https://provider.example");
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) {
                assertEquals(17, state.initial.lastSequence()); assertEquals("fixture_machine_key", state.initial.currentKey().keyId());
                var saved = store.read(); assertFalse(saved.has("privateKey")); assertFalse(saved.has("publicKeyJwk")); assertFalse(saved.has("sequence"));
                assertFalse(saved.getAsJsonObject("registration").has("keyId")); assertEquals("nethernet-control-v1", saved.get("controlMode").getAsString());
            }
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) { assertEquals(17, state.initial.lastSequence()); }
        }
    }
    @Test void interruptedPristineImportCanCompleteButCannotReplaceAdvancedJournal(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            seed(store, "https://provider.example"); var cfg = config("https://provider.example");
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, cfg, value -> { throw new IOException("injected root fsync failure"); }));
            assertTrue(store.read().has("privateKey"));
            try (var state = ControlledProviderState.open(store, cfg)) { assertEquals(17, state.initial.lastSequence()); }
        }
        Path other = directory.resolve("other");
        try (var store = new ProviderStateStore(other)) {
            seed(store, "https://provider.example"); var cfg = config("https://provider.example");
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, cfg, value -> { throw new IOException("injected root failure"); }));
            try (var journal = new FileControlClientJournal(other.resolve("control-session"))) {
                var initial = journal.read().orElseThrow(); journal.commit(new ControlClientJournal.Snapshot(initial.subject(), initial.currentKey(), initial.writer(), 18, null, null, null));
            }
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, cfg));
        }
    }
    @Test void noImplicitEnrollmentOrLegacyRotationRecovery(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, config("https://provider.example")));
            seed(store, "https://provider.example"); var root = store.read(); root.addProperty("pendingPrivateKey", "unresolved"); store.write(root);
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, config("https://provider.example")));
            assertFalse(Files.exists(directory.resolve("control-session/provider-state.json")));
        }
    }
    @Test void originalApplicationNumbersAndDuplicateFieldsAreStrict() {
        for (String number : List.of("1.5", "1e0", "18446744073709551616")) {
            var value = ControlledProviderJson.parse("{\"revision\":" + number + "}", 200);
            assertThrows(IllegalArgumentException.class, () -> ControlledProviderJson.number(value, "revision"));
        }
        assertThrows(IllegalArgumentException.class, () -> ControlledProviderJson.parse("{\"revision\":1,\"revision\":2}", 200));
    }
}
