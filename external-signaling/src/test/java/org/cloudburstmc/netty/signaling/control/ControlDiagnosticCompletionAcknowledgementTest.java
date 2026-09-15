package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticCompletionAcknowledgementTest {
    static final byte[] BODY = "{\"diagnosticCompletions\":{\"fixture\":true}}".getBytes(StandardCharsets.UTF_8);
    static final byte[] RESPONSE = "{\"diagnosticCompletions\":{\"fixtureReceipts\":true}}".getBytes(StandardCharsets.UTF_8);
    static ControlClientCoordinatorTest.Harness start(String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(mode); h.durableDiagnostics = true;
        h.cancelRoute = URI.create(ControlClientCoordinatorTest.ORIGIN + "/control/cancel-intent"); h.newClient();
        h.client.start(); h.respondStatus(); h.respondPrepare();
        if (mode.equals("websocket")) h.links.get(h.links.size()-1).challenge();
        h.respondActivation(); h.synchronizedReady(); return h;
    }
    static ControlLifecycleCodec.Receipt commit(ControlClientCoordinatorTest.Harness h) {
        var op = h.operations.get(h.operations.size()-1); var receipt = h.receipt("committed");
        op.reply().complete(new ControlClientIo.HttpReply(op.endpoint(), "POST", op.endpoint(), 200,
                ControlResultCodec.encode(ControlResultCodec.create(receipt, RESPONSE)))); return receipt;
    }
    @Test void bothCarriersKeepOriginalAndSignedResponseUntilDiagnosticSave() throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var h = start(mode); var result = h.client.submit("heartbeat", BODY, true); var receipt = commit(h);
            assertEquals(receipt, h.journal.value.pending().receipt()); assertArrayEquals(RESPONSE, h.diagnosticResponses.get(0));
            assertFalse(result.toCompletableFuture().isDone()); h.diagnosticAcks.get(0).complete(null);
            assertNull(h.journal.value.pending()); assertTrue(result.toCompletableFuture().join().hasBody()); h.client.close();
        }
    }
    @Test void ownerAndCompletionHooksComposeBeforeJournalClear() throws Exception {
        var h = start("https"); h.durableOwners = true; var result = h.client.submit("heartbeat", BODY, true); commit(h);
        assertEquals(1, h.ownerAcks.size()); assertTrue(h.diagnosticAcks.isEmpty()); h.ownerAcks.get(0).complete(null);
        assertEquals(1, h.diagnosticAcks.size()); assertNotNull(h.journal.value.pending()); assertFalse(result.toCompletableFuture().isDone());
        h.diagnosticAcks.get(0).complete(null); assertNull(h.journal.value.pending()); h.client.close();
    }
    @Test void failedSaveRetriesLocallyWithoutAnotherOperationAndWithBodylessRecovery() throws Exception {
        var h = start("https"); h.client.submit("heartbeat", BODY, true); commit(h); int network = h.bootstrapCalls;
        h.diagnosticAcks.get(0).completeExceptionally(new java.io.IOException("root save failed")); h.time.advance(200);
        assertEquals(2, h.diagnosticAcks.size()); assertNull(h.diagnosticResponses.get(1));
        assertEquals(network, h.bootstrapCalls); assertEquals(1, h.operations.size());
        h.diagnosticAcks.get(1).complete(null); assertNull(h.journal.value.pending()); h.client.close();
    }
    @Test void actualHookOccupancySurvivesTimeoutWithoutOverlap() throws Exception {
        var h = start("https"); h.client.submit("heartbeat", BODY, true); commit(h);
        h.time.advance(30001); h.time.advance(1000); h.client.reconcilePending(); assertEquals(1, h.diagnosticAcks.size());
        h.diagnosticAcks.get(0).complete(null); h.time.advance(200); assertEquals(2, h.diagnosticAcks.size());
        assertNull(h.diagnosticResponses.get(1)); h.diagnosticAcks.get(1).complete(null); h.client.close();
    }
    @Test void restartAfterCommitAndAfterFailedJournalClearRecoverReceiptWithoutFabricatingBody() throws Exception {
        for (boolean clearFailed : List.of(false, true)) {
            var h = start("https"); h.client.submit("heartbeat", BODY, true); commit(h);
            if (clearFailed) { h.journal.fail = true; h.diagnosticAcks.get(0).complete(null); }
            h.client.close(); h.journal.fail = false; int network = h.bootstrapCalls; h.newClient(); h.client.start();
            assertEquals(network, h.bootstrapCalls); assertEquals(2, h.diagnosticAcks.size()); assertNull(h.diagnosticResponses.get(1));
            h.diagnosticAcks.get(1).complete(null); assertNull(h.journal.value.pending()); assertEquals(1, h.operations.size()); h.client.close();
        }
    }
    @Test void strongStatusCommitReachesBodylessHookBeforeFreshWriter() throws Exception {
        var h = start("https"); h.client.submit("heartbeat", BODY, true); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, h.journal.value.pending().receipt()); assertEquals(1, h.diagnosticAcks.size()); assertNull(h.diagnosticResponses.get(0));
        h.diagnosticAcks.get(0).complete(null); assertNull(h.journal.value.pending()); assertEquals("prepare", h.requests.element().request().action()); h.client.close();
    }
    @Test void rejectedParentReceiptNeverAcknowledgesDiagnosticBytes() throws Exception {
        var h = start("https"); var result = h.client.submit("heartbeat", BODY, true); var op = h.operations.get(0); var receipt = h.receipt("rejected");
        op.reply().complete(new ControlClientIo.HttpReply(op.endpoint(), "POST", op.endpoint(), 200,
                ControlResultCodec.encode(ControlResultCodec.create(receipt, "{}".getBytes(StandardCharsets.UTF_8)))));
        assertTrue(h.diagnosticAcks.isEmpty()); assertNull(h.journal.value.pending()); assertFalse(result.toCompletableFuture().join().hasBody()); h.client.close();
    }
    @Test void losingCancellationRaceRetainsReportsUntilBodylessCommittedSettlement() throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var h = start(mode); h.cancelNativeClaims = true; var result = h.client.submit("heartbeat", BODY, true);
            h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
            if (h.requests.element().request().action().equals("prepare")) { h.respondPrepare(); h.respondActivation(); }
            var request = h.next("cancel-intent"); var receipt = h.receipt("committed");
            var body = new com.google.gson.JsonObject(); body.addProperty("intentDigest", receipt.intentDigest());
            body.add("receipt", ControlJson.parse(ControlLifecycleCodec.encodeReceipt(receipt), 2048));
            request.reply().complete(new ControlClientIo.HttpReply(request.endpoint(), "POST", request.endpoint(), 200,
                    h.responseWire(request.request(), "cancel-intent", body)));
            assertEquals(receipt, h.journal.value.pending().receipt()); assertFalse(result.toCompletableFuture().isDone());
            assertNull(h.diagnosticResponses.get(0)); h.diagnosticAcks.get(0).complete(null);
            assertNull(h.journal.value.pending()); assertFalse(result.toCompletableFuture().join().hasBody());
            assertEquals(1, h.operations.size()); h.client.close();
        }
    }

}
