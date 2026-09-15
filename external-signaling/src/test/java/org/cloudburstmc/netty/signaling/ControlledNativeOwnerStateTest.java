package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ControlledNativeOwnerStateTest {
    static byte[] body(long epoch) {
        var profile = new CandidateLeaseCodec.Profile(List.of(), CandidateLeaseCodec.ADMISSION_CAPABILITY, "0".repeat(32), "A001", "sha-256 " + "AA:".repeat(31) + "AA", 262144, 5000);
        var body = new JsonObject(); body.add("hostProfile", JsonParser.parseString(CandidateLeaseCodec.encodeProfile(profile)));
        body.add("nativeOwnerClaim", JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwnerClaim(new CandidateLeaseCodec.NativeOwnerClaim(epoch, "claim_owner_fixture_" + epoch))));
        body.addProperty("acceptingPlayers", false); return (" \n" + body + "\n").getBytes(StandardCharsets.UTF_8);
    }
    static ControlLifecycleCodec.Intent intent(byte[] body, long sequence) { return ControlLifecycleCodec.intent("https://provider.example", "heartbeat", "fixture_host", 1, sequence, "owner_intent_fixture_" + sequence, body); }
    static ControlLifecycleCodec.Receipt receipt(ControlLifecycleCodec.Intent intent, String disposition) {
        return new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(intent), "heartbeat", "fixture_host", 1, intent.sequence(), intent.idempotencyKey(), disposition, 1000L, 1L, null);
    }
    @Test void modePersistsOnlyExplicitlyAndCannotSilentlyDowngrade(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            try (var legacy = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledNativeOwnerApplicationTest.ORIGIN))) { assertFalse(legacy.application().has("nativeOwnership")); }
            try (var enabled = ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config())) { assertEquals("issued-v1", enabled.application().get("nativeOwnership").getAsString()); }
            assertThrows(IOException.class, () -> ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledNativeOwnerApplicationTest.ORIGIN)));
            try (var enabled = ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config())) { assertTrue(enabled.application().has("nativeOwnership")); }
        }
    }
    @Test void originalBytesCommittedReceiptAndSubjectAreRequired(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            try (var state = ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config())) {
                byte[] body = body(0); var intent = intent(body, 18); var committed = receipt(intent, "committed");
                assertTrue(ControlledNativeOwner.requiresAcknowledgement(intent, body)); assertTrue(ControlledProviderApplication.requiresNativeCancellation(intent, body));
                assertThrows(IllegalArgumentException.class, () -> state.acknowledgeNativeOwner(intent, "{}".getBytes(StandardCharsets.UTF_8), committed));
                assertThrows(IllegalArgumentException.class, () -> state.acknowledgeNativeOwner(intent, body, receipt(intent, "cancelled")));
                var foreign = ControlLifecycleCodec.intent("https://other.example", "heartbeat", "fixture_host", 1, 18, intent.idempotencyKey(), body);
                assertThrows(IOException.class, () -> state.acknowledgeNativeOwner(foreign, body, receipt(foreign, "committed")));
                assertFalse(state.application().has("nativeOwnerReceipt")); state.acknowledgeNativeOwner(intent, body, committed);
                var marker = state.application().getAsJsonObject("nativeOwnerReceipt"); assertEquals(intent.payloadSha256(), marker.get("bodyDigest").getAsString());
                assertEquals(1, marker.getAsJsonObject("owner").get("epoch").getAsInt());
            }
        }
    }
    @Test void idempotentHistorySurvivesRestartAndRejectsRollbackOrConflictingSequence(@TempDir Path directory) throws Exception {
        var writes = new AtomicInteger();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN); byte[] body = body(0); var intent = intent(body, 18); var receipt = receipt(intent, "committed");
            try (var state = ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config(), root -> { writes.incrementAndGet(); store.write(root); })) {
                state.acknowledgeNativeOwner(intent, body, receipt); int saved = writes.get(); state.acknowledgeNativeOwner(intent, body, receipt); assertEquals(saved, writes.get());
                var next = state.application(); next.remove("nativeOwnerReceipt"); assertThrows(IOException.class, () -> state.saveApplication(next));
                byte[] conflictBody = body(1); var conflict = intent(conflictBody, 18);
                assertThrows(IOException.class, () -> state.acknowledgeNativeOwner(conflict, conflictBody, receipt(conflict, "committed")));
            }
            try (var state = ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config())) {
                state.acknowledgeNativeOwner(intent, body, receipt);
                var older = intent(body, 17); assertThrows(IOException.class, () -> state.acknowledgeNativeOwner(older, body, receipt(older, "committed")));
                byte[] nextBody = body(1); var next = intent(nextBody, 19); state.acknowledgeNativeOwner(next, nextBody, receipt(next, "committed"));
                assertEquals(2, state.application().getAsJsonObject("nativeOwnerReceipt").getAsJsonObject("owner").get("epoch").getAsInt());
                var malformed = state.application(); malformed.getAsJsonObject("nativeOwnerReceipt").addProperty("extra", true);
                assertThrows(IllegalArgumentException.class, () -> state.saveApplication(malformed));
            }
        }
    }
    @Test void ordinaryReportsRemainOrdinaryAndInvalidClaimsNeverSettleAsOwners() {
        for (String plain : List.of("{\"healthy\":true}", "{\"keyRequestId\":\"existing_request_01\"}", "{\"acceptingPlayers\":false}")) {
            byte[] bytes = plain.getBytes(StandardCharsets.UTF_8); assertFalse(ControlledNativeOwner.requiresAcknowledgement(intent(bytes, 18), bytes));
        }
        for (String incompatible : List.of("keyRequestId", "hostProfileRevision", "candidateLeases")) {
            var value = JsonParser.parseString(new String(body(0), StandardCharsets.UTF_8)).getAsJsonObject(); value.addProperty(incompatible, "invalid");
            assertThrows(IllegalArgumentException.class, () -> ControlledNativeOwner.claim(value.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
