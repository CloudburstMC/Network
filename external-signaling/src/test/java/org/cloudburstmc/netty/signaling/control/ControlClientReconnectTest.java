package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.cloudburstmc.netty.signaling.control.ControlClientCoordinatorTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** A provider may retire its physical socket while a durable operation is unsettled. */
class ControlClientReconnectTest {
    private static final byte[] NOTICE = "{}".getBytes(StandardCharsets.UTF_8);

    @Test void noticeReplacesWriterWithoutClosingOldSocketBeforeActivationOrChangingPendingBody() throws Exception {
        var h = new Harness(); h.ready(); var old = h.links.get(0); var writer = h.writer;
        var result = h.client.submit("heartbeat", " { }\n".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var original = h.journal.value.pending();
        h.incoming(old, writer, "session.reconnect", NOTICE, 1);
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(original, h.journal.value.pending()); assertFalse(result.isDone());
        assertEquals(0, old.closeCalls + old.abortCalls);
        h.respondPrepare(); h.links.get(1).challenge();
        assertEquals(0, old.closeCalls + old.abortCalls);
        h.respondActivation(); h.synchronizedReady();
        assertEquals(writer.sessionEpoch() + 1, h.writer.sessionEpoch()); assertEquals(1, old.closeCalls);
        var sent = ControlFrameCodec.decode(h.links.get(1).sent.get(0));
        var retried = ControlLifecycleCodec.decodeWsRequest(new String(sent.payloadBytes(), StandardCharsets.UTF_8));
        assertEquals(original.intent(), retried.intent()); assertArrayEquals(original.bodyBytes(), retried.bodyBytes());
        assertTrue(h.operations.isEmpty()); assertEquals(0, h.httpAuthorityCalls);
        int bootstrapCalls = h.bootstrapCalls;
        h.incoming(old, writer, "session.reconnect", NOTICE, 2);
        assertEquals(bootstrapCalls, h.bootstrapCalls); assertTrue(h.client.ready());
        h.client.close();
    }

    @Test void noticeDuringCommittedKeyRotationReconcilesCandidateBeforeReplacingWriter() throws Exception {
        var h = new Harness(); h.ready(); var old = h.links.get(0); var writer = h.writer;
        var result = h.client.rotateMachineKey().toCompletableFuture(); var original = h.journal.value.pending();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.writer = new ControlWriterFence(writer.transport(), writer.sessionEpoch(), writer.sessionId(), writer.connectionId(),
                original.candidate().keyId(), writer.machineKeyRevision() + 1);
        h.incoming(old, writer, "session.reconnect", NOTICE, 1);
        assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state());
        assertEquals(original, h.journal.value.pending()); assertNotEquals(original.candidate(), h.journal.value.currentKey());
        assertTrue(h.requests.isEmpty()); assertEquals(1, old.abortCalls);
        h.time.advance(h.time.nextDelay());
        assertEquals(original.candidate().keyId(), h.requests.peek().request().authentication().keyId());
        h.respondStatus(); assertFalse(result.isDone()); h.respondStatus();
        assertEquals(receipt, result.join().receipt()); assertFalse(result.join().hasBody());
        assertNull(h.journal.value.pending()); assertEquals(original.candidate(), h.journal.value.currentKey());
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        assertEquals(writer.sessionEpoch() + 1, h.writer.sessionEpoch()); assertEquals(2, h.writer.machineKeyRevision());
        assertTrue(h.links.get(1).sent.isEmpty()); h.client.close();
    }

    @Test void noticeDuringUnknownKeyRotationRetainsCandidateAndExactIntentForNewWriter() throws Exception {
        var h = new Harness(); h.ready(); var old = h.links.get(0); var writer = h.writer;
        var oldKey = h.journal.value.currentKey(); h.client.rotateMachineKey(); var original = h.journal.value.pending();
        h.incoming(old, writer, "session.reconnect", NOTICE, 1);
        h.time.advance(h.time.nextDelay()); h.respondStatus(); // Candidate is not selected.
        assertEquals(oldKey.keyId(), h.requests.peek().request().authentication().keyId());
        h.respondStatus(); h.respondStatus(); // Positive old-key writer plus unknown receipt.
        assertEquals(oldKey, h.journal.value.currentKey()); assertEquals(original, h.journal.value.pending());
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        var retried = ControlLifecycleCodec.decodeWsRequest(new String(ControlFrameCodec.decode(h.links.get(1).sent.get(0)).payloadBytes(), StandardCharsets.UTF_8));
        assertEquals(original.intent(), retried.intent()); assertArrayEquals(original.bodyBytes(), retried.bodyBytes());
        assertEquals(original.candidate(), h.journal.value.pending().candidate());
        assertEquals(oldKey, h.journal.value.currentKey()); assertEquals(writer.sessionEpoch() + 1, h.writer.sessionEpoch());
        h.client.close();
    }

    @Test void noticeCannotOverlapOrEraseAnUnsettledDurableOutcomeAcknowledgement() throws Exception {
        var h = new Harness(); h.durableOutcomes = true; h.ready(); var old = h.links.get(0);
        var result = h.client.submit("outcomes", "[]".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.incoming(old, h.writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        var retained = h.journal.value.pending(); assertEquals(1, h.outcomeAcks.size());
        h.incoming(old, h.writer, "session.reconnect", NOTICE, 2);
        assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state());
        h.time.advance(h.time.nextDelay());
        assertTrue(h.requests.isEmpty()); assertEquals(1, h.outcomeAcks.size());
        assertEquals(retained, h.journal.value.pending()); assertFalse(result.isDone());
        h.outcomeAcks.get(0).complete(null);
        h.time.advance(h.time.nextDelay()); // Reapply only the idempotent local acknowledgement after settlement.
        assertEquals(2, h.outcomeAcks.size()); assertTrue(h.requests.isEmpty());
        assertEquals(1, old.sent.size()); h.outcomeAcks.get(1).complete(null);
        assertEquals(receipt, result.join().receipt()); assertNull(h.journal.value.pending());
        h.respondStatus(); h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        assertTrue(h.links.get(1).sent.isEmpty()); h.client.close();
    }
}
