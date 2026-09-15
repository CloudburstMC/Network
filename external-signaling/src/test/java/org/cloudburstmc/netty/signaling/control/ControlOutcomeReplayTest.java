package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ControlledApplicationCoordinatorFixture;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ControlOutcomeReplayTest {
    static ControlClientCoordinatorTest.Harness recovered(ControlledApplicationCoordinatorFixture application,
            ControlClientCoordinatorTest.Time time, String mode) throws Exception {
        byte[] body = application.appendOutcome(); var initial = application.initial();
        var intent = new ControlLifecycleCodec.Intent(1, initial.subject().audience(), "outcomes", initial.subject().instanceId(),
                initial.subject().generation(), initial.lastSequence()+1, "retained_outcomes_0001", ControlFrameCodec.payloadDigest(body));
        application.journal().commit(new ControlClientJournal.Snapshot(initial.subject(), initial.currentKey(), initial.writer(), intent.sequence(),
                new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), null, null), null, null));
        var h = ControlApplicationRecoveryTest.harness(application, time, mode); h.client.start(); h.respondStatus(); h.respondStatus();
        ControlApplicationRecoveryTest.activate(h); return h;
    }
    static long replayDeadline(ControlClientCoordinatorTest.Harness h) {
        if (h.initialTransport.equals("https")) return h.operations.get(0).request().expiresAt();
        var frame = ControlFrameCodec.decode(h.links.get(0).sent.get(0));
        assertEquals("lifecycle.request", frame.type(), "The pre-ready receiver does not accept outcomes.batch");
        return frame.expiresAt();
    }
    static void reply(ControlClientCoordinatorTest.Harness h) throws Exception {
        var receipt = h.receipt("committed"); String wire = ControlClientCoordinatorTest.resultWire(receipt);
        if (h.initialTransport.equals("https")) {
            var call = h.operations.get(0); call.reply().complete(new ControlClientIo.HttpReply(call.endpoint(), "POST", call.endpoint(), 200, wire));
        } else {
            var link = h.links.get(0); h.incomingActual(link, h.writer, "lifecycle.receipt", wire.getBytes(StandardCharsets.UTF_8), link.nextProviderSequence);
        }
    }
    @Test void recoveredOutcomesSettleDurableApplicationAckBeforeActualNativeSynchronizationOnBothCarriers(@TempDir Path directory) throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var time = new ControlClientCoordinatorTest.Time();
            try (var application = new ControlledApplicationCoordinatorFixture(directory.resolve(mode), time)) {
                var h = recovered(application, time, mode); var original = h.journal.value.pending();
                assertTrue(h.synchronizationExchanges.isEmpty(), "Original report must settle before the actual application starts");
                long deadline = replayDeadline(h);
                application.runTasks(); assertTrue(h.synchronizationExchanges.isEmpty()); assertEquals(1, application.pendingEvents()); assertFalse(application.nativeEnabled());
                assertEquals(18, original.intent().sequence()); time.advance(5000); reply(h);
                assertEquals(original.intent(), h.journal.value.pending().intent()); assertEquals("committed", h.journal.value.pending().receipt().disposition());
                assertTrue(h.synchronizationExchanges.isEmpty()); assertEquals(1, application.pendingEvents());
                application.runTasks();
                assertEquals(0, application.pendingEvents()); assertEquals(1, h.synchronizationExchanges.size()); assertEquals(deadline, h.synchronizationExchanges.get(0).deadlineMillis());
                var next = h.journal.value.pending(); assertEquals("heartbeat", next.intent().operation()); assertEquals(19, next.intent().sequence());
                var body = JsonParser.parseString(new String(next.bodyBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(body.get("acceptingPlayers").getAsBoolean()); assertTrue(body.has("keyRequestId"));
                assertFalse(application.nativeEnabled()); assertFalse(h.client.ready());
                if (mode.equals("websocket")) { assertTrue(h.operations.isEmpty()); assertEquals(2, h.links.get(0).sent.size()); }
                else assertEquals(2, h.operations.size());
                h.client.close(); application.runTasks();
            }
        }
    }
    @Test void keyLossDuringDurableAckCannotStartNativeApplicationFromTheOldProof(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = recovered(application, time, "websocket"); reply(h); h.keyAvailable = false; application.runTasks();
            assertEquals(0, application.pendingEvents()); assertNull(h.journal.value.pending()); assertTrue(h.synchronizationExchanges.isEmpty());
            assertFalse(application.nativeEnabled()); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
            h.keyAvailable = true; time.advance(200); h.respondAuthority(); application.runTasks();
            assertEquals(1, h.synchronizationExchanges.size()); assertEquals("heartbeat", h.journal.value.pending().intent().operation());
            assertEquals(2, h.links.get(0).sent.size()); h.client.close(); application.runTasks();
        }
    }
    @Test void expiryWaitsForActualAckAndThenNeedsAFreshProofWithoutReplayingOutcomes(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = recovered(application, time, "https"); time.advance(5000); reply(h); time.advance(25001);
            int reads = h.authorityCalls; time.advance(200); assertEquals(reads, h.authorityCalls, "Unsettled actual ACK work keeps its lane");
            assertTrue(h.synchronizationExchanges.isEmpty()); application.runTasks(); assertEquals(0, application.pendingEvents());
            assertTrue(h.synchronizationExchanges.isEmpty()); assertEquals(1, h.operations.size());
            time.advance(1000); h.respondAuthority(); application.runTasks(); assertEquals(1, h.synchronizationExchanges.size());
            assertEquals("heartbeat", h.operations.get(1).request().intent().operation()); h.client.close(); application.runTasks();
        }
    }
    @Test void closeDuringActualAckRetainsReceiptAndNeverResumesNativeApplication(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = recovered(application, time, "websocket"); reply(h); var committed = h.journal.value.pending(); h.client.close(); application.runTasks();
            assertEquals(committed, application.journal().read().orElseThrow().pending()); assertEquals(0, application.pendingEvents());
            assertTrue(h.synchronizationExchanges.isEmpty()); assertFalse(application.nativeEnabled()); assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state());
        }
    }
    @Test void rootSaveFailureRetainsCommittedIntentAndRetriesOnlyTheActualLocalAck(@TempDir Path directory) throws Exception {
        for (String mode : List.of("https", "websocket")) {
            var time = new ControlClientCoordinatorTest.Time();
            try (var application = new ControlledApplicationCoordinatorFixture(directory.resolve(mode), time)) {
                var h = recovered(application, time, mode); reply(h); application.failRootSave = true; application.runTasks();
                assertEquals("committed", h.journal.value.pending().receipt().disposition()); assertEquals(1, application.pendingEvents());
                assertTrue(h.synchronizationExchanges.isEmpty()); int calls = h.bootstrapCalls;
                application.failRootSave = false; time.advance(200); assertEquals(calls, h.bootstrapCalls); application.runTasks();
                assertEquals(0, application.pendingEvents()); assertNull(h.journal.value.pending()); assertTrue(h.synchronizationExchanges.isEmpty());
                if (mode.equals("websocket")) assertEquals(1, h.links.get(0).sent.size()); else assertEquals(1, h.operations.size());
                assertFalse(application.nativeEnabled()); h.client.close(); application.runTasks();
            }
        }
    }
    @Test void reentrantCompletionCloseCannotResumeThePreNativeReplayOwner(@TempDir Path directory) throws Exception {
        var time = new ControlClientCoordinatorTest.Time();
        try (var application = new ControlledApplicationCoordinatorFixture(directory, time)) {
            var h = ControlApplicationRecoveryTest.harness(application, time, "websocket");
            h.actualSynchronization = null; h.ready(); h.actualSynchronization = application::synchronize;
            time.advance(300001); var result = h.client.submit("outcomes", application.appendOutcome(), false);
            result.whenComplete((ignored, failure) -> { try { h.client.close(); } catch (Exception error) { throw new RuntimeException(error); } });
            h.respondAuthority(); int passes = h.synchronizationExchanges.size(); reply(h); application.runTasks();
            assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertNull(h.journal.value.pending());
            assertEquals(passes, h.synchronizationExchanges.size()); assertEquals(0, application.pendingEvents()); assertFalse(application.nativeEnabled());
        }
    }
}
