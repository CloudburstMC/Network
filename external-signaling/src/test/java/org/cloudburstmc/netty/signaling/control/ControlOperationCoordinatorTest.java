package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ControlOperationCoordinatorTest {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ControlClientCoordinatorTest.Harness activated() throws Exception {
        return activated("https");
    }
    private static ControlClientCoordinatorTest.Harness activated(String mode) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(mode); h.client.start(); h.respondStatus(); h.respondPrepare();
        if (mode.equals("websocket")) h.links.get(0).challenge(); h.respondActivation(); h.respondAuthority(); return h;
    }
    private static String wire(ControlLifecycleCodec.Receipt receipt, String body) {
        return ControlResultCodec.encode(ControlResultCodec.create(receipt, bytes(body)));
    }
    private static void reply(ControlClientCoordinatorTest.Harness h, int index, ControlLifecycleCodec.Receipt receipt, String body) throws Exception {
        if (h.operations.size() > index) {
            var operation = h.operations.get(index);
            operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 200, wire(receipt, body)));
        } else {
            var link = h.links.get(h.links.size() - 1);
            h.incomingActual(link, h.writer, "lifecycle.receipt", bytes(wire(receipt, body)), link.nextProviderSequence);
        }
    }

    @Test void bothCarriersDeliverLargeOwnedBodiesAfterPersistingOnlyTheReceiptBarrier() throws Exception {
        for (boolean http : new boolean[]{true, false}) {
            var h = new ControlClientCoordinatorTest.Harness(); h.ready(); var grant = h.client.snapshot().grant(); var writer = h.writer;
            var future = h.client.submit("heartbeat", bytes("{}"), http).toCompletableFuture(); var receipt = h.receipt("committed");
            String body = "{\"ticketKey\":\"private-response-must-not-enter-journal\",\"padding\":\"" + "x".repeat(8192) + "\"}";
            int[] callbacks = {0}; future.thenAccept(result -> { assertNull(h.journal.value.pending()); callbacks[0]++; });
            if (http) reply(h, 0, receipt, body);
            else h.incoming(h.links.get(0), writer, "lifecycle.receipt", bytes(wire(receipt, body)), 1);
            var result = future.join(); assertEquals(1, callbacks[0]); assertEquals(receipt, result.receipt());
            assertArrayEquals(bytes(body), result.bodyBytes().orElseThrow());
            byte[] copy = result.bodyBytes().orElseThrow(); Arrays.fill(copy, (byte)0);
            assertArrayEquals(bytes(body), result.bodyBytes().orElseThrow());
            assertFalse(result.toString().contains("private-response"));
            for (var snapshot : h.journal.writes) assertFalse(snapshot.toString().contains("private-response"));
            assertEquals(grant, h.client.snapshot().grant()); assertEquals(writer, h.client.snapshot().writer());
        }
    }

    @Test void wrongIntentAndBareLegacyReceiptReleaseNoBodyAndRetainPendingIntent() throws Exception {
        for (boolean bare : new boolean[]{true, false}) {
            var h = new ControlClientCoordinatorTest.Harness(); h.ready();
            var future = h.client.submit("heartbeat", bytes("{}"), true).toCompletableFuture();
            var receipt = h.receipt("committed");
            var wrong = new ControlLifecycleCodec.Receipt(1, receipt.intentDigest(), receipt.operation(), receipt.instanceId(), receipt.generation(),
                    receipt.sequence() + 1, receipt.idempotencyKey(), receipt.disposition(), receipt.committedAt(), receipt.commitRevision(), null);
            var operation = h.operations.get(0);
            operation.reply().complete(new ControlClientIo.HttpReply(operation.endpoint(), "POST", operation.endpoint(), 200,
                    bare ? ControlLifecycleCodec.encodeReceipt(receipt) : wire(wrong, "{\"secret\":\"unassociated\"}")));
            assertFalse(future.isDone()); assertNotNull(h.journal.value.pending()); assertFalse(h.client.ready());
        }
    }

    @Test void receiptOnlyReconciliationNeverInventsAResponseOrReplaysASecret() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.ready();
        var future = h.client.submit("heartbeat", bytes("{\"keyRequestId\":\"persisted-key-request\"}"), true).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        var result = future.join(); assertEquals(receipt, result.receipt()); assertFalse(result.hasBody());
        assertTrue(result.bodyBytes().isEmpty()); assertThrows(IllegalStateException.class, result::requireCurrent);
        assertNull(h.journal.value.pending()); assertFalse(h.client.ready());
    }

    @Test void deliveredBodyCannotBeAppliedAfterPhysicalReplacementExpiryOrProviderKeyRetirement() throws Exception {
        for (String change : new String[]{"writer", "expiry", "key"}) {
            var h = new ControlClientCoordinatorTest.Harness(); h.ready();
            var future = h.client.submit("heartbeat", bytes("{}"), false).toCompletableFuture();
            h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", bytes(wire(h.receipt("committed"), "{\"desiredState\":{\"revision\":2,\"state\":\"draining\"}}")), 1);
            var result = future.join(); result.requireCurrent();
            switch (change) {
                case "writer" -> h.client.replaceTransport("https", java.util.List.of("request-response"));
                case "expiry" -> h.time.advance(30000);
                case "key" -> h.keyAvailable = false;
            }
            assertThrows(IllegalStateException.class, result::requireCurrent);
            assertThrows(IllegalStateException.class, result::bodyBytes);
        }
    }

    @Test void receiptSigningKeyIsIndependentlyFencedBeforeAndAfterBodyRelease() throws Exception {
        for (boolean duringCommit : new boolean[]{true, false}) {
            var h = new ControlClientCoordinatorTest.Harness(); h.ready();
            var pair = ProviderCrypto.generate();
            var key = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL,
                    "provider_result_key_02", pair.getPublic(), 0, 100_000_000);
            h.additionalKeys.put(key.keyId(), key);
            var future = h.client.submit("heartbeat", bytes("{}"), false).toCompletableFuture();
            byte[] body = bytes(wire(h.receipt("committed"), "{\"ticketKey\":\"private-result-key\"}"));
            var frame = new ControlFrameCodec.Frame(1, "lifecycle.receipt", "provider_frame_0000001", 1 + h.links.get(0).readinessFrames,
                    ControlFrameCodec.Direction.PROVIDER_TO_HOST, ControlClientCoordinatorTest.ORIGIN,
                    h.initial.subject().instanceId(), h.initial.subject().generation(), h.writer.sessionId(), h.writer.sessionEpoch(),
                    h.writer.connectionId(), h.grant.capabilities(), h.time.now, h.time.now + 30000,
                    ProviderCrypto.base64(body), ControlFrameCodec.payloadDigest(body),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, key.keyId(), ""));
            if (duringCommit) h.journal.afterCommit = () -> h.additionalKeys.remove(key.keyId());
            h.links.get(0).receiver.accept(ControlFrameCodec.encode(ControlFrameCodec.sign(frame,
                    ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, pair.getPrivate())));
            var result = future.join(); assertNull(h.journal.value.pending()); assertTrue(h.keyAvailable);
            if (duringCommit) { assertFalse(result.hasBody()); assertFalse(h.client.ready()); }
            else {
                result.requireCurrent(); h.additionalKeys.remove(key.keyId());
                assertThrows(IllegalStateException.class, result::requireCurrent);
                assertThrows(IllegalStateException.class, result::bodyBytes);
            }
        }
    }

    @Test void initialHeartbeatUsesSameJournalAndWriterAndWaitsForApplicationAndSignedProviderReadiness() throws Exception {
        var h = activated("websocket"); h.autoReady = false; var lane = h.synchronizationExchanges.get(0); var writer = h.writer;
        assertTrue(lane.pendingHeartbeat().isEmpty());
        var result = lane.heartbeat(bytes("{\"state\":\"serving\",\"appliedStateRevision\":0}"));
        assertTrue(h.operations.isEmpty()); assertEquals(1, h.links.get(0).sent.size());
        assertEquals(writer.connectionId(), ControlFrameCodec.decode(h.links.get(0).sent.get(0)).connectionId());
        assertEquals(1, h.journal.value.lastSequence()); assertEquals(1, h.journal.value.pending().intent().sequence());
        assertThrows(IllegalStateException.class, () -> h.client.submit("heartbeat", bytes("{}"), true));
        assertThrows(IllegalStateException.class, h.client::rotateMachineKey);
        var applicationStarted = new CompletableFuture<Void>(); var applied = new CompletableFuture<Void>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var application = result.thenComposeAsync(value -> {
                assertFalse(Thread.holdsLock(h.client)); lane.requireCurrent(); value.requireCurrent();
                assertTrue(new String(value.bodyBytes().orElseThrow(), StandardCharsets.UTF_8).contains("desiredState"));
                applicationStarted.complete(null);
                return applied.thenApply(a -> { value.requireCurrent(); lane.requireCurrent(); return (Void)null; });
            }, executor);
            var completion = application.whenComplete((nothing, failure) -> { if (failure == null) h.synchronizations.get(0).complete(null); else h.synchronizations.get(0).completeExceptionally(failure); });
            reply(h, 0, h.receipt("committed"), "{\"desiredState\":{\"revision\":1,\"state\":\"serving\"}}");
            applicationStarted.get(2, TimeUnit.SECONDS); assertNull(h.journal.value.pending()); assertFalse(h.client.ready());
            applied.complete(null); assertFalse(h.client.ready());
            completion.toCompletableFuture().get(2, TimeUnit.SECONDS);
            var link = h.links.get(0);
            var ack = ControlStateCodec.decodeAcknowledgement(new String(ControlFrameCodec.decode(link.appliedFrames.get(0)).payloadBytes(), StandardCharsets.UTF_8));
            link.readyReply(ack);
            completion.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(h.client.ready()); assertEquals(writer, h.client.snapshot().writer()); assertEquals(3, h.bootstrapCalls);
            assertThrows(IllegalStateException.class, () -> lane.heartbeat(bytes("{}")));
        } finally { executor.shutdownNow(); }
    }

    @Test void prematureSynchronizationCompletionCannotMarkPendingHeartbeatReady() throws Exception {
        var h = activated(); var lane = h.synchronizationExchanges.get(0);
        var result = lane.heartbeat(bytes("{}")); var receipt = h.receipt("committed");
        h.synchronizations.get(0).complete(null);
        assertFalse(h.client.ready()); assertNotNull(h.journal.value.pending());
        reply(h, 0, receipt, "{\"ticketKey\":\"must-be-withheld-after-scope-expiry\"}");
        assertTrue(result.toCompletableFuture().isCompletedExceptionally(), "Invalid application pass has already settled"); assertNull(h.journal.value.pending());
        assertFalse(h.client.ready()); assertEquals(3, h.bootstrapCalls); assertTrue(h.links.isEmpty());
    }

    @Test void asynchronousApplicationMustRecheckAuthorityAndCannotCompleteSupersededScope() throws Exception {
        for (String change : new String[]{"key", "expiry", "writer", "close"}) {
            var h = activated(); var lane = h.synchronizationExchanges.get(0);
            var response = lane.heartbeat(bytes("{}")); reply(h, 0, h.receipt("committed"), "{\"accepted\":true}");
            var result = response.toCompletableFuture().join(); result.requireCurrent(); lane.requireCurrent();
            switch (change) {
                case "key" -> h.keyAvailable = false;
                case "expiry" -> h.time.advance(30000);
                case "writer" -> h.client.replaceTransport("https", java.util.List.of("request-response"));
                case "close" -> h.client.close();
            }
            assertThrows(IllegalStateException.class, result::requireCurrent);
            assertThrows(IllegalStateException.class, lane::requireCurrent);
            h.synchronizations.get(0).complete(null);
            assertFalse(h.client.ready());
        }
    }

    @Test void retainedHeartbeatCanJoinSynchronizationButNeverChangeItsBytesOrCreateSecondIntent() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.ready(); byte[] body = bytes("{\"state\":\"serving\"}");
        var original = h.client.submit("heartbeat", body, false); var pending = h.journal.value.pending();
        h.client.synchronize(); h.respondAuthority(); var lane = h.synchronizationExchanges.get(1);
        assertArrayEquals(body, lane.pendingHeartbeat().orElseThrow());
        assertThrows(IllegalStateException.class, () -> lane.heartbeat(bytes("{\"state\":\"closed\"}")));
        var resumed = lane.heartbeat(body);
        assertEquals(pending.intent(), h.journal.value.pending().intent()); assertEquals(1, h.links.get(0).sent.size()); assertTrue(h.operations.isEmpty());
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", bytes(wire(h.receipt("committed"), "{\"accepted\":true}")), 1);
        assertEquals(original.toCompletableFuture().join().receipt(), resumed.toCompletableFuture().join().receipt());
        assertEquals(1, h.journal.value.lastSequence()); assertNull(h.journal.value.pending());
    }

    @Test void noncommittedResultCannotExposeBodyReleaseBarrierOrAllowAnotherHeartbeat() throws Exception {
        for (String disposition : new String[]{"unknown"}) {
            var h = activated(); var lane = h.synchronizationExchanges.get(0);
            var result = lane.heartbeat(bytes("{}")); reply(h, 0, h.receipt(disposition), "{}");
            assertFalse(result.toCompletableFuture().isDone()); assertNotNull(h.journal.value.pending());
            assertThrows(IllegalStateException.class, () -> lane.heartbeat(bytes("{\"different\":true}")));
            if (!disposition.equals("unknown")) assertThrows(IllegalStateException.class, () -> lane.heartbeat(bytes("{}")));
            h.synchronizations.get(0).complete(null); assertFalse(h.client.ready());
        }
    }

    @Test void terminalRejectionReleasesSynchronizationLaneWithoutBodyOrAutomaticReadiness() throws Exception {
        for (String disposition : new String[]{"rejected", "expired"}) {
            var h = activated(); var lane = h.synchronizationExchanges.get(0);
            var result = lane.heartbeat(bytes("[]")); reply(h, 0, h.receipt(disposition), "{}");
            assertFalse(result.toCompletableFuture().join().hasBody()); assertNull(h.journal.value.pending());
            assertFalse(h.client.ready());
            var corrected = lane.heartbeat(bytes("{}")); assertEquals(2, h.journal.value.pending().intent().sequence());
            reply(h, 1, h.receipt("committed"), "{\"accepted\":true}");
            assertTrue(corrected.toCompletableFuture().join().hasBody()); assertFalse(h.client.ready());
            h.synchronizedReady(); h.client.close();
        }
    }
}
