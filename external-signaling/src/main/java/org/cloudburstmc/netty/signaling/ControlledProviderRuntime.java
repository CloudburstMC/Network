package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Reachable opted-in lifecycle. No calls to the legacy registration, recovery, HTTP, or sequence owner. */
final class ControlledProviderRuntime {
    private final ControlledProviderState storage;
    private final ControlledProviderApplication application;
    private final ProviderTransport transport;
    private final Executor applicationExecutor;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "nethernet-control-timeouts"));
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, r -> daemon(r, "nethernet-control-io"));
    private final ExecutorService receiver = Executors.newSingleThreadExecutor(r -> daemon(r, "nethernet-control-receive"));
    private final ControlClientClock clock = ControlClientClock.system();
    private final JdkProviderControlIo io;
    private final ControlClientCoordinator coordinator;
    private final Consumer<String> diagnostics;
    private final AtomicBoolean tickQueued = new AtomicBoolean(), stopped = new AtomicBoolean();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile CompletableFuture<JsonObject> awaitingReady;
    private final CompletableFuture<Void> stoppedResult = new CompletableFuture<>();
    private boolean outcomesInFlight, synchronizationDemand = true;
    private java.util.List<JsonObject> polledEvents = java.util.List.of();
    private long nextOutcomes;
    private long readinessDeadline;
    private ScheduledFuture<?> tick;

    ControlledProviderRuntime(ProviderClient.Configuration config, ProviderStateStore store, ProviderTransport transport,
            Executor executor, Supplier<ServerStatus> status, Supplier<ProviderClient.Health> health, Consumer<String> diagnostics) throws IOException {
        if (!transport.supportsAdmissionStaging()) throw new IOException("Controlled startup requires a listener created with admission staging");
        if (config.control().nativeOwnership() == ProviderControlConfiguration.NativeOwnership.ISSUED && !transport.supportsNativeIdentityCapture())
            throw new IOException("Issued ownership requires an explicit controlled v2 native identity capture");
        if ((config.control().candidatePublication() == ProviderControlConfiguration.CandidatePublication.MAINTAINED) != transport.supportsMaintainedCandidateLeases())
            throw new IOException("Maintained candidate publication requires matching explicit application and transport configuration");
        this.applicationExecutor = command -> {
            if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); throw new RejectedExecutionException("Controlled application queue full"); }
            try { executor.execute(() -> { try { command.run(); } finally { queued.decrementAndGet(); } }); }
            catch (RuntimeException failure) { queued.decrementAndGet(); throw failure; }
        };
        this.transport = transport; this.diagnostics = diagnostics;
        storage = ControlledProviderState.open(store, config.control());
        application = new ControlledProviderApplication(storage, transport, applicationExecutor, clock, status, health, config.region());
        var client = HttpClient.newBuilder().executor(ioExecutor).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        io = new JdkProviderControlIo(client, timer, receiver, clock, application, this::notice);
        try {
            coordinator = new ControlClientCoordinator(storage.journal, storage.initial, config.control().routes(), io, clock,
                    (action, delay) -> { var task = timer.schedule(action, delay, TimeUnit.MILLISECONDS); return () -> task.cancel(false); },
                    Math::random, ControlClientCoordinator.secureIdentifiers(), config.control().trustedKeys());
            application.onFatal(failure -> { diagnostics.accept("controlled_application_persistence_failed"); stop(); });
        } catch (Throwable failure) {
            io.close(); storage.close(); timer.shutdownNow(); ioExecutor.shutdownNow(); receiver.shutdownNow();
            if (failure instanceof IOException error) throw error; throw new IOException("Controlled coordinator could not start", failure);
        }
    }
    CompletionStage<JsonObject> start() {
        var result = waitReady(); coordinator.start();
        tick = timer.scheduleWithFixedDelay(() -> {
            if (stopped.get() || !tickQueued.compareAndSet(false, true)) return;
            try { applicationExecutor.execute(() -> { try { tick(); } catch (RuntimeException unavailable) { diagnostics.accept("controlled_observation_unavailable"); } finally { tickQueued.set(false); } }); }
            catch (RuntimeException failure) { tickQueued.set(false); diagnostics.accept("controlled_application_queue_unavailable"); }
        }, 0, 250, TimeUnit.MILLISECONDS);
        return result;
    }
    private void tick() {
        if (stopped.get()) return;
        application.maintainCandidates();
        var state = coordinator.state();
        if (state == ControlClientCoordinator.State.DEREGISTERED || state == ControlClientCoordinator.State.UNRESOLVED || state == ControlClientCoordinator.State.CLOSED) {
            if (awaitingReady != null) { awaitingReady.completeExceptionally(new IllegalStateException("Controlled lifecycle requires reconciliation: " + state)); awaitingReady = null; }
            return;
        }
        if (state == ControlClientCoordinator.State.READY) synchronizationDemand = false;
        if (coordinator.ready() && awaitingReady != null) {
            var result = awaitingReady; awaitingReady = null; result.complete(registration());
        } else if (awaitingReady != null && clock.nowMillis() >= readinessDeadline) {
            var result = awaitingReady; awaitingReady = null; result.completeExceptionally(new TimeoutException("Controlled readiness deadline reached"));
        }
        if ((state == ControlClientCoordinator.State.READY || state == ControlClientCoordinator.State.AUTHORITY_EXPIRED) && application.due()) synchronize();
        if (coordinator.ready() && !outcomesInFlight && coordinator.snapshot().pending() == null && clock.nowMillis() >= nextOutcomes) {
            nextOutcomes = clock.nowMillis() + 1000;
            try {
                if (polledEvents.isEmpty()) polledEvents = java.util.List.copyOf(transport.pollEvents(storage.eventCapacity()));
                // Retain a drained batch until its root write succeeds; a failed write cannot silently discard it.
                storage.appendEvents(polledEvents); polledEvents = java.util.List.of(); var body = storage.outcomeBatch();
                if (!body.getAsJsonArray("events").isEmpty()) {
                    outcomesInFlight = true;
                    coordinator.submit("outcomes", body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), false).whenCompleteAsync((receipt, failure) -> {
                        outcomesInFlight = false;
                        if (failure != null) { diagnostics.accept("controlled_outcomes_unavailable"); return; }
                        try { committed(receipt); }
                        catch (RuntimeException invalid) { diagnostics.accept("controlled_outcomes_retained"); }
                    }, applicationExecutor);
                }
            } catch (IOException | RuntimeException unavailable) { outcomesInFlight = false; diagnostics.accept("controlled_outcomes_unavailable"); }
        }
    }
    private void synchronize() {
        var state = coordinator.state();
        if (synchronizationDemand || state != ControlClientCoordinator.State.READY && state != ControlClientCoordinator.State.AUTHORITY_EXPIRED) return;
        try { coordinator.synchronize(); synchronizationDemand = true; }
        catch (IllegalStateException unavailable) { /* Existing bounded bootstrap/refresh owns its retry. */ }
    }
    private CompletionStage<JsonObject> waitReady() {
        if (awaitingReady == null) { awaitingReady = new CompletableFuture<>(); readinessDeadline = clock.nowMillis() + 300000; }
        return awaitingReady.minimalCompletionStage();
    }
    CompletionStage<JsonObject> refresh() { application.request(); var result = waitReady(); synchronize(); return result.thenApply(ignored -> application.lastResponse()); }
    void request() { application.request(); if (coordinator.ready()) synchronize(); }
    void extensions(JsonObject value) { application.extensions(value); request(); }
    CompletionStage<JsonObject> rotateTicketKey() throws IOException {
        if (!coordinator.ready() || coordinator.snapshot().pending() != null) throw new IOException("Controlled lifecycle must be ready and settled before requesting a key");
        application.requestKey(); var result = waitReady(); synchronize(); return result; }
    CompletionStage<JsonObject> rotateMachineKey() {
        return coordinator.rotateMachineKey().thenComposeAsync(receipt -> { committed(receipt); application.request(); return waitReady(); }, applicationExecutor);
    }
    CompletionStage<Void> deregister() {
        return coordinator.submit("deregister", new byte[]{'{', '}'}, true).thenComposeAsync(receipt -> {
            committed(receipt); return application.drain();
        }, applicationExecutor);
    }
    CompletionStage<Void> drain() { return application.drain().thenRunAsync(this::request, applicationExecutor); }
    JsonObject registration() { return storage.registration(coordinator.snapshot()); }
    private static void committed(ControlOperationResult result) {
        if (!result.receipt().disposition().equals("committed")) throw new IllegalStateException("Controlled lifecycle receipt is unresolved");
    }
    private void notice(ControlFrameDelivery delivery) {
        delivery.requireCurrent(); String type = delivery.frame().type();
        if (!type.equals("state.desired") && !type.equals("session.resync") && !type.equals("connectivity.report")) throw new IllegalStateException("No controlled application dispatcher for frame");
        if (type.equals("connectivity.report")) return; // Observations do not mutate player admission or health.
        application.invalidate();
        applicationExecutor.execute(() -> {
            // The notice may already have moved synchronization to another phase; stale notices perform no effects.
            try { delivery.requireCurrent(); application.request(); synchronize(); }
            catch (IllegalStateException expired) { }
        });
    }
    CompletionStage<Void> stop() {
        if (!stopped.compareAndSet(false, true)) return stoppedResult.minimalCompletionStage();
        application.close(); if (tick != null) tick.cancel(false);
        try { coordinator.close(); } catch (IOException failure) { diagnostics.accept("controlled_journal_close_failed"); }
        io.close();
        var result = stoppedResult;
        var timeout = timer.schedule(() -> result.completeExceptionally(new TimeoutException("Native close did not finish")), 10000, TimeUnit.MILLISECONDS);
        transport.close().whenComplete((ignored, failure) -> { if (failure == null) result.complete(null); else result.completeExceptionally(failure); });
        result.whenComplete((ignored, failure) -> {
            timeout.cancel(false); timer.shutdownNow(); ioExecutor.shutdownNow(); receiver.shutdownNow();
            if (awaitingReady != null) awaitingReady.completeExceptionally(new IllegalStateException("Controlled client stopped"));
        });
        return result.minimalCompletionStage();
    }
    private static Thread daemon(Runnable action, String name) { var thread = new Thread(action, name); thread.setDaemon(true); return thread; }
}
