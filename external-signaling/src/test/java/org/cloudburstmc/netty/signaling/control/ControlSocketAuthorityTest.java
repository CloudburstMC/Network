package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ControlSocketAuthorityTest {
    private static ControlClientCoordinatorTest.Harness activated(boolean heldSend) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness();
        h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge();
        if (heldSend) h.links.get(0).authoritySend = new CompletableFuture<>();
        h.respondActivation(); return h;
    }

    @Test void initialProofUsesExactSocketWithoutHttpOrFrameSequenceAndCannotCreateReadiness() throws Exception {
        var h = activated(false); var link = h.links.get(0);
        assertEquals(0, h.httpAuthorityCalls); assertEquals(1, link.authorityWires.size());
        assertTrue(link.sent.isEmpty()); assertTrue(link.appliedFrames.isEmpty()); assertTrue(h.synchronizations.isEmpty());
        var request = ControlAuthorityCodec.decodeRequest(link.authorityWires.get(0));
        assertEquals(h.writer, request.writer()); assertEquals("POST", request.method());
        assertEquals("/control/authority", request.encodedPathAndQuery());
        assertTrue(link.authorityWires.get(0).getBytes(StandardCharsets.UTF_8).length <= 8192);
        h.respondAuthority(); assertFalse(h.client.ready());
        h.synchronizedReady();
        assertEquals(1, ControlFrameCodec.decode(link.appliedFrames.get(0)).sequence());
        assertEquals(0, h.httpAuthorityCalls); h.client.close();
    }

    @Test void ninetySecondsOfSourceSilencePreservesPhysicalWriterAndOnlyFreshProofStartsApplication() throws Exception {
        var h = activated(false); var writer = h.writer; var link = h.links.get(0); int bootstrap = h.bootstrapCalls;
        String stale = null;
        for (int i = 0; i < 3; i++) {
            var request = h.authorityRequests.remove(); stale = h.authorityWire(request);
            h.time.advance(request.request().expiresAt() - h.time.now);
            assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
            assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
            assertEquals(writer, h.client.snapshot().writer()); assertEquals(0, link.abortCalls + link.closeCalls);
            assertTrue(link.sent.isEmpty()); assertTrue(link.appliedFrames.isEmpty());
            h.time.advance(h.time.nextDelay());
        }
        link.receiver.accept(stale); assertNull(h.journal.value.authorityFloor()); // Last expired nonce cannot answer new request.
        h.respondAuthority(); assertFalse(h.client.ready()); h.synchronizedReady();
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(1, h.links.size()); assertEquals(0, h.httpAuthorityCalls);
        assertEquals(writer, h.client.snapshot().writer()); h.client.close();
    }

    @Test void twoStartsPerRollingWindowSurviveResyncAndDoNotMintAnEarlyProof() throws Exception {
        var h = activated(false); h.synchronizedReady(); h.client.synchronize(); h.synchronizedReady();
        int bootstrap = h.bootstrapCalls; h.client.synchronize();
        assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
        assertEquals(2, h.links.get(0).authorityWires.size()); assertTrue(h.authorityRequests.isEmpty());
        h.time.advance(29999); assertEquals(2, h.authorityCalls);
        h.time.advance(1); assertEquals(3, h.authorityCalls);
        assertEquals(h.time.now, h.authorityRequests.element().request().sentAt()); // Lifetime starts on permitted handoff.
        h.synchronizedReady(); assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.httpAuthorityCalls); h.client.close();
    }

    @Test void selectedMachineRotationRetainsPhysicalRateAndUsesOnlyTheNewSelectedKey() throws Exception {
        var h = activated(false); h.synchronizedReady(); var physical = h.writer; var link = h.links.get(0);
        h.client.rotateMachineKey(); var candidate = h.journal.value.pending().candidate();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.writer = new ControlWriterFence(physical.transport(), physical.sessionEpoch(), physical.sessionId(), physical.connectionId(),
                candidate.keyId(), physical.machineKeyRevision() + 1);
        h.incoming(link, physical, "lifecycle.receipt", ControlClientCoordinatorTest.resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        h.respondStatus(); h.respondStatus();
        var request = h.authorityRequests.element().request(); assertEquals(candidate.keyId(), request.authentication().keyId());
        assertEquals(h.writer, request.writer()); h.synchronizedReady(); h.client.synchronize();
        assertTrue(h.authorityRequests.isEmpty()); assertEquals(2, link.authorityWires.size());
        assertEquals(1, h.links.size()); assertEquals(physical.sessionEpoch(), h.writer.sessionEpoch());
        h.time.advance(30000); h.synchronizedReady(); assertEquals(3, link.authorityWires.size());
        assertEquals(0, h.httpAuthorityCalls); h.client.close();
    }

    @Test void failedAuthorityHandoffRetainsSocketAndRetriesOnlyItsSourceLane() throws Exception {
        var h = activated(true); var link = h.links.get(0); var old = h.authorityRequests.remove(); String stale = h.authorityWire(old);
        link.authoritySend.completeExceptionally(new IllegalStateException("bounded queue rejected handoff"));
        assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
        assertNull(h.journal.value.authorityFloor()); assertEquals(0, link.closeCalls + link.abortCalls);
        link.receiver.accept(stale); assertNull(h.journal.value.authorityFloor());
        link.authoritySend = null; h.time.advance(h.time.nextDelay()); h.synchronizedReady();
        assertEquals(3, h.bootstrapCalls); assertEquals(1, h.links.size()); assertEquals(0, h.httpAuthorityCalls); h.client.close();
    }

    @Test void responseBeforeSendWaitsAndRechecksDeadlineCatalogAndOwnership() throws Exception {
        for (String change : List.of("none", "deadline", "key", "close", "writer")) {
            var h = activated(true); var link = h.links.get(0); h.respondAuthority();
            assertNull(h.journal.value.authorityFloor(), change); assertTrue(h.synchronizations.isEmpty(), change);
            switch (change) {
                case "deadline" -> h.time.advance(30000);
                case "key" -> h.keyAvailable = false;
                case "close" -> h.client.close();
                case "writer" -> { h.client.replaceTransport("https", List.of("request-response")); h.respondPrepare(); h.respondActivation(); }
            }
            link.authoritySend.complete(null);
            if (change.equals("none")) { assertNotNull(h.journal.value.authorityFloor()); h.synchronizedReady(); }
            else { assertNull(h.journal.value.authorityFloor(), change); assertTrue(h.synchronizations.isEmpty(), change); }
            h.client.close();
        }
    }

    @Test void timedOutActualSendRetainsSoleLaneUntilItsCompletionWithoutHttpFallback() throws Exception {
        var h = activated(true); var link = h.links.get(0); var old = h.authorityRequests.remove(); String wire = h.authorityWire(old);
        h.time.advance(30000);
        for (int i = 0; i < 6; i++) h.time.advance(h.time.nextDelay());
        assertEquals(1, h.authorityCalls); assertTrue(h.authorityRequests.isEmpty()); assertEquals(0, h.httpAuthorityCalls);
        link.receiver.accept(wire); link.authoritySend.complete(null); link.authoritySend = null;
        assertNull(h.journal.value.authorityFloor());
        h.time.advance(h.time.nextDelay()); h.synchronizedReady();
        assertEquals(2, h.authorityCalls); assertEquals(3, h.bootstrapCalls); assertEquals(1, h.links.size()); h.client.close();
    }

    @Test void oldLinkDuplicateAndChangedProofCannotInstallOrDisplaceCurrentNonce() throws Exception {
        var h = activated(false); var oldLink = h.links.get(0); var old = h.authorityRequests.remove(); String wire = h.authorityWire(old);
        h.client.replaceTransport("websocket", ControlClientCoordinatorTest.CAPS);
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation();
        oldLink.receiver.accept(wire); h.links.get(1).receiver.accept(wire);
        assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
        var current = h.authorityRequests.remove(); String valid = h.authorityWire(current);
        h.links.get(1).receiver.accept(valid); var floor = h.journal.value.authorityFloor();
        h.links.get(1).receiver.accept(valid); oldLink.receiver.accept(valid);
        assertEquals(floor, h.journal.value.authorityFloor()); assertEquals(1, h.synchronizations.size());
        h.synchronizedReady(); assertEquals(0, h.httpAuthorityCalls); h.client.close();
    }

    @Test void invalidSignatureAndWrongWriterAreSourceFailuresWithoutAuthorityOrEpochMutation() throws Exception {
        for (String change : List.of("signature", "writer")) {
            var h = activated(false); var exchange = h.authorityRequests.remove();
            var raw = ControlJson.parse(h.authorityWire(exchange), 8192);
            if (change.equals("signature")) raw.getAsJsonObject("authentication").addProperty("signature", "A".repeat(128));
            else raw.getAsJsonObject("writer").addProperty("sessionEpoch", h.writer.sessionEpoch() + 1);
            h.links.get(0).receiver.accept(raw.toString());
            assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state(), change);
            assertNull(h.journal.value.authorityFloor()); assertEquals(h.writer, h.client.snapshot().writer());
            assertEquals(0, h.links.get(0).abortCalls + h.links.get(0).closeCalls); assertEquals(0, h.httpAuthorityCalls);
            h.time.advance(h.time.nextDelay()); h.synchronizedReady(); h.client.close();
        }
    }

    @Test void oversizedAuthorityOrUnexpectedUnsequencedKindClosesWithoutGrant() throws Exception {
        for (String change : List.of("size", "kind")) {
            var h = activated(false); var exchange = h.authorityRequests.remove(); String wire = h.authorityWire(exchange);
            if (change.equals("size")) wire = wire + " ".repeat(8193);
            else wire = wire.replace("authority-response", "authority-unavailable");
            h.links.get(0).receiver.accept(wire);
            assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
            assertFalse(h.client.ready()); assertTrue(h.links.get(0).closed.isDone()); h.client.close();
        }
    }
}
