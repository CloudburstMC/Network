package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class ControlNativeOwnerAcknowledgementTest {
    private static final byte[] BODY = "{\"nativeOwnerClaim\":{\"fixture\":true},\"hostProfile\":{},\"acceptingPlayers\":false}".getBytes(StandardCharsets.UTF_8);
    private static ControlClientCoordinatorTest.Harness started(String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(mode); h.durableOwners = true; h.cancelNativeClaims = true;
        h.cancelRoute = URI.create(ControlClientCoordinatorTest.ORIGIN + "/control/cancel-intent"); h.newClient();
        h.client.start(); h.respondStatus(); h.respondPrepare();
        if (mode.equals("websocket")) h.links.get(h.links.size()-1).challenge();
        h.respondActivation(); h.synchronizedReady(); return h;
    }
    private static CompletionStage<ControlOperationResult> submit(ControlClientCoordinatorTest.Harness h) {
        return h.client.submit("heartbeat", BODY, true);
    }
    private static ControlLifecycleCodec.Receipt commit(ControlClientCoordinatorTest.Harness h) {
        var operation = h.operations.get(h.operations.size()-1); var receipt = h.receipt("committed");
        operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 200,
                ControlResultCodec.encode(ControlResultCodec.create(receipt, "{\"nativeOwner\":\"owned-body\"}".getBytes(StandardCharsets.UTF_8)))));
        return receipt;
    }
    @Test void eachCarrierRetainsCommittedClaimUntilDurableSaveBeforeDeliveringLiveBody() throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var h = started(mode); var result = submit(h); var receipt = commit(h);
            assertEquals(receipt, h.journal.value.pending().receipt()); assertArrayEquals(BODY, h.journal.value.pending().bodyBytes());
            assertFalse(result.toCompletableFuture().isDone()); assertEquals(1, h.ownerAcks.size());
            assertThrows(IllegalStateException.class, () -> h.client.replaceTransport("https", List.of("request-response")));
            h.ownerAcks.get(0).complete(null);
            assertNull(h.journal.value.pending()); assertTrue(result.toCompletableFuture().join().hasBody());
            assertEquals("{\"nativeOwner\":\"owned-body\"}", new String(result.toCompletableFuture().join().bodyBytes().orElseThrow(), StandardCharsets.UTF_8));
            assertEquals(1, h.operations.size()); h.client.close();
        }
    }
    @Test void failedSaveAndCallbackTimeoutKeepOriginalAndNeverRetransmitCommit() throws Exception {
        for (String failure : List.of("save", "timeout")) {
            var h = started("https"); submit(h); commit(h); int network = h.bootstrapCalls;
            if (failure.equals("save")) h.ownerAcks.get(0).completeExceptionally(new java.io.IOException("root fsync failure"));
            else {
                h.time.advance(30001); h.time.advance(1000); h.client.reconcilePending();
                assertEquals(1, h.ownerAcks.size()); assertEquals(network, h.bootstrapCalls);
                h.ownerAcks.get(0).complete(null);
            }
            h.time.advance(200); assertEquals(2, h.ownerAcks.size()); assertEquals(network, h.bootstrapCalls);
            assertNotNull(h.journal.value.pending().receipt()); h.ownerAcks.get(1).complete(null);
            assertNull(h.journal.value.pending()); assertEquals(1, h.operations.size()); h.client.close();
        }
    }
    @Test void restartAfterSavedReceiptOrFailedJournalClearOnlyRetriesHistoricalAcknowledgement() throws Exception {
        for (boolean clearFailure : List.of(false, true)) {
            var h = started("https"); submit(h); commit(h);
            if (clearFailure) { h.journal.fail = true; h.ownerAcks.get(0).complete(null); assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); }
            h.client.close(); h.journal.fail = false; int network = h.bootstrapCalls; h.newClient(); h.client.start();
            assertEquals(network, h.bootstrapCalls); assertEquals(2, h.ownerAcks.size()); assertNotNull(h.journal.value.pending().receipt());
            if (!clearFailure) { h.ownerAcks.get(0).complete(null); assertNotNull(h.journal.value.pending()); }
            h.ownerAcks.get(1).complete(null); assertNull(h.journal.value.pending()); assertEquals(network + 1, h.bootstrapCalls); assertEquals(1, h.operations.size()); h.client.close();
        }
    }
    @Test void strongStatusCommitWaitsForHistoryBeforeSelectingWriter() throws Exception {
        var h = started("https"); submit(h); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, h.journal.value.pending().receipt()); assertTrue(h.requests.isEmpty()); assertEquals(1, h.ownerAcks.size());
        h.ownerAcks.get(0).complete(null); assertNull(h.journal.value.pending()); assertEquals("prepare", h.requests.element().request().action()); assertEquals(1, h.operations.size()); h.client.close();
    }
    @Test void committedCancellationLosingRaceWaitsForHistoryAndReturnsReceiptOnly() throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var h = started(mode); var result = submit(h); h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
            if (h.requests.element().request().action().equals("prepare")) { h.respondPrepare(); h.respondActivation(); }
            var request = h.next("cancel-intent"); var receipt = h.receipt("committed");
            var body = new JsonObject(); body.addProperty("intentDigest", receipt.intentDigest()); body.add("receipt", ControlJson.parse(ControlLifecycleCodec.encodeReceipt(receipt), 2048));
            request.reply().complete(new ControlClientIo.HttpReply(request.endpoint(), "POST", request.endpoint(), 200, h.responseWire(request.request(), "cancel-intent", body)));
            assertEquals(receipt, h.journal.value.pending().receipt()); assertFalse(result.toCompletableFuture().isDone()); assertTrue(h.authorityRequests.isEmpty());
            h.ownerAcks.get(0).complete(null); assertNull(h.journal.value.pending()); assertFalse(result.toCompletableFuture().join().hasBody());
            assertEquals(1, h.authorityRequests.size()); assertFalse(h.client.ready()); assertEquals(1, h.operations.size()); h.client.close();
        }
    }
    @Test void expiryWhileDurableCallbackIsRunningNeverDeliversOldBody() throws Exception {
        var h = started("https"); var result = submit(h); commit(h);
        h.time.advance(30001); h.ownerAcks.get(0).complete(null); h.time.advance(200); h.ownerAcks.get(1).complete(null);
        assertFalse(result.toCompletableFuture().join().hasBody()); assertNull(h.journal.value.pending()); assertEquals(1, h.operations.size()); h.client.close();
    }
    @Test void epochConflictIsNotTerminalAndRequiresStrongCancellation() throws Exception {
        var h = started("https"); submit(h); var pending = h.journal.value.pending(); var operation = h.operations.get(0);
        operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 409, "native_owner_epoch_conflict"));
        assertEquals(pending, h.journal.value.pending()); assertTrue(h.ownerAcks.isEmpty());
        h.time.advance(2000); h.respondStatus(); h.respondStatus(); h.respondPrepare(); h.respondActivation();
        assertEquals("cancel-intent", h.requests.element().request().action()); assertEquals(pending, h.journal.value.pending()); assertEquals(1, h.operations.size()); h.client.close();
    }
}
