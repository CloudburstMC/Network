package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledDiagnosticApplicationTest {
    static ProviderControlConfiguration config() throws Exception {
        var base = ControlledNativeOwnerApplicationTest.config();
        return new ProviderControlConfiguration(base.routes(), base.providerKeys(), base.migrationSeed(), base.nativeOwnership(),
                ProviderControlConfiguration.CandidatePublication.DISABLED, ProviderControlConfiguration.Diagnostics.ENABLED);
    }
    static final class Native extends ControlledNativeOwnerApplicationTest.Native {
        final AtomicLong now; DiagnosticAdmission.Installation diagnostic; DiagnosticAdmission.Policy installed;
        CompletableFuture<Void> admission = CompletableFuture.completedFuture(null);
        Runnable afterInstall = () -> {}; int installs, withdrawals; boolean reject;
        Native(ControlledProviderState storage, AtomicLong now, int identity) { super(storage, identity, false); this.now = now; }
        @Override public boolean supportsDiagnosticAdmission() { return true; }
        @Override public CompletionStage<DiagnosticAdmission.Installation> installDiagnosticPolicy(DiagnosticAdmission.Policy policy, Runnable guard) {
            return admission.thenApply(ignored -> {
                guard.run(); if (reject) throw new IllegalStateException("diagnostic fixture rejected");
                long captured = material; String key = delegate.keys.get(delegate.keys.size() - 1).keyId();
                var reference = new AtomicReference<DiagnosticAdmission.Installation>();
                var handle = new DiagnosticAdmission.Installation(policy.binding(), () -> {
                    if (diagnostic != reference.get() || retired || delegate.closed || material != captured
                            || !key.equals(delegate.keys.get(delegate.keys.size() - 1).keyId()) || now.get() >= policy.expiresAt()
                            || policy.endpoints().stream().anyMatch(e -> now.get() >= e.expiresAt())) throw new IllegalStateException("fixture native retired");
                }); reference.set(handle); diagnostic = handle; installed = policy; installs++; afterInstall.run(); return handle;
            });
        }
        @Override public Optional<DiagnosticAdmission.Installation> captureDiagnosticInstallation() {
            if (diagnostic == null) return Optional.empty();
            try { diagnostic.requireCurrent(); return Optional.of(diagnostic); } catch (RuntimeException invalid) { return Optional.empty(); }
        }
        @Override public CompletionStage<Boolean> withdrawDiagnosticPolicy(DiagnosticAdmission.Installation expected) {
            if (diagnostic != expected) return CompletableFuture.completedFuture(false);
            diagnostic = null; withdrawals++; return CompletableFuture.completedFuture(true);
        }
    }
    static final class Harness implements AutoCloseable {
        final ProviderStateStore store; final ControlledProviderState storage; final AtomicLong now = new AtomicLong(1000);
        final ControlledNativeOwnerApplicationTest.Provider provider = new ControlledNativeOwnerApplicationTest.Provider();
        final Native nativeHost; final ControlledProviderApplication app; final List<String> notices = new ArrayList<>();
        Installation document; int profileSequence; boolean noMaterial, rotatePlayer; Consumer<JsonObject> alter = ignored -> {}; Consumer<JsonObject> inspect = ignored -> {};
        Harness(Path path) throws Exception { this(path, (store, value) -> store.write(value)); }
        Harness(Path path, Save save) throws Exception {
            store = new ProviderStateStore(path); ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            storage = ControlledProviderState.open(store, config(), value -> save.write(store, value)); nativeHost = new Native(storage, now, 1);
            app = ControlledNativeOwnerApplicationTest.app(storage, nativeHost, now); app.onDiagnostic(notices::add);
        }
        interface Save { void write(ProviderStateStore store, JsonObject value) throws IOException; }
        Exchange exchange() { app.request(); return new Exchange(this); }
        Exchange sync() throws Exception { var exchange = exchange(); app.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS); return exchange; }
        void ready() throws Exception { sync(); sync(); assertTrue(nativeHost.delegate.enabled); assertNotNull(nativeHost.diagnostic); }
        @Override public void close() throws Exception { app.close(); storage.close(); store.close(); }
    }
    static final class Exchange implements ControlClientIo.Synchronization {
        final Harness h; final ControlledNativeOwnerApplicationTest.Exchange parent;
        byte[] retained; Consumer<JsonObject> beforeResponse = ignored -> {};
        Exchange(Harness h) { this.h = h; parent = new ControlledNativeOwnerApplicationTest.Exchange(h.provider, h.app, h.now, "serving"); }
        public long deadlineMillis() { return parent.deadlineMillis(); }
        public Optional<byte[]> pendingHeartbeat() { return Optional.ofNullable(retained); }
        public void requireCurrent() { parent.requireCurrent(); }
        public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) { throw new AssertionError("unguarded"); }
        public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes, Runnable guard) {
            var body = ControlledProviderJson.parse(new String(bytes, StandardCharsets.UTF_8), 65536); h.inspect.accept(body);
            return parent.heartbeat(bytes, guard).thenApply(result -> {
                retained = null; var response = ControlledProviderJson.parse(new String(result.bodyBytes().orElseThrow(), StandardCharsets.UTF_8), 65536);
                if (body.has("hostProfile")) {
                    String profileRevision = "hpr_diagnostic_" + ++h.profileSequence;
                    parent.delegate.profileRevision = profileRevision; h.provider.revision = profileRevision;
                    response.addProperty("hostProfileRevision", profileRevision);
                    var basis = response.getAsJsonObject("application").get("expectedBasis");
                    if (!basis.isJsonNull()) basis.getAsJsonObject().addProperty("hostProfileRevision", profileRevision);
                }
                if (h.rotatePlayer && body.has("keyRequestId")) {
                    var key = new JsonObject(); key.addProperty("keyId", "A002"); key.addProperty("secret", "new-test-only-player-admission-secret"); response.add("ticketKey", key);
                    var request = new JsonObject(); request.add("id", body.get("keyRequestId")); request.addProperty("keyId", "A002"); response.add("keyRequest", request);
                    parent.delegate.policy = new ControlStateCodec.TicketPolicy("A002", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null), new ControlStateCodec.TicketEpoch("A002", 0, null)));
                }
                JsonObject profile = body.has("hostProfile") ? body.getAsJsonObject("hostProfile") : h.storage.application().getAsJsonObject("profile");
                if (profile != null && h.provider.owner != null && h.provider.revision != null && !response.has("ticketKey") && h.document == null)
                    h.document = document(profile, h.provider.owner, h.provider.revision, h.now.get());
                if (h.rotatePlayer && h.document != null && profile != null && !response.has("ticketKey")
                        && !h.document.binding().hostProfileSha256().equals(CandidateLeaseCodec.profileDigest(CandidateLeaseCodec.readProfile(profile)))) {
                    long revision = h.document.binding().policyRevision() + 1;
                    h.document = mutate(document(profile, h.provider.owner, h.provider.revision, h.now.get()), json -> json.getAsJsonObject("binding").addProperty("policyRevision", revision));
                }
                var request = ControlDiagnosticHeartbeatCodec.decodeRequest(body.get("diagnosticAdmission").toString());
                response.add("diagnosticAdmission", JsonParser.parseString(ControlDiagnosticHeartbeatCodec.encodeResponse(
                        new ControlDiagnosticHeartbeatCodec.Response(h.noMaterial ? null : h.document, request.installed()))));
                h.alter.accept(response); beforeResponse.accept(response);
                return ControlledApplicationResultFixture.delivered(response.toString(), () -> { requireCurrent(); guard.run(); });
            });
        }
        public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { throw new AssertionError("unguarded"); }
        public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis, Runnable guard) { return parent.applied(basis, guard); }
    }
    static Installation document(JsonObject profileJson, CandidateLeaseCodec.NativeOwner owner, String revision, long now) {
        var profile = CandidateLeaseCodec.readProfile(profileJson); String zero = "A".repeat(43);
        var binding = new Binding(ControlledNativeOwnerApplicationTest.ORIGIN, "fixture_host", "authority:1", 1, owner.epoch(), owner.nativeIncarnation(),
                revision, CandidateLeaseCodec.profileDigest(profile), profile.dtlsFingerprint().substring(8).replace(":", "").toLowerCase(Locale.ROOT), 1, zero);
        var endpoints = profile.candidates().stream().map(c -> new Endpoint(4, "0".repeat(24) + "08080808", c.port(), 1, "host", now + 300000)).toList();
        var key = new Epoch("D001", "public-test-only-diagnostic-epoch-secret", now, now + 300000);
        var catalog = new AnswerCatalog(binding.providerOrigin(), now, now + 300000, List.of(new AnswerKey("provider-diagnostic", "answer:1", "04" + "ab".repeat(96), now, now + 300000)));
        return hashed(new Installation(binding, now, now + 300000, "D001", List.of(key), endpoints, catalog));
    }
    static Installation hashed(Installation doc) {
        var json = JsonParser.parseString(encodeInstallation(doc)).getAsJsonObject();
        json.getAsJsonObject("binding").addProperty("installationSha256", installationDigest(doc)); return decodeInstallation(json.toString());
    }
    static Installation mutate(Installation doc, Consumer<JsonObject> change) {
        var json = JsonParser.parseString(encodeInstallation(doc)).getAsJsonObject(); change.accept(json); return hashed(decodeInstallation(json.toString()));
    }
    @Test void nativeCaptureAndProtectedSavePrecedeLaterAckWithoutPlayerChurn(@TempDir Path path) throws Exception {
        var saved = new AtomicBoolean(); var ref = new AtomicReference<Harness>();
        try (var h = new Harness(path, (store, root) -> {
            if (root.getAsJsonObject("controlApplication").has("diagnosticInstallation")) {
                assertNotNull(ref.get().nativeHost.captureDiagnosticInstallation().orElse(null)); saved.set(true);
            }
            store.write(root);
        })) {
            ref.set(h); var acked = new AtomicInteger();
            h.inspect = body -> { var request = ControlDiagnosticHeartbeatCodec.decodeRequest(body.get("diagnosticAdmission").toString());
                if (request.installed() != null) { assertTrue(saved.get()); assertEquals(h.document.binding(), request.installed().binding()); acked.incrementAndGet(); } };
            h.ready(); assertTrue(acked.get() > 0); assertEquals(1, h.nativeHost.installs);
            assertEquals(h.document, decodeInstallation(h.storage.application().get("diagnosticInstallation").toString()));
            assertTrue(h.app.lastResponse().getAsJsonObject("diagnosticAdmission").get("expected").isJsonNull());
            assertFalse(h.app.lastResponse().toString().contains(h.document.keys().get(0).secret()));
            int playerInstalls = h.nativeHost.delegate.installs, commits = h.nativeHost.delegate.commits;
            h.sync(); assertEquals(playerInstalls, h.nativeHost.delegate.installs); assertEquals(commits, h.nativeHost.delegate.commits);
        }
    }
    @Test void nullReceiptMaterialNeitherWithdrawsNorRenewsLiveAuthority(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); var handle = h.nativeHost.diagnostic; var stored = h.storage.application().get("diagnosticInstallation");
            h.noMaterial = true; h.alter = response -> response.getAsJsonObject("diagnosticAdmission").add("accepted", JsonNull.INSTANCE);
            h.sync(); assertSame(handle, h.nativeHost.diagnostic); assertEquals(stored, h.storage.application().get("diagnosticInstallation"));
            assertEquals(0, h.nativeHost.withdrawals); h.now.set(h.document.expiresAt());
            var next = h.sync(); assertTrue(next.parent.delegate.bodies.get(0).getAsJsonObject("diagnosticAdmission").get("installed").isJsonNull());
        }
    }
    @Test void explicitEmptyReplacementIsInstalledAndAcknowledgedOnNextPass(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); var old = h.nativeHost.diagnostic; int playerInstalls = h.nativeHost.delegate.installs;
            h.document = mutate(h.document, json -> { json.getAsJsonObject("binding").addProperty("policyRevision", 2); json.add("endpoints", new JsonArray()); });
            h.sync(); assertNotSame(old, h.nativeHost.diagnostic); assertTrue(h.nativeHost.installed.endpoints().isEmpty());
            var next = h.sync(); var ack = ControlDiagnosticHeartbeatCodec.decodeRequest(next.parent.delegate.bodies.get(0).get("diagnosticAdmission").toString()).installed();
            assertEquals(h.document.binding(), ack.binding()); assertEquals(playerInstalls, h.nativeHost.delegate.installs);
        }
    }
    @Test void malformedOptionalDiagnosticSliceDoesNotDrainHealthyPlayerApplication(@TempDir Path path) throws Exception {
        for (String malformed : List.of("null", "[]", "{\"version\":2,\"expected\":null,\"accepted\":null}",
                "{\"version\":1,\"expected\":{\"secret\":\"unusable-private-material\"},\"accepted\":null}")) {
            try (var h = new Harness(path.resolve(Integer.toString(malformed.hashCode())))) {
                h.ready(); var original = h.nativeHost.diagnostic;
                h.alter = response -> response.add("diagnosticAdmission", JsonParser.parseString(malformed));
                var exchange = h.sync();
                assertNotNull(exchange.parent.delegate.applied); assertTrue(h.nativeHost.delegate.enabled);
                assertSame(original, h.nativeHost.diagnostic);
                assertFalse(h.app.lastResponse().has("diagnosticAdmission"));
                assertFalse(h.app.lastResponse().toString().contains("unusable-private-material"));
                assertTrue(h.notices.contains("diagnostic_installation_unavailable"));
            }
        }
    }
    @Test void nativeRejectionDoesNotBlockPlayerReadinessOrSaveClaim(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.nativeHost.reject = true; var exchange = h.sync();
            assertNotNull(exchange.parent.delegate.applied); assertTrue(h.nativeHost.delegate.enabled);
            assertFalse(h.storage.application().has("diagnosticInstallation"));
            assertTrue(exchange.parent.delegate.bodies.stream().allMatch(body -> body.getAsJsonObject("diagnosticAdmission").get("installed").isJsonNull()));
            assertTrue(h.notices.contains("diagnostic_installation_unavailable"));
        }
    }
    @Test void saveFailureWithdrawsExactInstalledHandleAndNeverAcknowledges(@TempDir Path path) throws Exception {
        try (var h = new Harness(path, (store, root) -> { if (root.getAsJsonObject("controlApplication").has("diagnosticInstallation")) throw new IOException("injected save failure"); store.write(root); })) {
            var exchange = h.exchange(); assertThrows(CompletionException.class, () -> h.app.synchronize(exchange).toCompletableFuture().join());
            assertNull(h.nativeHost.diagnostic); assertEquals(1, h.nativeHost.withdrawals);
            assertFalse(h.storage.application().has("diagnosticInstallation"));
            assertTrue(exchange.parent.delegate.bodies.stream().allMatch(body -> body.getAsJsonObject("diagnosticAdmission").get("installed").isJsonNull()));
        }
    }
    @Test void staleDeliveryBeforeNativeCompletionCannotSaveOrAck(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.nativeHost.admission = new CompletableFuture<>(); var exchange = h.exchange();
            var pending = h.app.synchronize(exchange).toCompletableFuture(); assertFalse(pending.isDone());
            exchange.parent.delegate.current = false; h.nativeHost.admission.complete(null);
            assertThrows(CompletionException.class, pending::join); assertEquals(0, h.nativeHost.installs);
            assertFalse(h.storage.application().has("diagnosticInstallation"));
        }
    }
    @Test void remapDuringDurableSaveRetainsOnlyHistoricalBytesAndWithdrawsItsHandle(@TempDir Path path) throws Exception {
        var ref = new AtomicReference<Harness>(); var fired = new AtomicBoolean();
        try (var h = new Harness(path, (store, root) -> {
            store.write(root);
            if (root.getAsJsonObject("controlApplication").has("diagnosticInstallation") && fired.compareAndSet(false, true)) ref.get().nativeHost.remap();
        })) {
            ref.set(h); var exchange = h.exchange();
            assertThrows(CompletionException.class, () -> h.app.synchronize(exchange).toCompletableFuture().join());
            assertTrue(h.storage.application().has("diagnosticInstallation"), "Historical durable bytes may survive a post-save retirement");
            assertNull(h.nativeHost.diagnostic); assertEquals(1, h.nativeHost.withdrawals);
            assertTrue(exchange.parent.delegate.bodies.stream().allMatch(body -> body.getAsJsonObject("diagnosticAdmission").get("installed").isJsonNull()));
        }
    }
    @Test void lateFailedContinuationCannotWithdrawAnotherNativeSuccessor(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            var successor = new AtomicReference<DiagnosticAdmission.Installation>();
            h.nativeHost.afterInstall = () -> {
                var replacement = new DiagnosticAdmission.Installation(h.nativeHost.installed.binding(), () -> {});
                successor.set(replacement); h.nativeHost.diagnostic = replacement;
            };
            var exchange = h.sync(); assertNotNull(exchange.parent.delegate.applied);
            assertSame(successor.get(), h.nativeHost.diagnostic); assertEquals(0, h.nativeHost.withdrawals);
            assertFalse(h.storage.application().has("diagnosticInstallation"));
        }
    }
    @Test void sameTupleShorterExpiryReplacesOnlyDiagnosticStateAndKeepsOriginalDates(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); int players = h.nativeHost.delegate.installs; long started = h.document.notBefore();
            h.now.addAndGet(1000); h.document = mutate(h.document, json -> {
                json.getAsJsonObject("binding").addProperty("policyRevision", 2);
                json.getAsJsonArray("endpoints").get(0).getAsJsonObject().addProperty("expiresAt", started + 100000);
            });
            h.sync(); assertEquals(2, h.nativeHost.installs); assertEquals(players, h.nativeHost.delegate.installs);
            assertEquals(started, h.nativeHost.installed.notBefore()); assertEquals(started + 100000, h.nativeHost.installed.endpoints().get(0).expiresAt());
            var next = h.sync(); assertEquals(h.document.binding(), ControlDiagnosticHeartbeatCodec.decodeRequest(next.parent.delegate.bodies.get(0).get("diagnosticAdmission").toString()).installed().binding());
        }
    }
    @Test void playerKeyProfileRebindDoesNotWithdrawDiagnosticMembershipBeforeAtomicReplacement(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); var originalBinding = h.nativeHost.installed.binding(); long originalExpiry = h.nativeHost.installed.expiresAt();
            h.rotatePlayer = true; h.app.requestKey();
            var exchange = h.sync(); assertNotNull(exchange.parent.delegate.applied);
            assertEquals("A002", h.nativeHost.delegate.keys.get(h.nativeHost.delegate.keys.size() - 1).keyId());
            assertNotEquals(originalBinding.hostProfileSha256(), h.nativeHost.installed.binding().hostProfileSha256());
            assertEquals(2, h.nativeHost.installs); assertEquals(0, h.nativeHost.withdrawals,
                    "Pre-withdrawal would discard already admitted diagnostics which native replacement preserves");
            assertEquals(301000, originalExpiry);
            assertNotEquals(originalBinding.hostProfileRevision(), h.nativeHost.installed.binding().hostProfileRevision());
        }
    }
    @Test void lostRetainedAckRequiresStrongCancellationButFreshNullClaimIsSafe(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); var prior = h.sync(); byte[] original = prior.parent.delegate.originalBodies.get(0);
            var intent = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 99, "diagnostic_retained_fixture", original);
            assertTrue(ControlledProviderApplication.requiresNativeCancellation(intent, original));
            h.nativeHost.diagnostic = null; var retry = h.exchange(); retry.retained = original;
            var error = assertThrows(CompletionException.class, () -> h.app.synchronize(retry).toCompletableFuture().join());
            assertInstanceOf(ControlClientIo.ReconciliationRequired.class, error.getCause()); assertTrue(retry.parent.delegate.bodies.isEmpty());
        }
    }
    @Test void exactRetainedAckKeepsOriginalBytesAndReplacementReleasesOnlyDeliveredClaim(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); byte[] original = h.sync().parent.delegate.originalBodies.get(0);
            h.document = mutate(h.document, json -> json.getAsJsonObject("binding").addProperty("policyRevision", 2));
            var retry = h.exchange(); retry.retained = original; h.app.synchronize(retry).toCompletableFuture().join();
            assertArrayEquals(original, retry.parent.delegate.originalBodies.get(0)); assertEquals(2, h.nativeHost.installs);
            assertNotNull(retry.parent.delegate.applied);
        }
    }
    @Test void restartedApplicationHasNoLiveAckDespiteStoredMaterial(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.ready(); var next = ControlledNativeOwnerApplicationTest.app(h.storage, h.nativeHost, h.now);
            var prepared = new ControlledDiagnosticApplication(h.storage, h.nativeHost, Runnable::run, h.now::get,
                    h.storage::application, ignored -> {}, () -> h.provider.owner, () -> {}, ignored -> {}).prepare();
            assertNull(prepared.request().installed());
            byte[] retained = h.sync().parent.delegate.originalBodies.get(0);
            var exchange = new Exchange(h); // A separate app cannot attach the old diagnostic claim.
            exchange.retained = retained;
            assertThrows(CompletionException.class, () -> next.synchronize(exchange).toCompletableFuture().join());
        }
    }
    @Test void authorityOwnerProfileDigestAndPolicyRollbackAreRefusedWithoutPlayerChurn(@TempDir Path path) throws Exception {
        for (String field : List.of("authorityIncarnation", "hostId", "generation", "nativeOwnerEpoch", "hostProfileRevision", "hostProfileSha256", "hostFingerprintHex", "policyRevision"))
            try (var h = new Harness(path.resolve(field))) {
                h.ready(); var saved = h.storage.application().get("diagnosticInstallation"); var handle = h.nativeHost.diagnostic;
                h.document = mutate(h.document, json -> {
                    var b = json.getAsJsonObject("binding"); b.addProperty("policyRevision", 2);
                    switch (field) {
                        case "generation", "nativeOwnerEpoch" -> b.addProperty(field, 2);
                        case "policyRevision" -> b.addProperty(field, 1);
                        case "hostProfileSha256" -> b.addProperty(field, "A".repeat(43));
                        case "hostFingerprintHex" -> b.addProperty(field, "bb".repeat(32));
                        default -> b.addProperty(field, b.get(field).getAsString() + "x");
                    }
                    if (field.equals("policyRevision")) json.getAsJsonArray("keys").get(0).getAsJsonObject().addProperty("secret", "changed-test-only-diagnostic-epoch-secret");
                });
                var exchange = h.sync(); assertNotNull(exchange.parent.delegate.applied); assertTrue(h.nativeHost.delegate.enabled);
                assertSame(handle, h.nativeHost.diagnostic); assertEquals(saved, h.storage.application().get("diagnosticInstallation"));
            }
    }
}
