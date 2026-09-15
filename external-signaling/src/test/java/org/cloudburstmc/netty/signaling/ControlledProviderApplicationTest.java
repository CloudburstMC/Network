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

class ControlledProviderApplicationTest {
    static final String ORIGIN = "https://provider.example";
    static final String SECRET = "a-secret-admission-key-01234567890123456789";
    static final class Native implements ProviderTransport {
        final Executor executor; final ControlledProviderState storage;
        java.util.function.Supplier<CompletionStage<JsonObject>> profileOverride;
        List<TicketKey> keys = List.of(); AdmissionUpdate update; boolean enabled, closed; int commits, installs, profileVersion; Runnable afterCommit = () -> { }; Runnable beforeInstall = () -> { };
        Native(Executor executor, ControlledProviderState storage) { this.executor = executor; this.storage = storage; }
        @Override public boolean supportsAdmissionStaging() { return true; }
        @Override public AdmissionUpdate beginAdmissionUpdate(long deadline) { assertTrue(deadline > System.nanoTime()); enabled = false; return update = new AdmissionUpdate() { }; }
        @Override public CompletionStage<Void> installTicketKeys(AdmissionUpdate token, List<TicketKey> value) {
            assertSame(update, token); return CompletableFuture.runAsync(() -> { beforeInstall.run(); assertFalse(enabled); keys = List.copyOf(value); installs++; }, executor);
        }
        @Override public CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate token, Runnable current) {
            return CompletableFuture.supplyAsync(() -> { current.run(); assertSame(update, token); assertTrue(storage.application().has("basis"), "durable basis precedes native enable"); enabled = true; commits++; afterCommit.run(); return ApplyResult.APPLIED; }, executor);
        }
        @Override public CompletionStage<JsonObject> hostProfile() { if (profileOverride != null) return profileOverride.get(); return CompletableFuture.supplyAsync(() -> { var profile = new JsonObject(); profile.addProperty("credentialKeyId", keys.get(keys.size() - 1).keyId()); profile.addProperty("fixtureNativeInstance", "actual-" + System.identityHashCode(this)); profile.addProperty("fixtureProfileVersion", profileVersion); return profile; }, executor); }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { throw new AssertionError("Legacy key install must not run"); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return CompletableFuture.supplyAsync(() -> { if (state.equals("serving")) return enabled && !closed ? ApplyResult.APPLIED : ApplyResult.REJECTED; enabled = false; closed |= state.equals("closed"); return ApplyResult.APPLIED; }, executor); }
        @Override public List<JsonObject> pollEvents() { return List.of(); }
        @Override public CompletionStage<Void> drain() { enabled = false; return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> close() { closed = true; enabled = false; return CompletableFuture.completedFuture(null); }
    }
    static final class Exchange implements ControlClientIo.Synchronization {
        final Executor executor; final AtomicLong now; final long deadline; final List<JsonObject> bodies = new ArrayList<>();
        final List<byte[]> originalBodies = new ArrayList<>();
        java.util.function.Consumer<JsonObject> inspectBody = ignored -> { };
        byte[] retained;
        java.util.function.Supplier<CompletionStage<ControlSynchronizationResult>> appliedOverride;
        final String target; ControlStateCodec.AppliedBasis applied; boolean current = true, rejectApplied; long desiredRevision = 1; String profileRevision, acceptedBasisSha256;
        ControlStateCodec.TicketPolicy policy = new ControlStateCodec.TicketPolicy("A001", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null)));
        Exchange(Executor executor, AtomicLong now, String target) { this.executor = executor; this.now = now; this.target = target; deadline = now.get() + 30000; }
        @Override public long deadlineMillis() { return deadline; }
        @Override public Optional<byte[]> pendingHeartbeat() { return Optional.ofNullable(retained); }
        @Override public void requireCurrent() { if (!current || now.get() >= deadline) throw new IllegalStateException("expired pass"); }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) {
            requireCurrent(); var body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); bodies.add(body); originalBodies.add(bytes.clone()); inspectBody.accept(body);
            return CompletableFuture.supplyAsync(() -> {
                var result = new JsonObject(); result.addProperty("receivedAt", "1970-01-01T00:00:01.000Z");
                if (body.has("hostProfile")) profileRevision = "hpr_fixture_" + bodies.size();
                if (profileRevision != null) result.addProperty("hostProfileRevision", profileRevision);
                var desired = new JsonObject(); desired.addProperty("revision", desiredRevision); desired.addProperty("state", target); result.add("desiredState", desired);
                var application = new JsonObject(); application.addProperty("version", 1);
                ControlStateCodec.AppliedBasis expected = null;
                if (!target.equals("serving")) expected = new ControlStateCodec.AppliedBasis(1, desiredRevision, target, "disabled", null, null);
                else if (profileRevision != null) expected = new ControlStateCodec.AppliedBasis(1, desiredRevision, target, "enabled", profileRevision, ControlStateCodec.ticketPolicyDigest(policy));
                application.add("expectedBasis", expected == null ? JsonNull.INSTANCE : JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(expected)));
                application.add("expectedTicketPolicy", expected == null || !target.equals("serving") ? JsonNull.INSTANCE : JsonParser.parseString(ControlStateCodec.encodeTicketPolicy(policy)));
                if (body.has("applicationAck") && expected != null
                        && body.getAsJsonObject("applicationAck").get("basis").equals(JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(expected))))
                    acceptedBasisSha256 = ControlStateCodec.appliedBasisDigest(expected);
                application.add("acceptedBasisSha256", acceptedBasisSha256 == null ? JsonNull.INSTANCE : new JsonPrimitive(acceptedBasisSha256));
                result.add("application", application);
                if (body.has("keyRequestId") && target.equals("serving") && profileRevision == null) {
                    var key = new JsonObject(); key.addProperty("keyId", "A001"); key.addProperty("secret", SECRET); result.add("ticketKey", key);
                    var request = new JsonObject(); request.addProperty("id", body.get("keyRequestId").getAsString()); request.addProperty("keyId", "A001"); result.add("keyRequest", request);
                }
                retained = null; // A committed result settles the pending original before the next heartbeat.
                return ControlledApplicationResultFixture.delivered(result.toString(), this::requireCurrent);
            }, executor);
        }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis value) {
            requireCurrent(); applied = value;
            if (appliedOverride != null) return appliedOverride.get();
            if (rejectApplied) return CompletableFuture.failedFuture(new IOException("Socket replaced before application confirmation"));
            return CompletableFuture.completedFuture(ControlSynchronizationResult.awaitingSource());
        }
    }
    @Test void sameExecutorNativeCompletionInstallsPersistsThenAcknowledgesWithoutProfileChurn(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000);
                var application = app(storage, nativeTransport, executor, now); var exchange = new Exchange(executor, now, "serving");
                application.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertNotNull(exchange.applied); assertTrue(nativeTransport.enabled); assertEquals(3, exchange.bodies.size());
                assertTrue(exchange.bodies.get(1).has("hostProfile")); assertFalse(exchange.bodies.get(2).has("hostProfile"));
                assertFalse(exchange.bodies.get(0).get("acceptingPlayers").getAsBoolean());
                assertEquals(1, nativeTransport.commits); assertFalse(application.lastResponse().has("ticketKey"));
                var retry = new Exchange(executor, now, "serving");
                application.synchronize(retry).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertNotNull(retry.applied); assertTrue(retry.bodies.isEmpty(), "source-only retries perform no heartbeat"); assertEquals(1, nativeTransport.commits);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void replacementReportsCurrentNativeApplicationBeforeReadyDespiteRetainedAcceptedBasis(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (String scenario : List.of("accepting", "not-accepting", "unhealthy", "draining")) try (var store = new ProviderStateStore(directory.resolve(scenario))) {
                String target = scenario.equals("draining") ? "draining" : "serving";
                boolean accepting = scenario.equals("accepting");
                ControlledProviderStateTest.seed(store, ORIGIN);
                try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                    var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000);
                    var application = new ControlledProviderApplication(storage, nativeTransport, executor, now::get, () -> null,
                            () -> new ProviderClient.Health(!scenario.equals("unhealthy"), accepting, 10, 0, "fixture", "fixture"), null);
                    var initial = new Exchange(executor, now, target);
                    application.synchronize(initial).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertNotNull(initial.applied);
                    var cancelled = new Exchange(executor, now, target);
                    cancelled.profileRevision = initial.profileRevision; cancelled.acceptedBasisSha256 = initial.acceptedBasisSha256;
                    cancelled.rejectApplied = true; application.request();
                    assertThrows(ExecutionException.class, () -> application.synchronize(cancelled).toCompletableFuture().get(3, TimeUnit.SECONDS));
                    assertFalse(nativeTransport.enabled, "Cancelled confirmation must finish conservative native cleanup");
                    var replacement = new Exchange(executor, now, target);
                    replacement.profileRevision = initial.profileRevision; replacement.acceptedBasisSha256 = initial.acceptedBasisSha256;
                    application.synchronize(replacement).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(initial.applied, replacement.applied);
                    assertEquals(2, replacement.bodies.size(), "Historical provider acceptance cannot replace a heartbeat acknowledging the restored native application");
                    assertFalse(replacement.bodies.get(0).has("applicationAck"));
                    assertFalse(replacement.bodies.get(0).get("acceptingPlayers").getAsBoolean());
                    var finalBody = replacement.bodies.get(1);
                    assertEquals(JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(replacement.applied)), finalBody.getAsJsonObject("applicationAck").get("basis"));
                    assertEquals(accepting, finalBody.get("acceptingPlayers").getAsBoolean());
                    assertEquals(!scenario.equals("unhealthy"), finalBody.get("healthy").getAsBoolean());
                    assertEquals(target.equals("serving"), nativeTransport.enabled, "Health observations do not replace native desired state");
                }
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void restartReappliesNewNativeInstanceBeforeBasisAcknowledgement(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN); var now = new AtomicLong(1000);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var first = new Native(executor, storage); var application = app(storage, first, executor, now);
                application.synchronize(new Exchange(executor, now, "serving")).toCompletableFuture().get(3, TimeUnit.SECONDS);
            }
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var next = new Native(executor, storage); assertFalse(next.enabled);
                var exchange = new Exchange(executor, now, "serving");
                app(storage, next, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertTrue(next.enabled); assertTrue(next.installs >= 2); assertTrue(exchange.bodies.get(0).has("hostProfile"));
                assertFalse(exchange.bodies.get(0).has("applicationAck")); assertEquals(1, next.commits);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void saveFailureLeavesNativeDisabledAndNeverAcknowledges(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN), value -> {
                if (value.getAsJsonObject("controlApplication").has("basis")) throw new IOException("injected durable application failure"); store.write(value);
            })) {
                var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000); var exchange = new Exchange(executor, now, "serving");
                assertThrows(ExecutionException.class, () -> app(storage, nativeTransport, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertFalse(nativeTransport.enabled); assertEquals(0, nativeTransport.commits); assertNull(exchange.applied);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void expiryAfterQueuedNativeInstallCannotSaveOrEnable(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000); var exchange = new Exchange(executor, now, "serving");
                nativeTransport.beforeInstall = () -> now.set(exchange.deadline);
                assertThrows(ExecutionException.class, () -> app(storage, nativeTransport, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertFalse(nativeTransport.enabled); assertTrue(exchange.bodies.isEmpty()); assertNull(exchange.applied);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void disabledStatesNeedActualApplicationWithoutAdmissionKeys(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (String target : List.of("draining", "closed")) try (var store = new ProviderStateStore(directory.resolve(target))) {
                ControlledProviderStateTest.seed(store, ORIGIN);
                try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                    var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000); var exchange = new Exchange(executor, now, target);
                    app(storage, nativeTransport, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(target, exchange.applied.state()); assertFalse(nativeTransport.enabled); assertEquals(0, nativeTransport.commits);
                    assertTrue(nativeTransport.keys.isEmpty());
                }
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void profileChangeAfterCommitDisablesBeforeRepublishing(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000); var exchange = new Exchange(executor, now, "serving");
                nativeTransport.afterCommit = () -> { if (nativeTransport.commits == 1) nativeTransport.profileVersion++; };
                var republishes = new AtomicInteger();
                exchange.inspectBody = body -> { if (body.has("hostProfile") && nativeTransport.commits > 0) {
                    assertFalse(nativeTransport.enabled, "Mismatched committed native profile remained enabled during publication"); republishes.incrementAndGet();
                } };
                app(storage, nativeTransport, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(1, republishes.get()); assertEquals(2, nativeTransport.commits); assertNotNull(exchange.applied);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void unknownRetainedEnabledAckCannotReplayOnRestart(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN); var now = new AtomicLong(1000); byte[] original;
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var first = new Exchange(executor, now, "serving");
                app(storage, new Native(executor, storage), executor, now).synchronize(first).toCompletableFuture().get(3, TimeUnit.SECONDS);
                original = first.bodies.get(2).toString().getBytes(StandardCharsets.UTF_8);
            }
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var next = new Native(executor, storage); var exchange = new Exchange(executor, now, "serving"); exchange.retained = original;
                var failure = assertThrows(ExecutionException.class, () -> app(storage, next, executor, now).synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS));
                assertInstanceOf(ControlClientIo.ReconciliationRequired.class, failure.getCause());
                assertTrue(exchange.bodies.isEmpty()); assertFalse(next.enabled); assertNull(exchange.applied);
                assertArrayEquals(original, exchange.retained);
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void staleFullProfileWithoutAcceptingPlayersCannotReplayAfterRemapOrRestart(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (String scenario : List.of("remap", "restart")) try (var store = new ProviderStateStore(directory.resolve(scenario))) {
                ControlledProviderStateTest.seed(store, ORIGIN);
                try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                    var now = new AtomicLong(1000); var previous = new Native(executor, storage); var application = app(storage, previous, executor, now);
                    var first = new Exchange(executor, now, "serving");
                    application.synchronize(first).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    byte[] original = first.originalBodies.get(1).clone();
                    assertTrue(first.bodies.get(1).has("hostProfile")); assertFalse(first.bodies.get(1).get("acceptingPlayers").getAsBoolean());
                    var current = previous;
                    if (scenario.equals("remap")) current.profileVersion++;
                    else { current = new Native(executor, storage); application = app(storage, current, executor, now); }
                    var replacement = new Exchange(executor, now, "serving"); replacement.retained = original;
                    var result = application.synchronize(replacement).toCompletableFuture();
                    var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                    assertInstanceOf(ControlClientIo.ReconciliationRequired.class, failure.getCause());
                    assertTrue(replacement.bodies.isEmpty(), "Stale full profiles must be cancelled or strongly resolved before any replay");
                    assertArrayEquals(original, replacement.retained); assertFalse(current.enabled); assertNull(replacement.applied);
                }
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void unchangedOwnedFullProfileRetainsExactOriginalBytesForRetry(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var now = new AtomicLong(1000); var current = new Native(executor, storage); var application = app(storage, current, executor, now);
                var first = new Exchange(executor, now, "serving"); application.synchronize(first).toCompletableFuture().get(3, TimeUnit.SECONDS);
                byte[] original = (" \n" + first.bodies.get(1) + "\n").getBytes(StandardCharsets.UTF_8);
                var retry = new Exchange(executor, now, "serving"); retry.retained = original;
                application.synchronize(retry).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertArrayEquals(original, retry.originalBodies.get(0)); assertEquals(2, retry.bodies.size());
                assertTrue(retry.bodies.get(1).has("applicationAck")); assertTrue(retry.bodies.get(1).get("acceptingPlayers").getAsBoolean());
                assertNotNull(retry.applied); assertTrue(current.enabled);
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void retainedProfileReadCannotOutliveOriginalSynchronizationDeadline(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN))) {
                var now = new AtomicLong(1000); var current = new Native(executor, storage); var application = app(storage, current, executor, now);
                var first = new Exchange(executor, now, "serving"); application.synchronize(first).toCompletableFuture().get(3, TimeUnit.SECONDS);
                var original = first.originalBodies.get(1).clone(); var held = new CompletableFuture<JsonObject>(); var requested = new CountDownLatch(1);
                current.profileOverride = () -> { requested.countDown(); return held; };
                var retry = new Exchange(executor, now, "serving"); retry.retained = original;
                var result = application.synchronize(retry).toCompletableFuture();
                assertTrue(requested.await(3, TimeUnit.SECONDS)); assertFalse(result.isDone()); assertTrue(retry.bodies.isEmpty());
                now.set(retry.deadline); held.complete(first.bodies.get(1).getAsJsonObject("hostProfile").deepCopy());
                var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause()); assertArrayEquals(original, retry.retained);
                assertTrue(retry.bodies.isEmpty()); assertFalse(current.enabled); assertNull(retry.applied);
            }
        } finally { executor.shutdownNow(); }
    }

    @Test void keyRequestSaveFailureAfterServingFencesNativeAndSignalsFatal(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor(); var reject = new AtomicBoolean();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ORIGIN), value -> {
                if (reject.get()) throw new IOException("injected key request fsync failure"); store.write(value);
            })) {
                var nativeTransport = new Native(executor, storage); var now = new AtomicLong(1000); var application = app(storage, nativeTransport, executor, now);
                application.synchronize(new Exchange(executor, now, "serving")).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertTrue(nativeTransport.enabled); var fatal = new AtomicReference<Throwable>(); application.onFatal(fatal::set); reject.set(true);
                assertThrows(ExecutionException.class, () -> CompletableFuture.runAsync(() -> {
                    try { application.requestKey(); } catch (IOException failure) { throw new CompletionException(failure); }
                }, executor).get(3, TimeUnit.SECONDS));
                CompletableFuture.runAsync(() -> { }, executor).get(3, TimeUnit.SECONDS);
                assertFalse(nativeTransport.enabled); assertInstanceOf(IOException.class, fatal.get()); assertFalse(application.due());
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void retainedNativeClaimPolicyIsBoundToOriginalHeartbeatBytes() {
        for (String body : List.of("{\"acceptingPlayers\":true}", "{\"applicationAck\":{}}", "{\"hostProfile\":{},\"acceptingPlayers\":false}", "{\"acceptingPlayers\":false}")) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            var intent = ControlLifecycleCodec.intent(ORIGIN, "heartbeat", "fixture_host", 1, 18, "native_claim_fixture_18", bytes);
            assertEquals(!body.equals("{\"acceptingPlayers\":false}"), ControlledProviderApplication.requiresNativeCancellation(intent, bytes));
            assertThrows(IllegalArgumentException.class, () -> ControlledProviderApplication.requiresNativeCancellation(intent, "{}".getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void exactPolicyPreservesCutoffsAndOrdersActiveLast() {
        var available = new JsonArray();
        for (String id : List.of("Z999", "A001")) { var key = new JsonObject(); key.addProperty("keyId", id); key.addProperty("secret", SECRET); key.addProperty("notBefore", 0); if (id.equals("Z999")) key.addProperty("acceptUntil", 2000); available.add(key); }
        var policy = new ControlStateCodec.TicketPolicy("A001", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null), new ControlStateCodec.TicketEpoch("Z999", 0, 2000L)));
        var result = ControlledProviderApplication.exactPolicyKeys(available, policy); assertEquals("A001", result.get(1).getAsJsonObject().get("keyId").getAsString());
        assertThrows(IllegalStateException.class, () -> ControlledProviderApplication.exactPolicyKeys(available, new ControlStateCodec.TicketPolicy("A001", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null), new ControlStateCodec.TicketEpoch("Z999", 0, 2001L)))));
        assertThrows(IllegalStateException.class, () -> ControlledProviderApplication.exactPolicyKeys(new JsonArray(), policy));
    }
    private static ControlledProviderApplication app(ControlledProviderState storage, Native nativeTransport, Executor executor, AtomicLong now) {
        return new ControlledProviderApplication(storage, nativeTransport, executor, now::get, () -> null,
                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
    }
}
