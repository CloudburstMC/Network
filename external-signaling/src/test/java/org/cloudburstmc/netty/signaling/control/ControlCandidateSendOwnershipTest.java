package org.cloudburstmc.netty.signaling.control;

import org.cloudburstmc.netty.signaling.ControlledApplicationCoordinatorFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ControlCandidateSendOwnershipTest {
    @Test void actualApplicationSnapshotIsCheckedAfterJournalPersistenceAndAtFinalCarrierSend(@TempDir Path directory) throws Exception {
        for (String mode : List.of("https", "websocket")) for (String boundary : List.of("journal", "send")) {
            var time = new ControlClientCoordinatorTest.Time();
            try (var application = new ControlledApplicationCoordinatorFixture(directory.resolve(mode + boundary), time, true)) {
                var h = ControlApplicationRecoveryTest.harness(application, time, mode);
                h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
                long deadline = h.synchronizationExchanges.get(0).deadlineMillis();
                var changed = new AtomicBoolean();
                Runnable replace = () -> { changed.set(true); application.replaceCandidates(true); };
                if (boundary.equals("journal")) h.journal.afterCommit = replace; else time.afterSchedule = replace;
                application.runTasks();
                assertTrue(changed.get()); assertTrue(h.operations.isEmpty(), mode + boundary);
                if (mode.equals("websocket")) assertTrue(h.links.get(0).sent.isEmpty(), mode + boundary);
                var original = application.journal().read().orElseThrow().pending();
                assertNotNull(original); assertNull(original.receipt()); assertEquals(original, h.client.snapshot().pending());
                assertTrue(ControlledApplicationCoordinatorFixture.requiresNativeCancellation(original.intent(), original.bodyBytes()));
                assertFalse(application.nativeEnabled()); assertFalse(h.client.ready());
                assertTrue(h.synchronizationResults.get(0).toCompletableFuture().isCompletedExceptionally());
                assertEquals(deadline, h.synchronizationExchanges.get(0).deadlineMillis());
                h.client.close(); application.runTasks();
                assertEquals(original, application.journal().read().orElseThrow().pending(), "only strong provider reconciliation may release original bytes");
            }
        }
    }
    @Test void unchangedMaterialDuringJournalPersistenceStillSendsExactOriginal(@TempDir Path directory) throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var time = new ControlClientCoordinatorTest.Time();
            try (var application = new ControlledApplicationCoordinatorFixture(directory.resolve(mode), time, true)) {
                var h = ControlApplicationRecoveryTest.harness(application, time, mode);
                h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
                h.journal.afterCommit = () -> application.replaceCandidates(false); application.runTasks();
                var original = application.journal().read().orElseThrow().pending(); assertNotNull(original);
                if (mode.equals("https")) { assertEquals(1, h.operations.size()); assertArrayEquals(original.bodyBytes(), h.operations.get(0).body()); }
                else {
                    assertEquals(1, h.links.get(0).sent.size());
                    var frame = ControlFrameCodec.decode(h.links.get(0).sent.get(0));
                    var request = ControlLifecycleCodec.decodeWsRequest(new String(frame.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    assertArrayEquals(original.bodyBytes(), request.bodyBytes());
                }
                h.client.close(); application.runTasks();
            }
        }
    }
    @Test void committedReceiptsSettleButEndpointOwnershipStillGuardsBodyDeliveryAndReadback() throws Exception {
        for (String mode : List.of("https", "websocket")) for (boolean duringCommit : List.of(true, false)) {
            var h = new ControlClientCoordinatorTest.Harness(mode); var owned = new AtomicBoolean(true);
            h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
            var lane = h.synchronizationExchanges.get(0);
            Runnable guard = () -> { if (!owned.get()) throw new IllegalStateException("candidate ownership changed"); };
            var result = lane.heartbeat("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), guard).toCompletableFuture();
            var receipt = h.receipt("committed");
            if (duringCommit) h.journal.afterCommit = () -> owned.set(false);
            var wire = ControlClientCoordinatorTest.resultWire(receipt);
            if (mode.equals("https")) {
                var operation = h.operations.get(0); operation.reply().complete(new ControlClientIo.HttpReply(
                        operation.endpoint(), "POST", operation.endpoint(), 200, wire));
            } else {
                var link = h.links.get(0); h.incomingActual(link, h.writer, "lifecycle.receipt",
                        wire.getBytes(java.nio.charset.StandardCharsets.UTF_8), link.nextProviderSequence);
            }
            assertEquals(receipt, result.join().receipt()); assertNull(h.client.snapshot().pending());
            assertEquals(!duringCommit, result.join().hasBody());
            owned.set(false); assertThrows(IllegalStateException.class, result.join()::requireCurrent);
            assertFalse(h.client.ready()); h.client.close();
        }
    }
    @Test void guardedOperationsNeverFallBackToLegacyImplementations() {
        var called = new AtomicBoolean();
        var legacy = new ControlClientIo.Synchronization() {
            @Override public long deadlineMillis() { return 1; }
            @Override public java.util.Optional<byte[]> pendingHeartbeat() { return java.util.Optional.empty(); }
            @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] original) { called.set(true); return null; }
            @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { called.set(true); return null; }
            @Override public void requireCurrent() { }
        };
        assertThrows(UnsupportedOperationException.class, () -> legacy.heartbeat(new byte[0], () -> { }));
        assertThrows(UnsupportedOperationException.class, () -> legacy.applied(null, () -> { }));
        assertFalse(called.get());
    }
    @Test void stateAppliedChecksAfterSigningAndWhenReadyArrives() throws Exception {
        for (String boundary : List.of("signed", "ready")) {
            var h = new ControlClientCoordinatorTest.Harness("websocket"); var owned = new AtomicBoolean(true);
            Runnable guard = () -> { if (!owned.get()) throw new IllegalStateException("candidate snapshot changed"); };
            h.autoReady = false;
            h.actualSynchronization = exchange -> {
                if (boundary.equals("signed")) {
                    var previous = h.identifierSupplier;
                    var count = new java.util.concurrent.atomic.AtomicInteger();
                    h.identifierSupplier = () -> { if (count.incrementAndGet() == 2) owned.set(false); return previous.get(); };
                }
                return exchange.applied(h.appliedBasis, guard);
            };
            h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
            if (boundary.equals("signed")) assertTrue(h.links.get(0).appliedFrames.isEmpty(), "snapshot changed during frame construction");
            else {
                assertEquals(1, h.links.get(0).appliedFrames.size());
                var frame = ControlFrameCodec.decode(h.links.get(0).appliedFrames.get(0));
                var ack = ControlStateCodec.decodeAcknowledgement(new String(frame.payloadBytes(), java.nio.charset.StandardCharsets.UTF_8));
                owned.set(false); h.links.get(0).readyReply(ack);
            }
            assertFalse(h.client.ready()); assertTrue(h.synchronizationResults.get(0).toCompletableFuture().isCompletedExceptionally());
            h.client.close();
        }
    }
}
