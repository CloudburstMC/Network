package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Coordinator guard propagation; the independent JDK tests exercise the real bounded queue. */
class ControlQueuedSendOwnershipTest {
    @Test void queuedHeartbeatRetainsOriginalApplicationAndPhysicalAuthorityUntilHandoff() throws Exception {
        for (String change : List.of("none", "installation", "deadline", "key", "close")) {
            var h = new ControlClientCoordinatorTest.Harness("websocket");
            h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
            var link = h.links.get(0); link.queueHandoffs = true;
            var current = new AtomicBoolean(true); var lane = h.synchronizationExchanges.get(0);
            var pending = lane.heartbeat("{}".getBytes(StandardCharsets.UTF_8), () -> {
                if (!current.get()) throw new IllegalStateException("diagnostic installation withdrawn");
            }).toCompletableFuture();
            var original = h.client.snapshot().pending(); var writer = h.client.snapshot().writer();
            assertNotNull(original); assertTrue(link.sent.isEmpty()); assertEquals(1, link.handoffs.size());
            switch (change) {
                case "installation" -> current.set(false);
                case "deadline" -> h.time.now = lane.deadlineMillis(); // Do not run timers: the handoff owns this check.
                case "key" -> h.keyAvailable = false;
                case "close" -> h.client.close();
            }
            link.handoffs.remove().run();
            assertEquals(change.equals("none") ? 1 : 0, link.sent.size(), change);
            assertEquals(original, h.journal.value.pending()); assertEquals(writer, h.journal.value.writer());
            if (change.equals("none")) {
                var frame = ControlFrameCodec.decode(link.sent.get(0));
                var request = ControlLifecycleCodec.decodeWsRequest(new String(frame.payloadBytes(), StandardCharsets.UTF_8));
                assertEquals(original.intent(), request.intent()); assertArrayEquals(original.bodyBytes(), request.bodyBytes());
                h.incomingActual(link, writer, "lifecycle.receipt", ControlClientCoordinatorTest.resultWire(h.receipt("committed")).getBytes(StandardCharsets.UTF_8), link.nextProviderSequence);
                assertEquals("committed", pending.join().receipt().disposition()); assertNull(h.journal.value.pending());
            } else {
                assertTrue(pending.isCompletedExceptionally(), change); assertTrue(link.closed.isDone());
                assertFalse(h.client.ready()); assertTrue(h.operations.isEmpty());
            }
            h.client.close();
        }
    }

    @Test void queuedReadinessConfirmationChecksApplicationAgainAndHealthyConfirmationCompletes() throws Exception {
        for (String change : List.of("none", "installation", "deadline", "key", "close")) {
            var h = new ControlClientCoordinatorTest.Harness("websocket"); var current = new AtomicBoolean(true);
            h.actualSynchronization = lane -> {
                h.links.get(0).queueHandoffs = true;
                return lane.applied(h.appliedBasis, () -> {
                    if (!current.get()) throw new IllegalStateException("installation changed");
                });
            };
            h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
            var link = h.links.get(0); assertFalse(h.client.ready()); assertTrue(link.appliedFrames.isEmpty());
            assertEquals(1, link.handoffs.size());
            switch (change) {
                case "installation" -> current.set(false);
                case "deadline" -> h.time.now = h.synchronizationExchanges.get(0).deadlineMillis();
                case "key" -> h.keyAvailable = false;
                case "close" -> h.client.close();
            }
            link.handoffs.remove().run();
            assertEquals(change.equals("none") ? 1 : 0, link.appliedFrames.size(), change);
            assertEquals(change.equals("none"), h.client.ready(), change);
            if (!change.equals("none")) assertTrue(h.synchronizationResults.get(0).toCompletableFuture().isCompletedExceptionally());
            h.client.close();
        }
    }

    @Test void queuedAuthorityRequestRetainsOriginalDeadlineAndLinkWithoutHttpFallback() throws Exception {
        for (String change : List.of("none", "deadline", "close")) {
            var h = new ControlClientCoordinatorTest.Harness("websocket");
            h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge();
            var link = h.links.get(0); link.queueHandoffs = true; h.respondActivation();
            assertEquals(1, link.handoffs.size()); assertTrue(link.authorityWires.isEmpty());
            if (change.equals("deadline")) h.time.now += 30000; else if (change.equals("close")) h.client.close();
            link.handoffs.remove().run();
            assertEquals(change.equals("none") ? 1 : 0, link.authorityWires.size(), change);
            assertEquals(0, h.httpAuthorityCalls); assertNull(h.journal.value.authorityFloor());
            if (change.equals("none")) {
                link.queueHandoffs = false; h.respondAuthority(); h.synchronizedReady(); assertTrue(h.client.ready());
            } else { assertTrue(link.closed.isDone()); assertTrue(h.synchronizations.isEmpty()); }
            h.client.close();
        }
    }

    @Test void queuedApplicationGuardCannotReenterCloseOrDeadlineBeforeFinalSend() throws Exception {
        for (boolean deadline : List.of(false, true)) {
            var h = new ControlClientCoordinatorTest.Harness("websocket");
            h.client.start(); h.respondStatus(); ControlApplicationRecoveryTest.activate(h);
            var link = h.links.get(0); link.queueHandoffs = true;
            var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
            var lane = h.synchronizationExchanges.get(0);
            lane.heartbeat("{}".getBytes(StandardCharsets.UTF_8), () -> callback.getAndSet(() -> {}).run());
            callback.set(() -> { if (deadline) h.time.now = lane.deadlineMillis(); else assertDoesNotThrow(h.client::close); });
            link.handoffs.remove().run();
            assertTrue(link.sent.isEmpty()); assertNotNull(h.journal.value.pending()); assertFalse(h.client.ready());
            h.client.close();
        }
    }

    @Test void aLinkWithoutActualHandoffGuardsCannotSilentlyDowngrade() {
        var unguarded = new AtomicBoolean();
        var legacy = new ControlClientIo.Link() {
            public CompletionStage<Void> opened() { return CompletableFuture.completedFuture(null); }
            public CompletionStage<?> closed() { return new CompletableFuture<>(); }
            public CompletionStage<Void> sendText(String wire) { unguarded.set(true); return CompletableFuture.completedFuture(null); }
            public void close() { }
            public void abort() { }
        };
        assertThrows(UnsupportedOperationException.class, () -> legacy.sendText("claim", () -> {}));
        assertFalse(unguarded.get());
    }
}
