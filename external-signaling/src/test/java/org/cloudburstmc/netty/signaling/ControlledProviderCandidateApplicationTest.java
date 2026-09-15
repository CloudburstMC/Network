package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.signaling.control.ControlSynchronizationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledProviderCandidateApplicationTest {
    static final NativeCandidateSnapshot A = NativeCandidateSnapshot.hosts(List.of(new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("::1", 19132)));
    static final NativeCandidateSnapshot B = NativeCandidateSnapshot.hosts(List.of(new InetSocketAddress("::1", 19132)));
    static final NativeCandidateSnapshot EMPTY = NativeCandidateSnapshot.hosts(List.of());
    static final class Owned implements ProviderTransport {
        final ControlledProviderApplicationTest.Native delegate;
        NativeCandidateSnapshot candidates = A; volatile long generation;
        Function<HostProfileSnapshot, CompletionStage<HostProfileSnapshot>> capture = CompletableFuture::completedFuture;
        int captures;
        Owned(ControlledProviderApplicationTest.Native delegate) { this.delegate = delegate; }
        boolean replace(NativeCandidateSnapshot value) { if (candidates.equals(value)) return false; candidates = value; generation++; return true; }
        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            long owner = generation; var selected = candidates; captures++;
            return delegate.hostProfile().thenCompose(profile -> {
                profile.addProperty("version", 2); var array = new JsonArray();
                for (var item : selected.candidates()) { var candidate = new JsonObject(); candidate.addProperty("address", item.endpoint().getAddress().getHostAddress()); candidate.addProperty("port", item.endpoint().getPort()); array.add(candidate); }
                profile.add("candidates", array);
                return capture.apply(new HostProfileSnapshot(profile, () -> { if (owner != generation) throw new IllegalStateException("candidate ownership changed"); }));
            });
        }
        @Override public CompletionStage<JsonObject> hostProfile() { return captureHostProfile().thenApply(HostProfileSnapshot::profile); }
        @Override public boolean supportsAdmissionStaging() { return true; }
        @Override public AdmissionUpdate beginAdmissionUpdate(long deadline) { return delegate.beginAdmissionUpdate(deadline); }
        @Override public CompletionStage<Void> installTicketKeys(AdmissionUpdate token, List<TicketKey> keys) { return delegate.installTicketKeys(token, keys); }
        @Override public CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate token, Runnable guard) { return delegate.commitAdmissionUpdate(token, guard); }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { return delegate.installTicketKeys(keys); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return delegate.applyState(state); }
        @Override public List<JsonObject> pollEvents() { return List.of(); }
        @Override public CompletionStage<Void> drain() { return delegate.drain(); }
        @Override public CompletionStage<Void> close() { return delegate.close(); }
    }
    static ControlledProviderApplication app(ControlledProviderState storage, ProviderTransport transport, Executor executor, AtomicLong now) {
        return new ControlledProviderApplication(storage, transport, executor, now::get, () -> null,
                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
    }
    @Test void withdrawalKeepsTruthfulServingBasisAndUnchangedObservationsDoNotChurn(@TempDir Path directory) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledProviderApplicationTest.ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledProviderApplicationTest.ORIGIN))) {
                var transport = new Owned(new ControlledProviderApplicationTest.Native(executor, storage)); var now = new AtomicLong(1000);
                var application = app(storage, transport, executor, now);
                String previous = null;
                for (var selection : List.of(A, B, EMPTY, A)) {
                    transport.replace(selection); var exchange = new ControlledProviderApplicationTest.Exchange(executor, now, "serving");
                    exchange.profileRevision = previous;
                    application.synchronize(exchange).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertNotNull(exchange.applied); assertTrue(transport.delegate.enabled); assertEquals("serving", exchange.applied.state());
                    var last = exchange.bodies.get(exchange.bodies.size() - 1);
                    assertEquals(!selection.candidates().isEmpty(), last.get("acceptingPlayers").getAsBoolean());
                    assertTrue(last.get("healthy").getAsBoolean()); assertTrue(last.has("applicationAck"));
                    assertEquals(selection.candidates().size(), storage.application().getAsJsonObject("profile").getAsJsonArray("candidates").size());
                    previous = exchange.profileRevision;
                    int captures = transport.captures; assertFalse(transport.replace(selection));
                    for (int i = 0; i < 100; i++) assertFalse(application.due());
                    assertEquals(captures, transport.captures, "ticks invoke only a nonblocking saved ownership guard");
                    var retry = new ControlledProviderApplicationTest.Exchange(executor, now, "serving");
                    application.synchronize(retry).toCompletableFuture().get(3, TimeUnit.SECONDS); assertTrue(retry.bodies.isEmpty());
                }
            }
        } finally { executor.shutdownNow(); }
    }
    @Test void snapshotChangesFenceEachAsynchronousBoundaryIncludingAbaAndFinalAck(@TempDir Path directory) throws Exception {
        for (String boundary : List.of("capture", "response", "install", "save", "commit", "ack")) {
            var executor = Executors.newSingleThreadExecutor(); var owned = new AtomicReference<Owned>(); var fired = new AtomicBoolean();
            Runnable change = () -> { if (fired.compareAndSet(false, true)) { owned.get().replace(B); owned.get().replace(A); } };
            try (var store = new ProviderStateStore(directory.resolve(boundary))) {
                ControlledProviderStateTest.seed(store, ControlledProviderApplicationTest.ORIGIN);
                try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledProviderApplicationTest.ORIGIN), value -> {
                    store.write(value);
                    if (boundary.equals("save") && value.getAsJsonObject("controlApplication").has("basis")) change.run();
                })) {
                    var nativeTransport = new ControlledProviderApplicationTest.Native(executor, storage); var transport = new Owned(nativeTransport); owned.set(transport);
                    var now = new AtomicLong(1000); var application = app(storage, transport, executor, now);
                    var exchange = new ControlledProviderApplicationTest.Exchange(executor, now, "serving");
                    if (boundary.equals("capture")) transport.capture = snapshot -> { change.run(); return CompletableFuture.completedFuture(snapshot); };
                    if (boundary.equals("response")) exchange.inspectBody = body -> { if (body.has("hostProfile")) change.run(); };
                    if (boundary.equals("install")) nativeTransport.beforeInstall = () -> { if (nativeTransport.installs >= 2) change.run(); };
                    if (boundary.equals("commit")) nativeTransport.afterCommit = change;
                    var ack = new CompletableFuture<ControlSynchronizationResult>(); var ackEntered = new CountDownLatch(1);
                    if (boundary.equals("ack")) exchange.appliedOverride = () -> { ackEntered.countDown(); return ack; };
                    var completion = application.synchronize(exchange).toCompletableFuture();
                    if (boundary.equals("ack")) { assertTrue(ackEntered.await(3, TimeUnit.SECONDS)); change.run(); ack.complete(ControlSynchronizationResult.awaitingSource()); }
                    assertThrows(ExecutionException.class, () -> completion.get(3, TimeUnit.SECONDS), boundary);
                    assertTrue(fired.get(), boundary); assertFalse(nativeTransport.enabled, boundary);
                    if (!boundary.equals("ack")) assertNull(exchange.applied, boundary);
                    assertTrue(nativeTransport.commits <= (boundary.equals("ack") || boundary.equals("commit") ? 1 : 0), boundary);
                }
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void legacyCaptureCannotSilentlyPublishVersionTwo(@TempDir Path directory) throws Exception {
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledProviderApplicationTest.ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledProviderApplicationTest.ORIGIN))) {
                var transport = new ControlledProviderApplicationTest.Native(Runnable::run, storage);
                transport.profileOverride = () -> { var profile = new JsonObject(); profile.addProperty("version", 2); return CompletableFuture.completedFuture(profile); };
                assertThrows(ExecutionException.class, () -> transport.captureHostProfile().toCompletableFuture().get());
            }
        }
    }
}
