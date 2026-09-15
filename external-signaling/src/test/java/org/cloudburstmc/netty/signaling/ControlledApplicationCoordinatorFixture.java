package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.control.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Actual application/root/file journal with a deterministic serialized executor and explicitly fake native adapter. */
public final class ControlledApplicationCoordinatorFixture implements AutoCloseable {
    private final ProviderStateStore root;
    private final ControlledProviderState storage;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final ControlledProviderApplicationTest.Native nativeTransport;
    private final ControlledProviderApplication application;
    private final ControlledProviderCandidateApplicationTest.Owned candidates;
    public boolean failRootSave;
    public ControlledApplicationCoordinatorFixture(Path directory, ControlClientClock clock) throws Exception { this(directory, clock, false); }
    public ControlledApplicationCoordinatorFixture(Path directory, ControlClientClock clock, boolean version2) throws Exception {
        root = new ProviderStateStore(directory);
        ControlledProviderStateTest.seed(root, "https://provider.example");
        if (version2) {
            var value = root.read(); var key = new JsonObject(); key.addProperty("keyId", "A001");
            key.addProperty("secret", ControlledProviderApplicationTest.SECRET); value.getAsJsonArray("ticketKeys").add(key); root.write(value);
        }
        storage = ControlledProviderState.open(root, ControlledProviderStateTest.config("https://provider.example"), value -> {
            if (failRootSave) throw new IOException("Injected root acknowledgement save failure"); root.write(value);
        });
        nativeTransport = new ControlledProviderApplicationTest.Native(tasks::add, storage);
        candidates = version2 ? new ControlledProviderCandidateApplicationTest.Owned(nativeTransport) : null;
        application = new ControlledProviderApplication(storage, candidates == null ? nativeTransport : candidates, tasks::add, clock, () -> null,
                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
    }
    public void replaceCandidates(boolean change) {
        if (candidates == null) throw new IllegalStateException("Versioned fixture required");
        if (change) candidates.replace(ControlledProviderCandidateApplicationTest.B);
        candidates.replace(ControlledProviderCandidateApplicationTest.A);
    }
    public static boolean requiresNativeCancellation(ControlLifecycleCodec.Intent intent, byte[] body) {
        return ControlledProviderApplication.requiresNativeCancellation(intent, body);
    }
    public ControlClientJournal.Snapshot initial() { return storage.initial; }
    public ControlClientJournal journal() { return storage.journal; }
    public CompletionStage<ControlSynchronizationResult> synchronize(ControlClientIo.Synchronization exchange) { return application.synchronize(exchange); }
    public CompletionStage<Void> acknowledge(ControlLifecycleCodec.Intent intent, byte[] body, ControlLifecycleCodec.Receipt receipt) {
        return application.acknowledgeOutcomes(intent, body, receipt);
    }
    public void runTasks() {
        int count = 0;
        while (!tasks.isEmpty()) { if (++count > 1000) throw new AssertionError("Unbounded application work"); tasks.remove().run(); }
    }
    public boolean nativeEnabled() { return nativeTransport.enabled; }
    public byte[] appendOutcome() throws IOException {
        storage.appendEvents(List.of(ControlledProviderStateTest.event(1)));
        return storage.outcomeBatch().toString().getBytes(StandardCharsets.UTF_8);
    }
    public int pendingEvents() { return storage.outcomeBatch().getAsJsonArray("events").size(); }
    public JsonObject applicationState() { return storage.application(); }
    @Override public void close() throws IOException { storage.close(); root.close(); }
}
