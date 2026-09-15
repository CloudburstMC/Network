package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlOutcomeAcknowledgementTest {
    private static CompletionStage<ControlOperationResult> submit(ControlClientCoordinatorTest.Harness h) {
        return h.client.submit("outcomes", "{\"events\":[]}".getBytes(StandardCharsets.UTF_8), true);
    }
    private static ControlLifecycleCodec.Receipt commit(ControlClientCoordinatorTest.Harness h) {
        var operation = h.operations.get(h.operations.size() - 1); var receipt = h.receipt("committed");
        operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 200, ControlClientCoordinatorTest.resultWire(receipt)));
        return receipt;
    }
    @Test void committedReceiptPrecedesLocalAckAndIntentRelease() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready();
        var result = submit(h); var receipt = commit(h);
        assertEquals(receipt, h.journal.value.pending().receipt()); assertFalse(result.toCompletableFuture().isDone());
        assertThrows(IllegalStateException.class, () -> submit(h)); assertEquals(1, h.outcomeAcks.size());
        h.outcomeAcks.get(0).complete(null);
        assertNull(h.journal.value.pending()); assertEquals(receipt, result.toCompletableFuture().join().receipt());
        assertFalse(result.toCompletableFuture().join().hasBody()); assertEquals(1, h.operations.size());
    }
    @Test void restartAfterReceiptBeforeApplicationAckUsesOnlyLocalWork() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); submit(h); commit(h);
        var retained = h.journal.value; h.client.close(); int network = h.bootstrapCalls;
        h.newClient(); h.client.start();
        assertEquals(network, h.bootstrapCalls); assertEquals(2, h.outcomeAcks.size()); assertEquals(retained.pending(), h.journal.value.pending());
        h.outcomeAcks.get(0).complete(null); assertNotNull(h.journal.value.pending());
        h.outcomeAcks.get(1).complete(null); assertNull(h.journal.value.pending()); assertEquals(network + 1, h.bootstrapCalls);
        assertEquals(1, h.operations.size());
    }
    @Test void saveFailureRetriesLocalAckWithoutRetransmitting() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); submit(h); commit(h);
        int network = h.bootstrapCalls;
        h.outcomeAcks.get(0).completeExceptionally(new java.io.IOException("root save failed"));
        h.time.advance(200); assertEquals(2, h.outcomeAcks.size()); assertEquals(network, h.bootstrapCalls); assertEquals(1, h.operations.size());
        h.outcomeAcks.get(1).complete(null); assertNull(h.journal.value.pending());
    }
    @Test void timeoutDoesNotReleaseUnderlyingApplicationLane() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); submit(h); commit(h);
        int network = h.bootstrapCalls;
        h.time.advance(30001); h.time.advance(1000); h.client.reconcilePending();
        assertEquals(1, h.outcomeAcks.size()); assertEquals(network, h.bootstrapCalls); assertNotNull(h.journal.value.pending());
        h.outcomeAcks.get(0).complete(null); h.time.advance(200);
        assertEquals(2, h.outcomeAcks.size()); assertEquals(network, h.bootstrapCalls);
        h.outcomeAcks.get(1).complete(null); assertNull(h.journal.value.pending());
    }
    @Test void statusCommittedOutcomeMustApplyBeforeBootstrap() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); submit(h);
        var intent = h.journal.value.pending().intent(); var receipt = h.receipt("committed");
        h.receipts.put(ControlLifecycleCodec.intentDigest(intent), receipt);
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertEquals(1, h.outcomeAcks.size()); assertEquals(receipt, h.journal.value.pending().receipt()); assertTrue(h.requests.isEmpty());
        h.outcomeAcks.get(0).complete(null); assertNull(h.journal.value.pending()); assertEquals("prepare", h.requests.element().request().action());
        assertEquals(1, h.operations.size());
    }
    @Test void reentrantCloseDuringHookNeverClearsRetainedReceipt() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); var result = submit(h);
        h.onOutcomeAck = () -> { try { h.client.close(); } catch (Exception failure) { throw new RuntimeException(failure); } };
        commit(h); h.outcomeAcks.get(0).complete(null);
        assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertNotNull(h.journal.value.pending().receipt());
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
    }
    @Test void applicationSavedButJournalClearFailedReplaysOnlyIdempotentAckAfterRestart() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); submit(h); commit(h);
        // The application future has successfully saved; the following journal clear fails.
        h.journal.fail = true; h.outcomeAcks.get(0).complete(null);
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertNotNull(h.journal.value.pending().receipt());
        h.client.close(); h.journal.fail = false; int network = h.bootstrapCalls;
        h.newClient(); h.client.start(); assertEquals(network, h.bootstrapCalls); assertEquals(2, h.outcomeAcks.size());
        h.outcomeAcks.get(1).complete(null); assertNull(h.journal.value.pending()); assertEquals(1, h.operations.size());
    }
    @Test void reentrantPublicCompletionCannotStartOldContinuation() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready(); var result = submit(h); commit(h);
        result.whenComplete((ignored, failure) -> { try { h.client.close(); } catch (Exception error) { throw new RuntimeException(error); } });
        int network = h.bootstrapCalls; h.outcomeAcks.get(0).complete(null);
        assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertNull(h.journal.value.pending()); assertEquals(network, h.bootstrapCalls);
    }
    @Test void reconciliationRequiredDoesNotClearIntent() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.ready();
        h.client.synchronize(); h.respondAuthority(); var exchange = h.synchronizationExchanges.get(h.synchronizationExchanges.size()-1);
        exchange.heartbeat("{}".getBytes(StandardCharsets.UTF_8)); var retained = h.journal.value.pending();
        h.synchronizations.get(h.synchronizations.size()-1).completeExceptionally(new ControlClientIo.ReconciliationRequired("old native claim"));
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertEquals(retained, h.journal.value.pending());
    }
}
