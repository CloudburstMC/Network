package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.channel.nethernet.admission.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionGateTest extends AdmissionFixture {
    final InetSocketAddress first = new InetSocketAddress("127.0.0.1", 23450), other = new InetSocketAddress("127.0.0.1", 23451);
    final byte[] valid = binding(token + ":" + remote, password);
    AdmissionGate gate() { return new AdmissionGate(new AdmissionGate.Limits(2, 2, 1, 1000), validator()); }
    @Test void pendingLimitWarningsAreAuthenticatedAggregatedAndRateLimited() throws Exception {
        assertEquals(1024, AdmissionGate.Limits.defaults().pending());
        var trusted = validator().validate(valid, StunBinding.parse(valid), now);
        var v = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        var gate = new AdmissionGate(new AdmissionGate.Limits(2, 4, 1, 1000), v);
        var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        var answer1 = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199, now + 30_000, TestSignallingProvider.AUDIENCE, false);
        var answer2 = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199, now + 30_000, TestSignallingProvider.AUDIENCE, false);
        byte[] packet1 = binding(answer1.token() + ":" + remote, answer1.password());
        byte[] packet2 = binding(answer2.token() + ":" + remote, answer2.password());
        gate.ingress(packet1, first, now, 0, work::add); var r = work.remove();
        byte[] invalid = binding(answer2.token() + ":" + remote, "wrong-password-000000000000");
        assertFalse(gate.ingress(invalid, other, now, 0, work::add));
        assertNull(gate.pollPendingLimitWarning(0));
        for (int i = 0; i < 3; i++) assertFalse(gate.ingress(packet2, other, now, 0, work::add));
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 3), gate.pollPendingLimitWarning(0));
        assertTrue(work.isEmpty()); assertEquals(1, gate.stats().claims());
        for (int i = 0; i < 2; i++) assertFalse(gate.ingress(packet2, other, now, 0, work::add));
        assertNull(gate.pollPendingLimitWarning(4_999_999_999L));
        assertArrayEquals(packet1, gate.ready(r));
        // A short burst must still be reported even after the queue has drained.
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 2), gate.pollPendingLimitWarning(5_000_000_000L));
        assertNull(gate.pollPendingLimitWarning(10_000_000_000L));
        assertFalse(gate.ingress(packet2, other, now, 0, work::add));
        assertEquals(1, work.size()); assertEquals(1, gate.stats().pending());
        assertEquals(5, gate.stats().capacityRejected());
    }
    @Test void firstAuthenticatedPacketIsOwnedDeferredAndTransferredOnlyOnce() {
        var gate = gate(); var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        byte[] input = valid.clone();
        assertFalse(gate.ingress(input, first, now, 0, work::add));
        var r = work.remove(); Arrays.fill(input, (byte)0);
        for (int i = 0; i < 10; i++) assertFalse(gate.ingress(valid, first, now, 0, work::add));
        assertTrue(work.isEmpty()); assertEquals(1, gate.stats().pending());
        assertArrayEquals(valid, gate.ready(r)); assertEquals(0, gate.stats().pending());
        assertNull(gate.ready(r)); // duplicate readiness cannot replay again
    }
    @Test void closedAndTimedOutReservationsNeverReleaseDeferredPackets() {
        var gate = gate(); var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        gate.ingress(valid, first, now, 0, work::add); var r = work.remove();
        gate.close();
        assertNull(gate.ready(r)); assertNull(gate.admission(r));
        gate = gate(); var timed = gate;
        timed.ingress(valid, first, now, 0, work::add); r = work.remove();
        timed.sweep(now + 1000, 1_000_000_000L);
        assertNull(timed.ready(r));
    }
    @Test void invalidTrafficHasNoReservationsOrQueuedWork() {
        var gate = gate(); var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        for (int i = 0; i < 1000; i++) assertFalse(gate.ingress(binding(token + ":" + remote, "wrong-password-000000000000"), first, now, 0, work::add));
        assertEquals(0, gate.stats().sessions()); assertEquals(0, gate.stats().claims()); assertTrue(work.isEmpty());
        assertEquals(1000, gate.stats().invalid());
    }
    @Test void concurrentRetransmitsCreateOnlyOneAndConflictingTupleCannotClaim() throws Exception {
        var gate = gate(); var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) calls.add(() -> gate.ingress(valid, first, now, 0, work::add));
            for (Future<Boolean> result : executor.invokeAll(calls)) assertFalse(result.get());
        }
        assertEquals(1, work.size()); assertEquals(1, gate.stats().accepted());
        var r = work.remove(); assertFalse(gate.ingress(valid, other, now, 0, work::add));
        assertEquals(1, gate.stats().replayRejected()); assertArrayEquals(valid, gate.ready(r)); gate.connected(r);
        assertTrue(gate.ingress(valid, first, now + 120_000, 120_000_000_000L, work::add));
        assertEquals(0, gate.sweep(now + 120_000, 120_000_000_000L).size());
        assertEquals(1, gate.stats().claims()); // active consent is not expiry eviction
        assertTrue(gate.finish(r)); assertNull(gate.admission(r));
        assertFalse(gate.ingress(valid, first, now, 0, work::add)); // failed/closed cannot allocate again
        assertEquals(1, gate.stats().claims());
        gate.sweep(now + 120_000, 120_000_000_000L); assertEquals(0, gate.stats().claims());
    }
    @Test void timeoutCapacityQueueFailureAndCloseAreTerminal() {
        var gate = gate(); var work = new ArrayBlockingQueue<AdmissionGate.Reservation>(1);
        gate.ingress(valid, first, now, 0, work::add); var r = work.remove();
        assertEquals(List.of(r), gate.sweep(now + 1000, 1_000_000_000));
        assertNull(gate.ready(r)); assertNull(gate.admission(r)); assertEquals(0, gate.stats().pending());
        gate.close();assertEquals(0, gate.stats().claims());
        assertFalse(gate.ingress(valid, first, now, 0, work::add));assertTrue(work.isEmpty());
        var failed = gate(); failed.ingress(valid, first, now, 0, ignored -> { throw new RejectedExecutionException(); });
        assertEquals(0, failed.stats().sessions()); assertEquals(1, failed.stats().claims());
        assertFalse(failed.ingress(valid, first, now, 0, work::add)); assertTrue(work.isEmpty());
        var drained = gate(); drained.drain();assertFalse(drained.ingress(valid, first, now, 0, work::add));assertEquals(0, drained.stats().claims());
    }
}
