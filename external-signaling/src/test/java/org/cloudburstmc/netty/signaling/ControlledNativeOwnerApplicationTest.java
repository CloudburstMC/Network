package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ControlledNativeOwnerApplicationTest {
    static final String ORIGIN = ControlledProviderApplicationTest.ORIGIN;
    static ProviderControlConfiguration config() throws Exception {
        var base = ControlledProviderStateTest.config(ORIGIN);
        return new ProviderControlConfiguration(base.routes(), base.providerKeys(), base.migrationSeed(), ProviderControlConfiguration.NativeOwnership.ISSUED);
    }
    static final class Native implements ProviderTransport {
        final ControlledProviderApplicationTest.Native delegate;
        final String incarnation; long material; boolean retired, empty;
        Native(ControlledProviderState state, int identity, boolean empty) {
            delegate = new ControlledProviderApplicationTest.Native(Runnable::run, state);
            incarnation = String.format("%032x", identity); this.empty = empty;
        }
        void remap() { material++; }
        @Override public boolean supportsNativeIdentityCapture() { return true; }
        @Override public NativeIdentitySnapshot captureNativeIdentity() { return new NativeIdentitySnapshot(incarnation, () -> { if (retired || delegate.closed) throw new IllegalStateException("retired identity"); }); }
        @Override public boolean supportsAdmissionStaging() { return true; }
        @Override public AdmissionUpdate beginAdmissionUpdate(long deadline) { return delegate.beginAdmissionUpdate(deadline); }
        @Override public CompletionStage<Void> installTicketKeys(AdmissionUpdate token, List<TicketKey> keys) { return delegate.installTicketKeys(token, keys); }
        @Override public CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate token, Runnable current) { return delegate.commitAdmissionUpdate(token, current); }
        @Override public CompletionStage<JsonObject> hostProfile() {
            var candidates = empty ? List.<CandidateLeaseCodec.Candidate>of() : List.of(new CandidateLeaseCodec.Candidate("8.8.8.8", 19132 + (int) (material % 2), 1, "host", 100, "udp", "host"));
            var profile = new CandidateLeaseCodec.Profile(candidates, CandidateLeaseCodec.ADMISSION_CAPABILITY, incarnation,
                    delegate.keys.get(delegate.keys.size()-1).keyId(), "sha-256 " + "AA:".repeat(31) + "AA", 262144, 5000);
            return CompletableFuture.completedFuture(JsonParser.parseString(CandidateLeaseCodec.encodeProfile(profile)).getAsJsonObject());
        }
        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            long captured = material; var lifetime = captureNativeIdentity();
            return hostProfile().thenApply(value -> new HostProfileSnapshot(value, () -> { lifetime.requireCurrent(); if (captured != material) throw new IllegalStateException("retired material"); }));
        }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { return delegate.installTicketKeys(keys); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return delegate.applyState(state); }
        @Override public List<JsonObject> pollEvents() { return List.of(); }
        @Override public CompletionStage<Void> drain() { retired = true; return delegate.drain(); }
        @Override public CompletionStage<Void> close() { retired = true; return delegate.close(); }
    }
    static final class Provider {
        CandidateLeaseCodec.NativeOwner owner; long sequence = 17; String revision, accepted;
        byte[] original; ControlLifecycleCodec.Intent intent; ControlLifecycleCodec.Receipt receipt;
        int claims; Runnable beforeAck = () -> {}; boolean receiptOnly;
        CompletionStage<Void> commit(ControlledProviderApplication app, byte[] bytes) {
            var claim = ControlledNativeOwner.claim(bytes); if (claim == null) return CompletableFuture.completedFuture(null);
            assertFalse(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().has("keyRequestId"));
            assertEquals(owner == null ? 0 : owner.epoch(), claim.claim().expectedEpoch());
            owner = claim.issued(); claims++; original = bytes.clone();
            intent = ControlLifecycleCodec.intent(ORIGIN, "heartbeat", "fixture_host", 1, ++sequence, "owner_intent_fixture_" + sequence, bytes);
            receipt = new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(intent), "heartbeat", "fixture_host", 1, sequence, intent.idempotencyKey(), "committed", 1000L, 1L, null);
            beforeAck.run(); return app.acknowledgeNativeOwner(intent, bytes, receipt);
        }
    }
    static final class Exchange implements ControlClientIo.Synchronization {
        final ControlledProviderApplicationTest.Exchange delegate;
        final Provider provider; final ControlledProviderApplication app;
        Exchange(Provider provider, ControlledProviderApplication app, AtomicLong now, String state) {
            this.provider = provider; this.app = app; delegate = new ControlledProviderApplicationTest.Exchange(Runnable::run, now, state);
            delegate.profileRevision = provider.revision; delegate.acceptedBasisSha256 = provider.accepted;
        }
        @Override public Optional<byte[]> pendingHeartbeat() { return Optional.empty(); }
        @Override public long deadlineMillis() { return delegate.deadlineMillis(); }
        @Override public void requireCurrent() { delegate.requireCurrent(); }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes, Runnable guard) {
            guard.run(); return delegate.heartbeat(bytes).thenCompose(result -> {
                var response = JsonParser.parseString(new String(result.bodyBytes().orElseThrow(), StandardCharsets.UTF_8)).getAsJsonObject();
                return provider.commit(app, bytes).thenApply(ignored -> {
                    provider.revision = delegate.profileRevision; provider.accepted = delegate.acceptedBasisSha256;
                    response.add("nativeOwner", provider.owner == null ? JsonNull.INSTANCE : JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwner(provider.owner)));
                    if (provider.receiptOnly && ControlledNativeOwner.claim(bytes) != null) return ControlledApplicationResultFixture.reconciled(provider.receipt);
                    return ControlledApplicationResultFixture.delivered(response.toString(), () -> { requireCurrent(); guard.run(); });
                });
            });
        }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) { throw new AssertionError("unguarded"); }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis value, Runnable guard) { guard.run(); return delegate.applied(value); }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis value) { throw new AssertionError("unguarded"); }
    }
    static ControlledProviderApplication app(ControlledProviderState state, Native transport, AtomicLong now) {
        return new ControlledProviderApplication(state, transport, Runnable::run, now::get, () -> null,
                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
    }
    static Exchange synchronize(Provider provider, ControlledProviderApplication app, AtomicLong now, String target) throws Exception {
        var exchange = new Exchange(provider, app, now, target);
        app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS); return exchange;
    }
    @Test void adoptionIsSavedBeforeServingAndSurvivesMaterialAndControlPassChanges(@TempDir Path directory) throws Exception {
        for (boolean empty : List.of(false, true)) try (var store = new ProviderStateStore(directory.resolve("empty-" + empty))) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var state = ControlledProviderState.open(store, config())) {
                var now = new AtomicLong(1000); var nativeHost = new Native(state, 1, empty); var app = app(state, nativeHost, now); var provider = new Provider();
                nativeHost.delegate.afterCommit = () -> assertTrue(storeValue(store).getAsJsonObject("controlApplication").has("nativeOwnerReceipt"));
                var first = synchronize(provider, app, now, "serving");
                assertEquals(1, provider.claims); assertNotNull(first.delegate.applied); assertTrue(nativeHost.delegate.enabled);
                assertEquals(!empty, first.delegate.bodies.get(first.delegate.bodies.size()-1).get("acceptingPlayers").getAsBoolean());
                var owner = provider.owner; nativeHost.remap(); app.invalidate(); app.request();
                var refresh = synchronize(provider, app, now, "serving");
                assertNotNull(refresh.delegate.applied); assertEquals(owner, provider.owner); assertEquals(1, provider.claims);
                assertTrue(refresh.delegate.bodies.stream().noneMatch(body -> body.has("nativeOwnerClaim")));
            }
        }
    }
    @Test void committedReceiptOnlyCanAttachOriginalLiveCaptureButRestartMustClaimNextEpoch(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN); var provider = new Provider(); provider.receiptOnly = true; var now = new AtomicLong(1000);
            try (var state = ControlledProviderState.open(store, config())) {
                var nativeHost = new Native(state, 1, false); var app = app(state, nativeHost, now);
                synchronize(provider, app, now, "serving"); assertEquals(1, provider.claims); assertTrue(nativeHost.delegate.enabled);
            }
            try (var state = ControlledProviderState.open(store, config())) {
                var replacement = new Native(state, 2, false); var app = app(state, replacement, now);
                app.acknowledgeNativeOwner(provider.intent, provider.original, provider.receipt).toCompletableFuture().join();
                assertFalse(replacement.delegate.enabled);
                var refreshed = synchronize(provider, app, now, "serving");
                assertEquals(2, provider.claims); assertEquals(2, provider.owner.epoch()); assertEquals(replacement.incarnation, provider.owner.nativeIncarnation());
                assertFalse(refreshed.delegate.bodies.get(0).has("hostProfile"), "first discover provider owner; never adopt historical tuple");
            }
        }
    }
    @Test void originalCaptureRetirementBeforeOrDuringRootSaveIsHistoricalOnly(@TempDir Path directory) throws Exception {
        for (String race : List.of("before-save", "after-save", "aba", "close")) try (var store = new ProviderStateStore(directory.resolve(race))) {
            ControlledProviderStateTest.seed(store, ORIGIN); var nativeRef = new AtomicReference<Native>(); var fired = new AtomicBoolean();
            try (var state = ControlledProviderState.open(store, config(), root -> {
                store.write(root);
                if (race.equals("after-save") && root.getAsJsonObject("controlApplication").has("nativeOwnerReceipt") && fired.compareAndSet(false, true)) nativeRef.get().remap();
            })) {
                var now = new AtomicLong(1000); var nativeHost = new Native(state, 1, false); nativeRef.set(nativeHost);
                var app = app(state, nativeHost, now); var provider = new Provider();
                provider.beforeAck = () -> { if (!race.equals("after-save") && fired.compareAndSet(false, true)) {
                    if (race.equals("close")) nativeHost.retired = true;
                    else { nativeHost.remap(); if (race.equals("aba")) nativeHost.remap(); }
                }};
                assertThrows(ExecutionException.class, () -> synchronize(provider, app, now, "serving"));
                assertTrue(state.application().has("nativeOwnerReceipt")); assertFalse(nativeHost.delegate.enabled); assertEquals(0, nativeHost.delegate.commits);
                if (!race.equals("close")) { synchronize(provider, app, now, "serving"); assertEquals(2, provider.claims); assertEquals(2, provider.owner.epoch()); }
            }
        }
    }
    @Test void failedOwnerSaveCannotEnableAndExactRetryIsIdempotent(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN); var reject = new AtomicBoolean(true); var writes = new AtomicInteger();
            try (var state = ControlledProviderState.open(store, config(), root -> {
                if (root.getAsJsonObject("controlApplication").has("nativeOwnerReceipt")) { if (reject.get()) throw new IOException("owner fsync failure"); writes.incrementAndGet(); } store.write(root);
            })) {
                var now = new AtomicLong(1000); var host = new Native(state, 1, false); var app = app(state, host, now); var provider = new Provider();
                assertThrows(ExecutionException.class, () -> synchronize(provider, app, now, "serving"));
                assertFalse(host.delegate.enabled); assertFalse(state.application().has("nativeOwnerReceipt"));
                reject.set(false); app.acknowledgeNativeOwner(provider.intent, provider.original, provider.receipt).toCompletableFuture().join();
                int saved = writes.get(); app.acknowledgeNativeOwner(provider.intent, provider.original, provider.receipt).toCompletableFuture().join(); assertEquals(saved, writes.get());
                synchronize(provider, app, now, "serving"); assertEquals(1, provider.claims);
            }
        }
    }
    @Test void providerCannotRewriteAnIssuedTupleAtTheSameEpoch(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var state = ControlledProviderState.open(store, config())) {
                var now = new AtomicLong(1000); var host = new Native(state, 1, false); var app = app(state, host, now); var provider = new Provider();
                synchronize(provider, app, now, "serving");
                provider.owner = new CandidateLeaseCodec.NativeOwner(provider.owner.epoch(), provider.owner.nativeIncarnation(), "different_claim_fixture_01");
                app.request(); var error = assertThrows(ExecutionException.class, () -> synchronize(provider, app, now, "serving"));
                assertTrue(error.getCause().getMessage().contains("tuple changed")); assertFalse(host.delegate.enabled); assertEquals(1, provider.claims);
            }
        }
    }
    @Test void newListenerWithDrainingReportingFloorCanAdoptWhenProviderRequestsServing(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN); var config = config();
            var draining = new ProviderControlConfiguration(config.routes(), config.providerKeys(), new ProviderControlConfiguration.ReportingSeed(1, 0, "draining"), config.nativeOwnership());
            try (var state = ControlledProviderState.open(store, draining)) {
                var now = new AtomicLong(1000); var host = new Native(state, 1, false); var app = app(state, host, now); var provider = new Provider();
                var exchange = synchronize(provider, app, now, "serving");
                assertEquals(1, provider.claims); assertEquals("serving", exchange.delegate.applied.state()); assertTrue(host.delegate.enabled);
                assertEquals("draining", exchange.delegate.bodies.get(1).get("state").getAsString(), "claim preparation does not claim applied serving state");
            }
        }
    }
    @Test void nonServingAndClosedRefreshNeedsNoOwnerOrKeys(@TempDir Path directory) throws Exception {
        for (String target : List.of("draining", "closed")) try (var store = new ProviderStateStore(directory.resolve(target))) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var state = ControlledProviderState.open(store, config())) {
                var now = new AtomicLong(1000); var host = new Native(state, 1, false); var app = app(state, host, now); var provider = new Provider();
                assertEquals(target, synchronize(provider, app, now, target).delegate.applied.state()); app.request();
                assertEquals(target, synchronize(provider, app, now, target).delegate.applied.state());
                assertEquals(0, provider.claims); assertFalse(host.delegate.enabled); assertTrue(host.delegate.keys.isEmpty());
            }
        }
    }
    static JsonObject storeValue(ProviderStateStore store) { try { return store.read(); } catch (IOException e) { throw new RuntimeException(e); } }
}
