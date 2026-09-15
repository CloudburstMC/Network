package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ControlledCandidateLeaseApplicationTest {
    static ProviderControlConfiguration config() throws Exception {
        var base = ControlledNativeOwnerApplicationTest.config();
        return new ProviderControlConfiguration(base.routes(), base.providerKeys(), base.migrationSeed(), base.nativeOwnership(), ProviderControlConfiguration.CandidatePublication.MAINTAINED);
    }
    static final class Native extends ControlledNativeOwnerApplicationTest.Native {
        final AtomicLong now;
        CandidateLeaseCodec.Observation observation;
        Native(ControlledProviderState state, AtomicLong now) { super(state, 1, true); this.now = now; success(1, 1, 43000); }
        void success(long revision, long sequence, int port) {
            if (observation != null && (observation.mappingRevision() != revision || observation.port() != port)) material++;
            observation = new CandidateLeaseCodec.Observation("ipv4", "08080808", port, 1, revision, sequence, now.get(), now.get() + 269000);
        }
        @Override public boolean supportsMaintainedCandidateLeases() { return true; }
        @Override public CandidateLeaseSnapshot maintainCandidateLeases(boolean allowed) {
            boolean nextEmpty = !allowed || observation == null || observation.expiresAt() <= now.get();
            if (nextEmpty != empty) { empty = nextEmpty; material++; }
            long captured = material; var owned = empty ? List.<CandidateLeaseCodec.Observation>of() : List.of(observation);
            var identity = captureNativeIdentity();
            return new CandidateLeaseSnapshot(Long.toString(material), owned, () -> {
                identity.requireCurrent();
                if (captured != material || owned.stream().anyMatch(value -> value.expiresAt() <= now.get())) throw new IllegalStateException("lease material or expiry");
            });
        }
        @Override public CompletionStage<JsonObject> hostProfile() {
            var candidates = empty ? List.<CandidateLeaseCodec.Candidate>of() : List.of(new CandidateLeaseCodec.Candidate("8.8.8.8", observation.port(), 1, "1", 2130706431, "udp", "srflx"));
            var profile = new CandidateLeaseCodec.Profile(candidates, CandidateLeaseCodec.ADMISSION_CAPABILITY, incarnation,
                    delegate.keys.get(delegate.keys.size() - 1).keyId(), "sha-256 " + "AA:".repeat(31) + "AA", 262144, 5000);
            return CompletableFuture.completedFuture(JsonParser.parseString(CandidateLeaseCodec.encodeProfile(profile)).getAsJsonObject());
        }
    }
    static final class Exchange implements ControlClientIo.Synchronization {
        final ControlledNativeOwnerApplicationTest.Exchange owner;
        Consumer<JsonObject> alter = ignored -> {};
        Runnable beforeDelivery = () -> {}, beforeSend = () -> {};
        byte[] retained, attempted;
        boolean failBeforeSend;
        Exchange(ControlledNativeOwnerApplicationTest.Provider provider, ControlledProviderApplication app, AtomicLong now) {
            owner = new ControlledNativeOwnerApplicationTest.Exchange(provider, app, now, "serving");
        }
        @Override public long deadlineMillis() { return owner.deadlineMillis(); }
        @Override public Optional<byte[]> pendingHeartbeat() { return Optional.ofNullable(retained); }
        @Override public void requireCurrent() { owner.requireCurrent(); }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) { throw new AssertionError("unguarded"); }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes, Runnable guard) {
            beforeSend.run(); guard.run();
            attempted = bytes.clone();
            if (failBeforeSend) return CompletableFuture.failedFuture(new java.io.IOException("uncertain original send"));
            return owner.heartbeat(bytes, guard).thenApply(result -> {
                retained = null;
                var response = JsonParser.parseString(new String(result.bodyBytes().orElseThrow(), StandardCharsets.UTF_8)).getAsJsonObject();
                var body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (body.has("candidateLeases")) response.add("candidateLeaseReceipt", JsonParser.parseString(CandidateLeaseCodec.encodeReceipt(
                        CandidateLeaseCodec.receipt(response.get("hostProfileRevision").getAsString(), CandidateLeaseCodec.decodeLeases(body.get("candidateLeases").toString())))));
                alter.accept(response); beforeDelivery.run();
                return ControlledApplicationResultFixture.delivered(response.toString(), () -> { requireCurrent(); guard.run(); });
            });
        }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { throw new AssertionError("unguarded"); }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis, Runnable guard) { return owner.applied(basis, guard); }
    }
    static final class Harness implements AutoCloseable {
        final ProviderStateStore store; final ControlledProviderState state;
        final AtomicLong now = new AtomicLong(1000);
        final Native nativeHost; final ControlledProviderApplication app;
        final ControlledNativeOwnerApplicationTest.Provider provider = new ControlledNativeOwnerApplicationTest.Provider();
        Harness(Path directory) throws Exception { this(directory, ignored -> {}); }
        Harness(Path directory, Consumer<JsonObject> afterSave) throws Exception {
            store = new ProviderStateStore(directory); ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            state = ControlledProviderState.open(store, config(), root -> { store.write(root); afterSave.accept(root); }); nativeHost = new Native(state, now);
            app = ControlledNativeOwnerApplicationTest.app(state, nativeHost, now);
        }
        Exchange exchange() { app.request(); return new Exchange(provider, app, now); }
        Exchange sync() throws Exception { var exchange = exchange(); app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS); return exchange; }
        void ready() throws Exception { sync(); sync(); assertFalse(nativeHost.empty); assertTrue(nativeHost.delegate.enabled); }
        @Override public void close() throws Exception { state.close(); store.close(); }
    }
    @Test void ownerAdoptionPrecedesReflexivePublicationAndExactReceiptIsSaved(@TempDir Path directory) throws Exception {
        try (var h = new Harness(directory)) {
            var adoption = h.sync();
            assertTrue(adoption.owner.delegate.bodies.stream().filter(body -> body.has("hostProfile")).allMatch(body -> body.getAsJsonObject("hostProfile").getAsJsonArray("candidates").isEmpty()));
            var publication = h.sync();
            assertEquals(1, h.provider.claims); assertTrue(h.nativeHost.delegate.enabled);
            var body = publication.owner.delegate.bodies.get(0); assertEquals("srflx", body.getAsJsonObject("hostProfile").getAsJsonArray("candidates").get(0).getAsJsonObject().get("type").getAsString());
            var receipt = CandidateLeaseCodec.decodeReceipt(h.state.application().get("candidateLeaseReceipt").toString());
            assertTrue(CandidateLeaseCodec.matches(receipt, h.provider.revision, CandidateLeaseCodec.decodeLeases(body.get("candidateLeases").toString())));
        }
    }
    @Test void unchangedAndNewSuccessRenewalDoNotReinstallProfileKeysOrPlayers(@TempDir Path directory) throws Exception {
        try (var h = new Harness(directory)) {
            h.ready(); int installs = h.nativeHost.delegate.installs, commits = h.nativeHost.delegate.commits;
            var before = h.state.application(); String revision = h.provider.revision;
            h.now.addAndGet(1000); var repeated = h.sync();
            assertEquals(before.get("candidateLeaseReceipt"), h.state.application().get("candidateLeaseReceipt"));
            h.nativeHost.success(1, 2, 43000); var renewed = h.sync();
            assertNotEquals(before.get("candidateLeaseReceipt"), h.state.application().get("candidateLeaseReceipt"));
            assertEquals(revision, h.provider.revision); assertEquals(before.get("basis"), h.state.application().get("basis"));
            for (var exchange : List.of(repeated, renewed)) {
                assertEquals(1, exchange.owner.delegate.bodies.size()); var body = exchange.owner.delegate.bodies.get(0);
                assertFalse(body.has("hostProfile")); assertTrue(body.has("hostProfileRevision")); assertTrue(body.has("applicationAck"));
                assertTrue(body.get("acceptingPlayers").getAsBoolean());
            }
            assertEquals(installs, h.nativeHost.delegate.installs); assertEquals(commits, h.nativeHost.delegate.commits);
        }
    }
    @Test void sameMappingNewSuccessDuringAwaitPreservesOriginalBytesAndGuard(@TempDir Path directory) throws Exception {
        try (var h = new Harness(directory)) {
            h.ready(); var original = h.nativeHost.observation; h.now.addAndGet(1000); var exchange = h.exchange();
            exchange.beforeDelivery = () -> { h.nativeHost.success(1, 2, 43000); h.app.maintainCandidates(); };
            h.app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
            var published = CandidateLeaseCodec.decodeLeases(exchange.owner.delegate.bodies.get(0).get("candidateLeases").toString());
            assertEquals(List.of(original), published.observations()); assertTrue(h.nativeHost.delegate.enabled);
        }
    }

    @Test void requestedKeyRotationRebindsOriginalObservationDatesToNewFullProfile(@TempDir Path directory) throws Exception {
        try (var h = new Harness(directory)) {
            h.ready(); var before = h.state.application(); var observation = h.nativeHost.observation;
            h.app.requestKey(); var exchange = h.exchange();
            exchange.alter = response -> {
                var body = exchange.owner.delegate.bodies.get(exchange.owner.delegate.bodies.size() - 1);
                if (!body.has("keyRequestId")) return;
                var key = new JsonObject(); key.addProperty("keyId", "B002"); key.addProperty("secret", ControlledProviderApplicationTest.SECRET); response.add("ticketKey", key);
                var request = new JsonObject(); request.addProperty("id", body.get("keyRequestId").getAsString()); request.addProperty("keyId", "B002"); response.add("keyRequest", request);
                exchange.owner.delegate.policy = new ControlStateCodec.TicketPolicy("B002", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null), new ControlStateCodec.TicketEpoch("B002", 0, null)));
            };
            h.app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(h.nativeHost.delegate.enabled); assertEquals(1, h.provider.claims);
            assertEquals("B002", h.state.application().getAsJsonObject("profile").get("credentialKeyId").getAsString());
            assertNotEquals(before.getAsJsonObject("candidateLeaseReceipt").get("profileSha256"), h.state.application().getAsJsonObject("candidateLeaseReceipt").get("profileSha256"));
            for (var body : exchange.owner.delegate.bodies) if (body.has("candidateLeases"))
                assertEquals(List.of(observation), CandidateLeaseCodec.decodeLeases(body.get("candidateLeases").toString()).observations());
        }
    }
    @Test void remapDuringAwaitOrFinalSendRejectsOriginalPublication(@TempDir Path directory) throws Exception {
        for (boolean finalSend : List.of(false, true)) try (var h = new Harness(directory.resolve(Boolean.toString(finalSend)))) {
            h.ready(); var exchange = h.exchange(); Runnable remap = () -> { h.nativeHost.success(2, 2, 43001); h.app.maintainCandidates(); };
            if (finalSend) exchange.beforeSend = remap; else exchange.beforeDelivery = remap;
            assertThrows(ExecutionException.class, () -> h.app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertFalse(h.nativeHost.delegate.enabled);
            if (finalSend) assertTrue(exchange.owner.delegate.bodies.isEmpty());
        }
    }
    @Test void expiryWithdrawsFinalCandidateAndKeepsIssuedOwner(@TempDir Path directory) throws Exception {
        try (var h = new Harness(directory)) {
            h.ready(); var owner = h.provider.owner; h.now.set(h.nativeHost.observation.expiresAt());
            h.app.maintainCandidates(); var withdrawn = h.sync();
            assertTrue(withdrawn.owner.delegate.bodies.get(0).getAsJsonObject("hostProfile").getAsJsonArray("candidates").isEmpty());
            assertTrue(withdrawn.owner.delegate.bodies.stream().noneMatch(body -> body.get("acceptingPlayers").getAsBoolean()));
            assertEquals(owner, h.provider.owner); assertEquals(0, CandidateLeaseCodec.decodeReceipt(h.state.application().get("candidateLeaseReceipt").toString()).expiresAt());
            assertTrue(h.nativeHost.delegate.enabled, "Empty advertisement leaves the truthful native serving basis intact");
        }
    }
    @Test void missingOrMismatchedReceiptNeverBecomesAcceptedLocalMetadata(@TempDir Path directory) throws Exception {
        for (String mismatch : List.of("missing", "profile", "revision", "digest", "expiry")) try (var h = new Harness(directory.resolve(mismatch))) {
            h.ready(); var original = h.state.application().get("candidateLeaseReceipt"); h.now.addAndGet(1000); h.nativeHost.success(1, 2, 43000);
            var exchange = h.exchange(); exchange.alter = response -> {
                if (mismatch.equals("missing")) response.remove("candidateLeaseReceipt");
                else {
                    var receipt = response.getAsJsonObject("candidateLeaseReceipt");
                    switch (mismatch) {
                        case "profile" -> receipt.addProperty("profileSha256", "A".repeat(43));
                        case "revision" -> receipt.addProperty("hostProfileRevision", "hpr_other_revision");
                        case "digest" -> receipt.addProperty("acceptedSha256", "A".repeat(43));
                        case "expiry" -> receipt.addProperty("expiresAt", receipt.get("expiresAt").getAsLong() - 1);
                    }
                }
            };
            assertThrows(ExecutionException.class, () -> h.app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(original, h.state.application().get("candidateLeaseReceipt")); assertFalse(h.nativeHost.delegate.enabled);
        }
    }

    @Test void retainedNonacceptingFullPublicationRetriesExactBytesOnlyWithOriginalLiveCapture(@TempDir Path directory) throws Exception {
        for (String replacement : List.of("unchanged", "new-success", "remap", "restart")) try (var h = new Harness(directory.resolve(replacement))) {
            h.sync(); // The native owner is attached; the next full publication has no live application ACK yet.
            var first = h.exchange(); first.failBeforeSend = true;
            assertThrows(ExecutionException.class, () -> h.app.synchronize(first).toCompletableFuture().get(3, TimeUnit.SECONDS));
            var original = JsonParser.parseString(new String(first.attempted, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(original.has("hostProfile") && original.has("candidateLeases")); assertFalse(original.get("acceptingPlayers").getAsBoolean());
            if (replacement.equals("new-success")) { h.now.addAndGet(1000); h.nativeHost.success(1, 2, 43000); }
            if (replacement.equals("remap")) h.nativeHost.success(2, 2, 43001);
            var application = replacement.equals("restart") ? ControlledNativeOwnerApplicationTest.app(h.state, new Native(h.state, h.now), h.now) : h.app;
            var retry = new Exchange(h.provider, application, h.now); retry.retained = first.attempted;
            if (replacement.equals("unchanged") || replacement.equals("new-success")) {
                application.synchronize(retry).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertArrayEquals(first.attempted, retry.owner.delegate.originalBodies.get(0)); assertTrue(h.nativeHost.delegate.enabled);
            } else {
                var error = assertThrows(ExecutionException.class, () -> application.synchronize(retry).toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertInstanceOf(ControlClientIo.ReconciliationRequired.class, error.getCause()); assertNull(retry.attempted);
            }
            var intent = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 50,
                    "lease_intent_fixture", first.attempted);
            assertTrue(ControlledProviderApplication.requiresNativeCancellation(intent, first.attempted));
        }
    }

    @Test void revisionOnlyLeaseClaimsRequireStrongCancellationEvenWhenNonaccepting() {
        byte[] body = "{\"hostProfileRevision\":\"hpr_fixture\",\"acceptingPlayers\":false,\"candidateLeases\":{}}".getBytes(StandardCharsets.UTF_8);
        var intent = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 50, "lease_intent_fixture", body);
        assertTrue(ControlledProviderApplication.requiresNativeCancellation(intent, body));
    }

    @Test void remapDuringReceiptSaveCannotAttachItsHistoricalAcceptanceOrReachReady(@TempDir Path directory) throws Exception {
        var saved = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        try (var h = new Harness(directory, root -> saved.get().run())) {
            h.ready(); h.now.addAndGet(1000); h.nativeHost.success(1, 2, 43000);
            saved.set(() -> { saved.set(() -> {}); h.nativeHost.success(2, 3, 43001); h.app.maintainCandidates(); });
            var exchange = h.exchange();
            assertThrows(ExecutionException.class, () -> h.app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertNull(exchange.owner.delegate.applied); assertFalse(h.nativeHost.delegate.enabled);
            var original = CandidateLeaseCodec.decodeLeases(exchange.owner.delegate.bodies.get(0).get("candidateLeases").toString());
            assertTrue(CandidateLeaseCodec.matches(CandidateLeaseCodec.decodeReceipt(h.state.application().get("candidateLeaseReceipt").toString()), h.provider.revision, original),
                    "Durable historical receipt may survive; it cannot replace the retired original live guard");
        }
    }

    @Test void modeCannotDowngradeOrStartWithoutIssuedOwnership(@TempDir Path directory) throws Exception {
        var base = ControlledProviderStateTest.config(ControlledNativeOwnerApplicationTest.ORIGIN);
        assertThrows(IllegalArgumentException.class, () -> new ProviderControlConfiguration(base.routes(), base.providerKeys(), base.migrationSeed(),
                ProviderControlConfiguration.NativeOwnership.DISABLED, ProviderControlConfiguration.CandidatePublication.MAINTAINED));
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            try (var state = ControlledProviderState.open(store, config())) {
                var value = state.application(); value.remove("candidatePublication");
                assertThrows(java.io.IOException.class, () -> state.saveApplication(value));
            }
            assertThrows(java.io.IOException.class, () -> ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config()));
        }
    }
}
