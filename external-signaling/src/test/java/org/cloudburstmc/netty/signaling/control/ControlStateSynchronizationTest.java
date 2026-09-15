package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class ControlStateSynchronizationTest {
    private static ControlClientCoordinatorTest.Harness activated(String transport) throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(transport);
        h.client.start(); h.respondStatus(); h.respondPrepare();
        if (transport.equals("websocket")) h.links.get(0).challenge();
        h.respondActivation(); h.respondAuthority(); return h;
    }
    private static ControlStateCodec.Acknowledgement acknowledgement(ControlClientCoordinatorTest.Harness h) {
        var link = h.links.get(h.links.size() - 1);
        var frame = ControlFrameCodec.decode(link.appliedFrames.get(link.appliedFrames.size() - 1));
        return ControlStateCodec.decodeAcknowledgement(new String(frame.payloadBytes(), StandardCharsets.UTF_8));
    }

    @Test void queuedApplicationReadsTheOriginalFixedDeadlineRatherThanANewBudget() throws Exception {
        var h = activated("https"); var exchange = h.synchronizationExchanges.get(0);
        long deadline = h.time.now + 30_000;
        assertEquals(deadline, exchange.deadlineMillis()); h.time.advance(12_000);
        assertEquals(deadline, exchange.deadlineMillis()); exchange.requireCurrent();
        h.time.advance(18_000); assertEquals(deadline, exchange.deadlineMillis());
        assertThrows(IllegalStateException.class, exchange::requireCurrent); h.client.close();
    }

    @Test void nullApplicationCompletionCannotManufactureReadiness() throws Exception {
        for (String transport : List.of("https", "websocket")) {
            var h = activated(transport); h.nullSynchronizationResult = true;
            h.synchronizations.get(0).complete(null);
            assertFalse(h.client.ready()); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
            assertEquals(3, h.bootstrapCalls); h.client.close();
        }
    }

    @Test void mismatchedAppliedStateUsesBoundedCacheRetryWithoutStrongStatusOrSocketReplacement() throws Exception {
        for (String transport : List.of("https", "websocket")) {
            var h = activated(transport); int bootstrap = h.bootstrapCalls;
            h.appliedBasis = new ControlStateCodec.AppliedBasis(h.initial.subject().generation(), 1, "closed", "disabled", null, null);
            h.synchronizations.get(0).complete(null);
            assertFalse(h.client.ready()); assertEquals(150, h.time.nextDelay());
            if (!h.links.isEmpty()) { assertTrue(h.links.get(0).appliedFrames.isEmpty()); assertEquals(0, h.links.get(0).abortCalls); }
            h.sourceState = new ControlStateCodec.Summary(1, "closed", ControlStateCodec.appliedBasisDigest(h.appliedBasis));
            h.time.advance(150); h.synchronizedReady();
            assertEquals(bootstrap, h.bootstrapCalls); h.client.close();
        }
    }

    @Test void websocketRequiresMatchingSignedAcknowledgementAndActualSendCompletion() throws Exception {
        var h = activated("websocket"); h.autoReady = false;
        var link = h.links.get(0); link.appliedSend = new CompletableFuture<>();
        h.synchronizations.get(0).complete(null); var ack = acknowledgement(h);
        assertFalse(h.client.ready()); assertEquals(1, ControlFrameCodec.decode(link.appliedFrames.get(0)).sequence());
        link.readyReply(new ControlStateCodec.Acknowledgement("previous_pass_000000000000", ack.state()));
        assertFalse(h.client.ready());
        link.readyReply(ack); assertFalse(h.client.ready());
        link.appliedSend.complete(null); assertTrue(h.client.ready());
        assertThrows(IllegalStateException.class, () -> h.synchronizationExchanges.get(0).applied(h.appliedBasis));
        assertThrows(IllegalStateException.class, h.synchronizationExchanges.get(0)::requireCurrent);
        h.client.close();
    }

    @Test void wrongStateForCurrentSyncIdCannotConfirmAndConfirmationTimeoutReleasesTheLane() throws Exception {
        for (String failure : List.of("wrong-state", "timeout", "key-change")) {
            var h = activated("websocket"); h.autoReady = false; var link = h.links.get(0);
            link.appliedSend = new CompletableFuture<>(); h.synchronizations.get(0).complete(null);
            var ack = acknowledgement(h);
            switch (failure) {
                case "wrong-state" -> link.readyReply(new ControlStateCodec.Acknowledgement(ack.syncId(),
                        new ControlStateCodec.Summary(1, "closed", ack.state().appliedBasisSha256())));
                case "timeout" -> h.time.advance(30_000);
                case "key-change" -> { h.keyAvailable = false; link.appliedSend.complete(null); h.keyAvailable = true; }
            }
            assertFalse(h.client.ready(), failure); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state(), failure);
            // A real link send may remain pending, but the synchronization wait itself is cancelled.
            link.appliedSend.complete(null); h.autoReady = true;
            h.time.advance(h.time.nextDelay()); h.synchronizedReady();
            assertEquals(3, h.bootstrapCalls); h.client.close();
        }
    }

    @Test void cancelledConfirmationCallbacksCannotResurrectOrOverrideAClosedClient() throws Exception {
        for (String transition : List.of("replace", "recover", "send-failure", "timeout")) {
            var h = activated("websocket"); h.autoReady = false; var link = h.links.get(0);
            link.appliedSend = new CompletableFuture<>(); h.synchronizations.get(0).complete(null);
            h.synchronizationResults.get(0).whenComplete((ignored, failure) -> {
                assertNotNull(failure);
                try { h.client.close(); } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            });
            switch (transition) {
                case "replace" -> h.client.replaceTransport("https", List.of("request-response"));
                case "recover" -> h.client.reconcilePending();
                case "send-failure" -> link.appliedSend.completeExceptionally(new IllegalStateException("failed handoff"));
                case "timeout" -> h.time.advance(30_000);
            }
            assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state(), transition);
            int calls = h.bootstrapCalls; h.time.advance(300_000);
            assertEquals(calls, h.bootstrapCalls, transition); assertTrue(h.requests.isEmpty(), transition);
        }
    }

    @Test void uncompletedSendKeepsItsPhysicalLaneAcrossRepeatedAuthorityRetries() throws Exception {
        var h = activated("websocket"); h.autoReady = false; var link = h.links.get(0);
        link.appliedSend = new CompletableFuture<>(); h.synchronizations.get(0).complete(null);
        h.time.advance(30_000);
        for (int i = 0; i < 3; i++) {
            h.awaitAuthorityRequest(); h.respondAuthority();
            h.synchronizations.get(h.synchronizations.size() - 1).complete(null);
            assertEquals(1, link.appliedFrames.size()); assertFalse(h.client.ready());
        }
        link.appliedSend.complete(null); h.autoReady = true;
        h.awaitAuthorityRequest(); h.synchronizedReady();
        assertEquals(2, link.appliedFrames.size()); assertEquals(3, h.bootstrapCalls); h.client.close();
    }

    @Test void deadlineCrossedBeforeHandoffDoesNotConsumeAnUnsentFrameSequence() throws Exception {
        var h = activated("websocket"); var originalIds = h.identifierSupplier;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        h.identifierSupplier = () -> {
            if (calls.incrementAndGet() == 2) h.time.now += 30_001;
            return originalIds.get();
        };
        h.synchronizations.get(0).complete(null);
        assertFalse(h.client.ready()); assertTrue(h.links.get(0).appliedFrames.isEmpty());
        h.identifierSupplier = originalIds; h.time.advance(h.time.nextDelay()); h.synchronizedReady();
        assertEquals(1, ControlFrameCodec.decode(h.links.get(0).appliedFrames.get(0)).sequence()); h.client.close();
    }

    @Test void desiredStateNoticeAfterApplicationInvalidatesConfirmationEvenBeforeAdapterCompletion() throws Exception {
        var h = activated("websocket"); h.autoReady = false;
        h.synchronizations.get(0).complete(null); var old = acknowledgement(h);
        h.incoming(h.links.get(0), h.writer, "state.desired", "{}".getBytes(StandardCharsets.UTF_8), 1);
        assertFalse(h.client.ready());
        assertThrows(IllegalStateException.class, h.synchronizationExchanges.get(0)::requireCurrent);
        h.links.get(0).readyReply(old); assertFalse(h.client.ready()); h.client.close();
    }

    @Test void httpsConfirmationConsumesTheCurrentExchangeWithoutSendingAFrame() throws Exception {
        var h = activated("https"); h.synchronizedReady(); assertTrue(h.links.isEmpty());
        assertThrows(IllegalStateException.class, () -> h.synchronizationExchanges.get(0).applied(h.appliedBasis));
        h.client.synchronize(); h.respondAuthority(); assertFalse(h.client.ready());
        h.nullSynchronizationResult = true; h.synchronizations.get(1).complete(null);
        assertFalse(h.client.ready()); h.client.close();
    }
}
