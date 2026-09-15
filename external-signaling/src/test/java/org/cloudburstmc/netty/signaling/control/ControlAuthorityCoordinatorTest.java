package org.cloudburstmc.netty.signaling.control;

import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ControlAuthorityCoordinatorTest {
    private static ControlClientCoordinatorTest.Harness activated() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness();
        h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge(); h.respondActivation(); return h;
    }
    private static void unavailable(ControlClientCoordinatorTest.Harness h) {
        var exchange = h.authorityRequests.remove();
        exchange.reply().complete(new ControlClientIo.HttpReply(exchange.endpoint(), "POST", exchange.endpoint(), 503, "unavailable"));
    }
    private static String frame(ControlClientCoordinatorTest.Harness h, long sentAt, long expiresAt, boolean validSignature) throws Exception {
        return frame(h, sentAt, expiresAt, validSignature, 1, "connectivity.report");
    }
    private static String frame(ControlClientCoordinatorTest.Harness h, long sentAt, long expiresAt, boolean validSignature,
                                long sequence, String type) throws Exception {
        var writer = h.writer; byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var link = h.links.get(h.links.size() - 1); sequence += link.readinessFrames;
        if (validSignature) link.nextProviderSequence = Math.max(link.nextProviderSequence, sequence + 1);
        var value = new ControlFrameCodec.Frame(1, type, "provider_frame_0000001", sequence,
                ControlFrameCodec.Direction.PROVIDER_TO_HOST, ControlClientCoordinatorTest.ORIGIN, h.initial.subject().instanceId(), h.initial.subject().generation(),
                writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), h.grant.capabilities(), sentAt, expiresAt,
                ProviderCrypto.base64(body), ControlFrameCodec.payloadDigest(body),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, h.providerKey.keyId(), ""));
        var signed = ControlFrameCodec.sign(value, ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, h.provider.getPrivate());
        if (!validSignature) signed = new ControlFrameCodec.Frame(signed.version(), signed.type(), signed.id(), signed.sequence(), signed.direction(), signed.audience(),
                signed.instanceId(), signed.generation(), signed.sessionId(), signed.sessionEpoch(), signed.connectionId(), signed.capabilities(), signed.sentAt(), signed.expiresAt(),
                signed.payload(), signed.payloadSha256(), new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, h.providerKey.keyId(), "A".repeat(128)));
        return ControlFrameCodec.encode(signed);
    }

    @Test void initialCachedLagPastOldGrantExpiryNeedsFreshProofAndStateApplicationWithoutStrongPolling() throws Exception {
        var h = activated(); int bootstrap = h.bootstrapCalls; var writer = h.writer;
        h.time.advance(300000);
        h.authorityRequests.remove().reply().completeExceptionally(new java.util.concurrent.TimeoutException());
        assertFalse(h.client.ready()); assertTrue(h.synchronizations.isEmpty());
        h.time.advance(150); h.respondAuthority();
        assertFalse(h.client.ready()); assertNotNull(h.journal.value.authorityFloor());
        h.synchronizedReady(); assertEquals(writer, h.client.snapshot().writer());
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.links.get(0).abortCalls);
    }

    @Test void unavailableRefreshRetainsSocketEpochAndFloorAcrossRepeatedRetries() throws Exception {
        var h = activated(); h.synchronizedReady(); var floor = h.journal.value.authorityFloor(); var writer = h.writer;
        int bootstrap = h.bootstrapCalls, requests = h.authorityCalls;
        h.time.advance(300000); assertEquals(requests, h.authorityCalls); // idle expiry has no refresh timer
        h.client.synchronize();
        for (int i = 0; i < 4; i++) { unavailable(h); h.time.advance(h.time.nextDelay()); }
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(writer, h.writer);
        assertEquals(floor, h.journal.value.authorityFloor()); assertEquals(0, h.links.get(0).abortCalls);
        h.synchronizedReady(); assertTrue(h.journal.value.authorityFloor().value().source().sourceRevision() > floor.value().source().sourceRevision());
    }

    @Test void exactHttpsProvenanceAndPendingNonceFenceRedirectedOrLateResponses() throws Exception {
        var h = activated(); var first = h.authorityRequests.remove(); String wire = h.authorityWire(first);
        first.reply().complete(new ControlClientIo.HttpReply(first.endpoint(), "POST", URI.create("https://other.example/control/authority"), 200, wire));
        assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
        h.time.advance(150); var late = h.authorityRequests.remove(); String lateWire = h.authorityWire(late);
        h.time.advance(30000); h.time.advance(h.time.nextDelay());
        assertTrue(h.authorityRequests.isEmpty()); // unabortable old request retains the sole I/O lane
        late.reply().complete(new ControlClientIo.HttpReply(late.endpoint(), "POST", late.endpoint(), 200, lateWire));
        assertNull(h.journal.value.authorityFloor());
        h.time.advance(h.time.nextDelay());
        h.synchronizedReady(); assertEquals(3, h.bootstrapCalls); assertEquals(0, h.links.get(0).abortCalls);
    }

    @Test void consumedDeliveryDeadlineDoesNotPrematurelyEndStateApplication() throws Exception {
        var h = activated(); h.time.advance(29000); h.respondAuthority();
        h.time.advance(2000); // original authority delivery expired, installed inner grant remains live
        h.synchronizedReady(); assertTrue(h.client.ready());
    }

    @Test void nonsettlingStateApplicationDoesNotCreateMoreHttpOrApplicationOperations() throws Exception {
        var h = activated(); h.respondAuthority(); int requests = h.authorityCalls;
        h.time.advance(30000);
        for (int i = 0; i < 6; i++) h.time.advance(h.time.nextDelay());
        assertEquals(requests, h.authorityCalls); assertEquals(1, h.synchronizations.size());
        assertEquals(3, h.bootstrapCalls); assertFalse(h.client.ready());
        h.synchronizations.get(0).complete(null); // its timeout already fenced readiness
        assertFalse(h.client.ready()); h.time.advance(h.time.nextDelay()); h.synchronizedReady();
    }

    @Test void signingKeyRetirementDuringStateApplicationCannotCompleteReadiness() throws Exception {
        var h = activated(); h.respondAuthority(); var floor = h.journal.value.authorityFloor();
        h.keyAvailable = false; h.synchronizations.get(0).complete(null);
        assertFalse(h.client.ready()); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
        assertEquals(floor, h.journal.value.authorityFloor()); assertEquals(0, h.links.get(0).abortCalls);
        h.keyAvailable = true; h.time.advance(150); h.synchronizedReady();
    }

    @Test void durableFloorWriteFailureHaltsBeforeStateApplication() throws Exception {
        var h = activated(); h.journal.fail = true; h.respondAuthority();
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state());
        assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
        assertEquals(1, h.links.get(0).abortCalls);
    }

    @Test void reentrantCloseAfterDurableFloorWriteCannotInstallThePendingProof() throws Exception {
        var h = activated();
        h.journal.afterCommit = () -> { try { h.client.close(); } catch (IOException failure) { throw new IllegalStateException(failure); } };
        h.respondAuthority();
        assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state());
        assertNotNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
    }

    @Test void blockingJournalPastDeliveryOrRetiringSignerCannotInstallTheAlreadyPersistedFloor() throws Exception {
        var expired = activated(); expired.journal.afterCommit = () -> expired.time.advance(30001); expired.respondAuthority();
        assertNotNull(expired.journal.value.authorityFloor()); assertTrue(expired.synchronizations.isEmpty());
        assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, expired.client.state());
        assertEquals(0, expired.links.get(0).abortCalls);
        var retired = activated(); retired.journal.afterCommit = () -> retired.keyAvailable = false; retired.respondAuthority();
        assertNotNull(retired.journal.value.authorityFloor()); assertTrue(retired.synchronizations.isEmpty());
        assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, retired.client.state());
    }

    @Test void replacingWriterFencesPendingAuthorityResponseWithoutChangingTheNewSynchronization() throws Exception {
        var h = activated(); var old = h.authorityRequests.remove(); String wire = h.authorityWire(old);
        h.client.replaceTransport("https", java.util.List.of("request-response")); h.respondPrepare(); h.respondActivation();
        old.reply().complete(new ControlClientIo.HttpReply(old.endpoint(), "POST", old.endpoint(), 200, wire));
        assertNull(h.journal.value.authorityFloor()); assertTrue(h.synchronizations.isEmpty());
        h.time.advance(h.time.nextDelay());
        h.synchronizedReady(); assertEquals("https", h.client.snapshot().authorityFloor().value().writer().transport());
        assertEquals(2, h.client.snapshot().writer().sessionEpoch());
    }

    @Test void restartAndWriterReplacementRetainFloorAndRejectAnOlderSourceCut() throws Exception {
        var h = activated(); h.sourceRevision = 4; h.synchronizedReady(); var floor = h.journal.value.authorityFloor();
        h.client.close(); h.newClient(); assertEquals(floor, h.client.snapshot().authorityFloor());
        h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation();
        assertEquals(floor, h.journal.value.authorityFloor());
        h.sourceRevision = 0; h.respondAuthority();
        assertFalse(h.client.ready()); assertEquals(floor, h.journal.value.authorityFloor());
        int bootstrap = h.bootstrapCalls;
        h.time.advance(150); h.sourceRevision = 5; h.synchronizedReady();
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(2, h.writer.sessionEpoch());
    }

    @Test void futureOrderedFrameWaitsForFreshProofAndStateBeforeDispatchWithoutSequenceReset() throws Exception {
        var h = activated(); h.synchronizedReady(); int bootstrap = h.bootstrapCalls;
        h.time.advance(300000);
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true));
        assertEquals(0, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
        h.respondAuthority(); assertEquals(0, h.applicationFrames);
        h.synchronizedReady(); assertEquals(1, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
    }

    @Test void directApplicationArrivalDuringStateApplicationWaitsWithoutConsumingSequence() throws Exception {
        var h = activated(); h.respondAuthority(); int bootstrap = h.bootstrapCalls;
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true));
        assertEquals(0, h.applicationFrames); assertFalse(h.client.ready());
        h.synchronizedReady(); assertEquals(1, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
    }

    @Test void failedExplicitRefreshDoesNotUseStillLiveOldProofToAcceptAndDropFrames() throws Exception {
        var h = activated(); h.synchronizedReady(); int bootstrap = h.bootstrapCalls;
        h.client.synchronize(); unavailable(h);
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true));
        assertEquals(0, h.applicationFrames); assertFalse(h.client.ready()); assertTrue(h.authorityRequests.isEmpty());
        h.time.advance(150); h.synchronizedReady();
        assertEquals(1, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
    }

    @Test void authenticatedExpiredFrameReconcilesOneGapOnlyAfterPositiveAuthority() throws Exception {
        var h = activated(); h.synchronizedReady(); h.time.advance(299000);
        String wire = frame(h, h.time.now, h.time.now + 1000, true); h.time.advance(1001);
        int bootstrap = h.bootstrapCalls;
        h.links.get(0).receiver.accept(wire); unavailable(h);
        for (int i = 0; i < 10; i++) h.links.get(0).receiver.accept(wire);
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        h.time.advance(150); h.respondAuthority();
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state()); assertEquals(bootstrap + 1, h.bootstrapCalls);
        for (int i = 0; i < 10; i++) h.links.get(0).receiver.accept(wire);
        assertEquals(bootstrap + 1, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
    }

    @Test void unauthenticatedExpiredGarbageCannotInduceGapReplacement() throws Exception {
        var h = activated(); h.synchronizedReady(); h.time.advance(299000);
        String wire = frame(h, h.time.now, h.time.now + 1000, false); h.time.advance(1001);
        int bootstrap = h.bootstrapCalls; h.links.get(0).receiver.accept(wire);
        h.synchronizedReady(); assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true));
        assertEquals(1, h.applicationFrames); // untrusted frame did not consume sequence 1
    }

    @Test void secondAuthenticatedFrameDuringSourceLagRecordsGapUntilPositiveProof() throws Exception {
        var h = activated(); h.synchronizedReady(); h.time.advance(300000); int bootstrap = h.bootstrapCalls;
        String first = frame(h, h.time.now, h.time.now + 30000, true, 1, "connectivity.report");
        h.links.get(0).receiver.accept(first);
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true, 2, "connectivity.report"));
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        h.respondAuthority();
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(bootstrap + 1, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
    }

    @Test void applicationFrameCannotSilentlyHideLaterSynchronizationFrame() throws Exception {
        var h = activated(); h.respondAuthority(); int bootstrap = h.bootstrapCalls;
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true, 1, "connectivity.report"));
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true, 2, "state.desired"));
        assertEquals(ControlClientCoordinator.State.SYNCHRONIZING, h.client.state());
        assertEquals("state.desired", h.frameDeliveries.get(0).frame().type());
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        h.synchronizedReady(); assertEquals(1, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
    }

    @Test void secondApplicationFrameDuringSynchronizationDoesNotGrowTheDeferredBuffer() throws Exception {
        var h = activated(); h.respondAuthority(); int bootstrap = h.bootstrapCalls;
        String first = frame(h, h.time.now, h.time.now + 30000, true, 1, "connectivity.report");
        String second = frame(h, h.time.now, h.time.now + 30000, true, 2, "connectivity.report");
        h.links.get(0).receiver.accept(first); h.links.get(0).receiver.accept(second);
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(bootstrap + 1, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        for (int i = 0; i < 10; i++) { h.links.get(0).receiver.accept(first); h.links.get(0).receiver.accept(second); }
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(bootstrap + 1, h.bootstrapCalls); assertEquals(0, h.links.get(0).abortCalls);
    }

    @Test void consumedDeferredApplicationFrameCannotSurviveAChangedAuthorityPass() throws Exception {
        var h = activated(); h.respondAuthority(); int bootstrap = h.bootstrapCalls;
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, true));
        h.synchronizations.get(0).completeExceptionally(new IllegalStateException("application failed"));
        assertEquals(bootstrap, h.bootstrapCalls); h.time.advance(h.time.nextDelay()); h.respondAuthority();
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(bootstrap + 1, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
    }

    @Test void failedDeferredApplicationEnqueueCannotLeaveReadinessOrLoseItsRecoveryFence() throws Exception {
        var h = activated(); h.respondAuthority(); int bootstrap = h.bootstrapCalls;
        String wire = frame(h, h.time.now, h.time.now + 30000, true);
        h.links.get(0).receiver.accept(wire); h.failApplicationDispatch = true;
        h.synchronizations.get(0).complete(null);
        assertFalse(h.client.ready()); assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals(0, h.applicationFrames); assertEquals(bootstrap + 1, h.bootstrapCalls);
        h.links.get(0).receiver.accept(wire);
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state()); assertEquals(bootstrap + 1, h.bootstrapCalls);
    }

    @Test void unauthenticatedSecondFrameAndExactDuplicateDoNotDisplaceRetainedFrame() throws Exception {
        var h = activated(); h.synchronizedReady(); h.time.advance(300000); int bootstrap = h.bootstrapCalls;
        String first = frame(h, h.time.now, h.time.now + 30000, true);
        h.links.get(0).receiver.accept(first); h.links.get(0).receiver.accept(first);
        h.links.get(0).receiver.accept(frame(h, h.time.now, h.time.now + 30000, false, 2, "connectivity.report"));
        h.synchronizedReady(); assertTrue(h.client.ready()); assertEquals(1, h.applicationFrames); assertEquals(bootstrap, h.bootstrapCalls);
    }

    @Test void repeatedStateApplicationFailureKeepsBackoffUntilFullReadiness() throws Exception {
        var h = activated(); int bootstrap = h.bootstrapCalls; long previousDelay = 0;
        for (int i = 0; i < 4; i++) {
            h.respondAuthority();
            h.synchronizations.get(h.synchronizations.size() - 1).completeExceptionally(new IllegalStateException("state unavailable"));
            long delay = h.time.nextDelay(); assertEquals(150L << i, delay); assertTrue(delay > previousDelay); previousDelay = delay;
            h.time.advance(delay);
        }
        assertEquals(bootstrap, h.bootstrapCalls); assertEquals(0, h.links.get(0).abortCalls);
        h.synchronizedReady(); h.client.synchronize(); unavailable(h); assertEquals(150, h.time.nextDelay());
    }

    @Test void strongRecoveryBackoffIncrementsOncePerFailure() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.client.start();
        for (int i = 0; i < 4; i++) {
            h.requests.remove().reply().completeExceptionally(new java.io.IOException("bootstrap unavailable"));
            assertEquals(150L << i, h.time.nextDelay()); h.time.advance(h.time.nextDelay());
        }
    }
}
