package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import static org.junit.jupiter.api.Assertions.*;

class ControlNativeIntentCancellationTest {
    private static final byte[] BODY = "{\"acceptingPlayers\":true,\"applicationAck\":{\"previous\":\"native-instance\"}}".getBytes(StandardCharsets.UTF_8);
    private static ControlClientCoordinatorTest.Harness started(String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(mode); h.cancelNativeClaims = true;
        h.cancelRoute = URI.create(ControlClientCoordinatorTest.ORIGIN + "/control/cancel-intent"); h.newClient();
        h.client.start(); h.respondStatus(); h.respondPrepare();
        if (mode.equals("websocket")) h.links.get(h.links.size()-1).challenge();
        h.respondActivation(); h.synchronizedReady(); return h;
    }
    private static CompletionStage<ControlOperationResult> submit(ControlClientCoordinatorTest.Harness h) {
        return h.client.submit("heartbeat", BODY, true);
    }
    private static void restartToCancellation(ControlClientCoordinatorTest.Harness h) throws Exception {
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        h.respondPrepare(); if (h.initialTransport.equals("websocket")) h.links.get(h.links.size()-1).challenge(); h.respondActivation();
    }
    private static JsonObject result(ControlLifecycleCodec.Receipt receipt) {
        var payload = new JsonObject(); payload.addProperty("intentDigest", receipt.intentDigest());
        payload.add("receipt", ControlJson.parse(ControlLifecycleCodec.encodeReceipt(receipt), ControlLifecycleCodec.MAX_INTENT_BYTES)); return payload;
    }
    private static void reply(ControlClientCoordinatorTest.Harness h, ControlClientCoordinatorTest.Exchange request, ControlLifecycleCodec.Receipt receipt) throws Exception {
        request.reply().complete(new ControlClientIo.HttpReply(request.endpoint(), "POST", request.endpoint(), 200, h.responseWire(request.request(), "cancel-intent", result(receipt))));
    }
    @Test void eachCarrierReplacesLostWriterThenCancelsWithoutReadyOrBodyReplay() throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var h = started(mode); submit(h); long sequence = h.journal.value.lastSequence(); var original = h.journal.value.pending().intent();
            restartToCancellation(h); var request = h.next("cancel-intent");
            assertEquals(1, h.operations.size()); assertTrue(h.authorityRequests.isEmpty()); assertFalse(h.client.ready());
            var payload = ControlSessionPayloadCodec.decodeRequest("cancel-intent", request.request().payloadBytes());
            assertEquals(original, ControlLifecycleCodec.readIntent(payload.getAsJsonObject("intent"))); assertEquals(h.writer, ControlWriterFence.read(payload.getAsJsonObject("expectedWriter")));
            reply(h, request, h.receipt("cancelled")); assertNull(h.journal.value.pending()); assertEquals(sequence, h.journal.value.lastSequence());
            assertEquals(1, h.authorityRequests.size()); assertFalse(h.client.ready()); h.synchronizedReady(); assertTrue(h.client.ready());
            assertEquals(1, h.operations.size());
        }
    }
    @Test void originalCommitWinningCancellationOnlyReturnsReceiptAndFreshSynchronization() throws Exception {
        var h = started("websocket"); var result = submit(h); h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        var request = h.next("cancel-intent"); var receipt = h.receipt("committed"); reply(h, request, receipt);
        assertEquals(receipt, result.toCompletableFuture().join().receipt()); assertFalse(result.toCompletableFuture().join().hasBody());
        assertNull(h.journal.value.pending()); assertFalse(h.client.ready()); assertEquals(1, h.operations.size()); assertEquals(1, h.authorityRequests.size());
    }
    @Test void committedStatusSettlesBeforeAnyCancellationOrNativeReplay() throws Exception {
        var h = started("websocket"); submit(h); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertNull(h.journal.value.pending()); assertEquals("prepare", h.requests.element().request().action()); assertEquals(1, h.operations.size());
    }
    @Test void lostCancellationAckReconcilesTerminalStatusAcrossRestart() throws Exception {
        var h = started("https"); submit(h); restartToCancellation(h); h.next("cancel-intent");
        var receipt = h.receipt("cancelled"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertNull(h.journal.value.pending()); assertEquals("prepare", h.requests.element().request().action()); assertEquals(1, h.operations.size());
    }
    @Test void missingOptionalRouteRetainsUnknownIntentWithoutBootstrapOrReplay() throws Exception {
        var h = started("websocket"); submit(h); var pending = h.journal.value.pending(); h.cancelRoute = null;
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertEquals(pending, h.journal.value.pending());
        assertTrue(h.requests.isEmpty()); assertTrue(h.authorityRequests.isEmpty()); assertEquals(1, h.operations.size());
    }
    @Test void unavailableLateWrongTargetAndRetiredProviderKeyNeverReleasePending() throws Exception {
        for (String failure : List.of("unavailable", "late", "wrong-target", "retired-key")) {
            var h = started("https"); submit(h); restartToCancellation(h); var request = h.next("cancel-intent"); var receipt = h.receipt("cancelled");
            var retained = h.journal.value.pending(); String wire = h.responseWire(request.request(), "cancel-intent", result(receipt));
            if (failure.equals("late")) h.time.advance(30001);
            if (failure.equals("retired-key")) h.keyAvailable = false;
            var responseUri = failure.equals("wrong-target") ? URI.create(request.endpoint() + "/other") : request.endpoint();
            request.reply().complete(new ControlClientIo.HttpReply(request.endpoint(), "POST", responseUri, failure.equals("unavailable") ? 503 : 200, wire));
            assertEquals(retained, h.journal.value.pending(), failure); assertFalse(h.client.ready()); assertTrue(h.authorityRequests.isEmpty()); assertEquals(1, h.operations.size());
        }
    }
    @Test void incomingFrameCannotRaceCancellationAheadOfStrongStatus() throws Exception {
        var h = started("websocket"); submit(h); var link = h.links.get(0); h.client.reconcilePending();
        h.incoming(link, h.writer, "state.desired", "{}".getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(1, h.requests.size()); assertEquals("status", h.requests.element().request().action()); assertTrue(h.authorityRequests.isEmpty());
        h.respondStatus(); assertEquals("status", h.requests.element().request().action());
        h.respondStatus(); assertEquals("cancel-intent", h.requests.element().request().action()); assertEquals(1, h.operations.size());
    }
    @Test void repeatedUnavailableCancellationIsBoundedAndNeverReplaysOriginal() throws Exception {
        var h = started("https"); submit(h); restartToCancellation(h); var original = h.journal.value.pending().intent();
        for (int cycle = 0; cycle < 3; cycle++) {
            var request = h.next("cancel-intent");
            request.reply().complete(new ControlClientIo.HttpReply(request.endpoint(), "POST", request.endpoint(), 503, "unavailable"));
            assertTrue(h.requests.isEmpty()); assertEquals(original, h.journal.value.pending().intent());
            h.time.advance(2000); h.respondStatus(); h.respondStatus(); h.respondPrepare(); h.respondActivation();
            assertEquals(1, h.operations.size()); assertTrue(h.authorityRequests.isEmpty()); assertFalse(h.client.ready());
        }
    }
    @Test void terminalStatusCallbackMayQueueAnotherIntentWithoutOldCancellationMarker() throws Exception {
        for (String disposition : List.of("committed", "cancelled")) {
            var h = started("websocket"); var original = submit(h); var receipt = h.receipt(disposition); h.receipts.put(receipt.intentDigest(), receipt);
            var next = new java.util.concurrent.atomic.AtomicReference<CompletionStage<ControlOperationResult>>();
            original.whenComplete((ignored, failure) -> next.set(h.client.submit("outcomes", "{}".getBytes(StandardCharsets.UTF_8), true)));
            h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
            assertNotEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertNotNull(next.get());
            h.synchronizedReady(); assertEquals("outcomes", h.journal.value.pending().intent().operation());
            assertEquals(2, h.operations.size()); assertTrue(h.requests.isEmpty());
        }
    }
    @Test void nativeReconciliationFencesHeldOperationAndAllowsNextHeartbeat() throws Exception {
        var h = started("websocket"); submit(h); var old = h.operations.get(0);
        h.client.synchronize(); h.respondAuthority();
        h.synchronizations.get(h.synchronizations.size()-1).completeExceptionally(new ControlClientIo.ReconciliationRequired("native instance replaced"));
        var request = h.next("cancel-intent"); var receipt = h.receipt("cancelled"); reply(h, request, receipt);
        h.respondAuthority(); var lane = h.synchronizationExchanges.get(h.synchronizationExchanges.size()-1); lane.heartbeat("{}".getBytes(StandardCharsets.UTF_8));
        var pending = h.journal.value.pending();
        assertEquals(2, h.operations.size() + h.links.get(0).sent.size());
        old.reply().complete(new ControlClientIo.HttpReply(old.endpoint(), "POST", old.endpoint(), 200, ControlClientCoordinatorTest.resultWire(receipt)));
        assertEquals(pending, h.journal.value.pending());
    }
    @Test void explicitReplacementCannotAbandonOwnedCancellationIo() throws Exception {
        var h = started("websocket"); submit(h); h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        var request = h.next("cancel-intent"); var writer = h.client.snapshot().writer();
        assertThrows(IllegalStateException.class, () -> h.client.replaceTransport("https", List.of("request-response")));
        assertEquals(writer, h.client.snapshot().writer()); assertTrue(h.requests.isEmpty());
        reply(h, request, h.receipt("cancelled")); assertNull(h.journal.value.pending()); assertEquals(1, h.authorityRequests.size());
    }
    @Test void reentrantCloseAfterTerminalReceiptPreventsReadinessContinuation() throws Exception {
        var h = started("websocket"); var result = submit(h); h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        var request = h.next("cancel-intent");
        result.whenComplete((ignored, failure) -> { try { h.client.close(); } catch (Exception error) { throw new RuntimeException(error); } });
        reply(h, request, h.receipt("cancelled"));
        assertNull(h.journal.value.pending()); assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertTrue(h.authorityRequests.isEmpty());
    }
}
