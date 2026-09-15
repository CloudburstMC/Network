package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlLifecycleCarrierTest {
    private static ControlClientCoordinatorTest.Harness activated(String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(mode); h.client.start(); h.respondStatus(); h.respondPrepare();
        if (mode.equals("websocket")) h.links.get(0).challenge(); h.respondActivation(); h.respondAuthority(); return h;
    }
    private static byte[] body(int size) { return ("{\"padding\":\"" + "x".repeat(size) + "\"}").getBytes(StandardCharsets.UTF_8); }
    private static void websocketReply(ControlClientCoordinatorTest.Harness h, ControlLifecycleCodec.Receipt receipt) throws Exception {
        var link = h.links.get(h.links.size()-1);
        h.incomingActual(link, h.writer, "lifecycle.receipt", ControlClientCoordinatorTest.resultWire(receipt).getBytes(StandardCharsets.UTF_8), link.nextProviderSequence);
    }
    @Test void eachCarrierAndOversizedFallbackKeepsOriginalSynchronizationDeadline() throws Exception {
        for (String mode : List.of("https", "websocket", "oversized-websocket")) {
            var h = activated(mode.equals("https") ? "https" : "websocket"); var lane = h.synchronizationExchanges.get(0); var grant = h.client.snapshot().grant();
            h.time.advance(29000); lane.heartbeat(body(mode.startsWith("oversized") ? 45056 : 0));
            if (mode.equals("websocket")) {
                assertTrue(h.operations.isEmpty()); var frame = ControlFrameCodec.decode(h.links.get(0).sent.get(0));
                assertEquals(lane.deadlineMillis(), frame.expiresAt()); assertEquals(1000, frame.expiresAt()-frame.sentAt());
            } else {
                assertEquals(1, h.operations.size()); assertEquals(lane.deadlineMillis(), h.operations.get(0).request().expiresAt());
                if (!h.links.isEmpty()) assertTrue(h.links.get(0).sent.isEmpty());
            }
            assertEquals(grant, h.client.snapshot().grant()); assertEquals(1, h.journal.value.lastSequence());
            h.time.advance(1001); assertThrows(IllegalStateException.class, () -> lane.heartbeat(body(0))); assertNotNull(h.journal.value.pending());
        }
    }
    @Test void periodicHeartbeatPastUpgradeDeliveryWindowUsesTheSameSignedSocket() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.ready(); var originalWriter = h.writer; var link = h.links.get(0);
        h.time.advance(31000); h.client.synchronize(); h.respondAuthority(); var lane = h.synchronizationExchanges.get(1);
        var result = lane.heartbeat(body(0));
        assertTrue(h.operations.isEmpty()); assertEquals(1, link.sent.size());
        var frame = ControlFrameCodec.decode(link.sent.get(0)); assertTrue(frame.sentAt() > link.upgrade.expiresAt()); assertEquals(originalWriter.sessionId(), frame.sessionId());
        websocketReply(h, h.receipt("committed")); assertTrue(result.toCompletableFuture().join().hasBody()); assertFalse(h.client.ready());
        h.synchronizations.get(1).complete(null); assertTrue(h.client.ready()); assertEquals(originalWriter, h.client.snapshot().writer()); assertEquals(1, h.links.size());
    }
    @Test void failedWebsocketHandoffDoesNotFallBackToHttp() throws Exception {
        var h = activated("websocket"); var lane = h.synchronizationExchanges.get(0); var link = h.links.get(0);
        link.lifecycleSend = CompletableFuture.failedFuture(new java.io.IOException("send failed")); lane.heartbeat(body(0));
        assertTrue(h.operations.isEmpty()); assertEquals(1, link.sent.size()); assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state());
        assertNotNull(h.journal.value.pending()); assertEquals(1, h.journal.value.lastSequence()); assertFalse(h.client.ready());
    }
    @Test void lostWebsocketReceiptReconcilesOriginalIntentWithoutHttpReplayAndFencesOldSocket() throws Exception {
        var h = activated("websocket"); var lane = h.synchronizationExchanges.get(0); var oldLink = h.links.get(0); var oldWriter = h.writer;
        var result = lane.heartbeat(body(0)); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        oldLink.closed.completeExceptionally(new java.io.IOException("socket lost after commit"));
        h.time.advance(200); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, result.toCompletableFuture().join().receipt()); assertFalse(result.toCompletableFuture().join().hasBody());
        h.synchronizations.get(0).completeExceptionally(new IllegalStateException("old application pass expired"));
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        var current = h.client.snapshot(); assertNotEquals(oldWriter, current.writer()); assertTrue(h.operations.isEmpty()); assertEquals(1, current.lastSequence());
        h.incomingActual(oldLink, oldWriter, "lifecycle.receipt", ControlClientCoordinatorTest.resultWire(receipt).getBytes(StandardCharsets.UTF_8), oldLink.nextProviderSequence);
        assertEquals(current, h.client.snapshot()); assertTrue(h.client.ready()); assertEquals(1, oldLink.sent.size()); assertTrue(h.links.get(1).sent.isEmpty());
    }
    @Test void lostReplyAloneReconcilesWithoutReplacingHealthySocketOrReplayingBody() throws Exception {
        var h = activated("websocket"); var lane = h.synchronizationExchanges.get(0); var writer = h.writer;
        var result = lane.heartbeat(body(0)); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.time.advance(30001); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, result.toCompletableFuture().join().receipt()); assertFalse(result.toCompletableFuture().join().hasBody());
        h.synchronizations.get(0).completeExceptionally(new IllegalStateException("expired original application pass"));
        h.time.advance(1000); h.synchronizedReady();
        assertEquals(writer, h.client.snapshot().writer()); assertEquals(1, h.links.size()); assertTrue(h.operations.isEmpty()); assertEquals(1, h.links.get(0).sent.size());
    }
    @Test void websocketOutcomesRetainTheirDurableApplicationBarrier() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.durableOutcomes = true; h.ready();
        var result = h.client.submit("outcomes", "{\"events\":[]}".getBytes(StandardCharsets.UTF_8), false);
        assertTrue(h.operations.isEmpty()); websocketReply(h, h.receipt("committed"));
        assertFalse(result.toCompletableFuture().isDone()); assertNotNull(h.journal.value.pending().receipt());
        h.outcomeAcks.get(0).complete(null); assertNull(h.journal.value.pending()); assertFalse(result.toCompletableFuture().join().hasBody());
    }
}
