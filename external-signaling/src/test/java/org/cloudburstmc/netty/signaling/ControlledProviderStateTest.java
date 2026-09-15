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
    static JsonObject event(int id) {
        var event = new JsonObject(); event.addProperty("reason", (String) null); event.addProperty("stage", "ticket.data_channels_open"); event.addProperty("ticketId", "ticket-" + id); event.addProperty("occurredAt", "2026-09-15T00:00:00Z"); return event;
    }
    @Test void nativeBurstsSplitIntoWireBatchesWithoutDropping(@TempDir Path directory) throws Exception {
        for (int count : List.of(101, 256)) try (var store = new ProviderStateStore(directory.resolve("batch-" + count))) {
            seed(store, "https://provider.example");
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) {
                state.appendEvents(java.util.stream.IntStream.range(0, count).mapToObj(ControlledProviderStateTest::event).toList());
                int consumed = 0;
                while (!state.outcomeBatch().getAsJsonArray("events").isEmpty()) {
                    var body = state.outcomeBatch(); int n = body.getAsJsonArray("events").size(); assertTrue(n <= 100);
                    assertEquals("ticket-" + consumed, body.getAsJsonArray("events").get(0).getAsJsonObject().get("ticketId").getAsString());
                    acknowledge(state, body, 18 + consumed); consumed += n;
                }
                assertEquals(count, consumed);
            }
        }
    }
    @Test void pollingCapacityRespectsCountAndRootBytes(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            seed(store, "https://provider.example");
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) {
                for (int base = 0; base < 990; base += 250) {
                    int start = base, end = Math.min(990, base + 250);
                    state.appendEvents(java.util.stream.IntStream.range(start, end).mapToObj(ControlledProviderStateTest::event).toList());
                }
                assertEquals(10, state.eventCapacity()); state.appendEvents(java.util.stream.IntStream.range(990, 1000).mapToObj(ControlledProviderStateTest::event).toList());
                assertEquals(0, state.eventCapacity());
            }
        }
        try (var store = new ProviderStateStore(directory.resolve("byte-bound"))) {
            seed(store, "https://provider.example");
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) {
                int count = 0;
                while (state.eventCapacity() > 0) {
                    var batch = new ArrayList<JsonObject>();
                    for (int i = 0; i < state.eventCapacity(); i++) { var e = event(count++); e.addProperty("reason", "r".repeat(850)); batch.add(e); }
                    state.appendEvents(batch);
                }
                assertTrue(count < 1000); assertTrue(store.read().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 262144);
                assertTrue(state.outcomeBatch().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ControlLifecycleCodec.MAX_WS_BODY_BYTES);
            }
        }
    }
    static void acknowledge(ControlledProviderState state, JsonObject body, long sequence) throws IOException {
        byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var intent = ControlLifecycleCodec.intent(state.initial.subject().audience(), "outcomes", state.initial.subject().instanceId(), 1, sequence, "outcome_fixture_id_" + sequence, bytes);
        var receipt = new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(intent), "outcomes", intent.instanceId(), 1, sequence, intent.idempotencyKey(), "committed", 1000L, 1L, null);
        state.acknowledgeOutcomes(intent, bytes, receipt);
    }
    @Test void outcomeRootAcknowledgementIsAtomicAndIdempotentAcrossRestart(@TempDir Path directory) throws Exception {
        var reject = new java.util.concurrent.atomic.AtomicBoolean();
        try (var store = new ProviderStateStore(directory)) {
            seed(store, "https://provider.example"); JsonObject original;
            try (var state = ControlledProviderState.open(store, config("https://provider.example"), value -> {
                if (reject.get()) throw new IOException("injected outcome root fsync failure"); store.write(value);
            })) {
                state.appendEvents(List.of(event(1), event(2))); original = state.outcomeBatch();
                reject.set(true); assertThrows(IOException.class, () -> acknowledge(state, original, 18));
                assertEquals(original, state.outcomeBatch()); assertFalse(store.read().has("controlOutcomeAcknowledgement"));
                reject.set(false); acknowledge(state, original, 18); assertTrue(state.outcomeBatch().getAsJsonArray("events").isEmpty());
                state.appendEvents(List.of(event(3))); acknowledge(state, original, 18);
                assertEquals(1, state.outcomeBatch().getAsJsonArray("events").size());
            }
            try (var state = ControlledProviderState.open(store, config("https://provider.example"))) {
                acknowledge(state, original, 18); assertEquals("ticket-3", state.outcomeBatch().getAsJsonArray("events").get(0).getAsJsonObject().get("ticketId").getAsString());
                assertThrows(IOException.class, () -> acknowledge(state, state.outcomeBatch(), 18));
                acknowledge(state, state.outcomeBatch(), 19); assertTrue(state.outcomeBatch().getAsJsonArray("events").isEmpty());
            }
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
