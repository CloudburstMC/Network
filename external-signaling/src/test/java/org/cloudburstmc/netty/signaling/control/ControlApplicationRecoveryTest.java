package org.cloudburstmc.netty.signaling.control;

import org.cloudburstmc.netty.signaling.ControlledApplicationCoordinatorFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlApplicationRecoveryTest {
    static ControlClientCoordinatorTest.Harness harness(ControlledApplicationCoordinatorFixture application,
            ControlClientCoordinatorTest.Time time, String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(new ControlClientCoordinatorTest.Journal(), time, application.initial(), mode);
        h.client.close();
        h.newClient(new ControlClientJournal() {
            @Override public Optional<Snapshot> read() throws IOException { return application.journal().read(); }
            @Override public void commit(Snapshot value) throws IOException { application.journal().commit(value); h.journal.commit(value); }
            @Override public void close() { }
        });
        h.actualSynchronization = application::synchronize;
        h.durableOutcomes = true; h.actualOutcomeAck = application::acknowledge;
        return h;
    }
    static void activate(ControlClientCoordinatorTest.Harness h) throws Exception {
        h.respondPrepare(); if (h.initialTransport.equals("websocket")) h.links.get(h.links.size()-1).challenge(); h.respondActivation(); h.respondAuthority();
    }
    @Test void actualApplicationLostSocketHeartbeatWaitSettlesCleanupWithoutDroppingTheIntent(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = harness(application, time, "websocket"); h.client.start(); h.respondStatus(); activate(h); application.runTasks();
            var original = h.journal.value.pending(); assertEquals("heartbeat", original.intent().operation());
            assertEquals(1, h.links.get(0).sent.size()); assertFalse(h.synchronizationResults.get(0).toCompletableFuture().isDone());
            h.links.get(0).abort(); application.runTasks();
            assertTrue(h.synchronizationResults.get(0).toCompletableFuture().isCompletedExceptionally(), "Invalidated application must finish its actual cleanup");
            assertEquals(original, application.journal().read().orElseThrow().pending()); assertFalse(application.nativeEnabled());
            time.advance(200); h.respondStatus(); h.respondStatus(); activate(h); application.runTasks();
            assertEquals(2, h.synchronizationExchanges.size()); assertEquals(1, h.links.get(1).sent.size());
            assertEquals(original, h.journal.value.pending()); assertTrue(h.operations.isEmpty()); assertFalse(h.client.ready()); h.client.close(); application.runTasks();
        }
    }
    @Test void expiredApplicationHeartbeatWaitSettlesButLateHttpReplyCannotInstallItsBody(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = harness(application, time, "https"); h.client.start(); h.respondStatus(); activate(h); application.runTasks();
            var original = h.journal.value.pending(); var operation = h.operations.get(0); var receipt = h.receipt("committed");
            time.advance(30001); application.runTasks();
            assertTrue(h.synchronizationResults.get(0).toCompletableFuture().isCompletedExceptionally());
            operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 200, ControlClientCoordinatorTest.resultWire(receipt)));
            application.runTasks(); assertEquals(original, h.journal.value.pending()); assertFalse(application.nativeEnabled());
            assertFalse(application.applicationState().has("basis")); h.client.close(); application.runTasks();
        }
    }
}
