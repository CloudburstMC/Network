package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.channel.nethernet.admission.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionGateTest extends AdmissionFixture {
    final InetSocketAddress other = new InetSocketAddress("127.0.0.1", 23451);
    AdmissionGate gate() { return new AdmissionGate(new AdmissionGate.Limits(2, 2, 1, 1000), validator()); }
    AdmissionRequest elsewhere() { return new AdmissionRequest(token, remote, other); }

    @Test void invalidTokensNeverReserveCapacity() {
        var gate = gate();
        for (int i = 0; i < 1000; i++) assertNull(gate.reserve(request("invalidToken", remote), now, 0));
        assertEquals(0, gate.stats().sessions()); assertEquals(0, gate.stats().claims());
        assertEquals(1000, gate.stats().invalid());
    }
    @Test void concurrentAttemptsShareOneTokenReservation() throws Exception {
        var gate = gate();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<AdmissionGate.Reservation>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) calls.add(() -> gate.reserve(request(), now, 0));
            int reserved = 0;
            for (var result : executor.invokeAll(calls)) if (result.get() != null) reserved++;
            assertEquals(1, reserved);
        }
        assertEquals(1, gate.stats().sessions()); assertEquals(1, gate.stats().pending());
        assertNull(gate.reserve(elsewhere(), now, 0));
    }
    @Test void nativeVerificationFailureDoesNotConsumeToken() {
        var gate = gate();
        var forged = gate.reserve(request(), now, 0);
        assertNotNull(forged); assertEquals(0, gate.stats().accepted());
        gate.invalidNativeRequest(); assertTrue(gate.finish(forged));
        assertEquals(0, gate.stats().claims()); assertEquals(0, gate.stats().sessions());
        var legitimate = gate.reserve(elsewhere(), now, 1);
        assertNotNull(legitimate); assertTrue(gate.ready(legitimate));
        assertFalse(gate.ready(legitimate)); assertEquals(1, gate.stats().accepted());
    }
    @Test void acceptedTokensCannotAllocateAgainAndActiveSessionsOutliveTokenExpiry() {
        var gate = gate(); var r = gate.reserve(request(), now, 0);
        assertTrue(gate.ready(r)); gate.connected(r);
        assertNull(gate.reserve(elsewhere(), now, 0));
        assertEquals(1, gate.stats().replayRejected());
        assertTrue(gate.sweep(now + 120_000, 120_000_000_000L).isEmpty());
        assertNotNull(gate.admission(r));
        assertTrue(gate.finish(r)); assertNull(gate.admission(r));
        assertNull(gate.reserve(request(), now, 0));
        assertEquals(1, gate.stats().claims());
        gate.sweep(now + 120_000, 120_000_000_000L); assertEquals(0, gate.stats().claims());
    }
    @Test void timeoutAndShutdownKeepCapacityUntilNativeTeardownCompletes() {
        var gate = gate(); var r = gate.reserve(request(), now, 0); assertTrue(gate.ready(r));
        assertEquals(List.of(r), gate.sweep(now + 1000, 1_000_000_000L));
        assertTrue(gate.sweep(now + 2000, 2_000_000_000L).isEmpty());
        assertNull(gate.admission(r)); assertEquals(1, gate.stats().sessions());
        assertTrue(gate.finish(r)); assertEquals(0, gate.stats().sessions());
        gate = gate(); r = gate.reserve(request(), now, 0);
        assertEquals(List.of(r), gate.close());
        assertFalse(gate.ready(r)); assertNull(gate.admission(r));
        assertEquals(1, gate.stats().pending()); assertNull(gate.reserve(request(), now, 0));
        assertTrue(gate.finish(r)); assertEquals(0, gate.stats().pending()); assertEquals(0, gate.stats().claims());
    }
    @Test void drainingRejectsNewReservations() {
        var gate = gate(); gate.drain();
        assertNull(gate.reserve(request(), now, 0));
        assertEquals(0, gate.stats().claims()); assertEquals(1, gate.stats().capacityRejected());
    }
    @Test void pendingWarningsAreAggregatedAndRateLimited() throws Exception {
        assertEquals(1024, AdmissionGate.Limits.defaults().pending());
        var trusted = validator().validate(request(), now);
        var v = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        var gate = new AdmissionGate(new AdmissionGate.Limits(2, 4, 1, 1000), v);
        var first = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199, now + 30_000, TestSignallingProvider.AUDIENCE, false);
        var next = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199, now + 30_000, TestSignallingProvider.AUDIENCE, false);
        var r = gate.reserve(request(first.token(), remote), now, 0);
        var nextRequest = new AdmissionRequest(next.token(), remote, other);
        assertNull(gate.reserve(request("invalidToken", remote), now, 0));
        assertNull(gate.pollPendingLimitWarning(0));
        for (int i = 0; i < 3; i++) assertNull(gate.reserve(nextRequest, now, 0));
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 3), gate.pollPendingLimitWarning(0));
        for (int i = 0; i < 2; i++) assertNull(gate.reserve(nextRequest, now, 0));
        assertNull(gate.pollPendingLimitWarning(4_999_999_999L));
        assertTrue(gate.ready(r));
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 2), gate.pollPendingLimitWarning(5_000_000_000L));
        assertNotNull(gate.reserve(nextRequest, now, 0)); assertEquals(1, gate.stats().pending());
        assertEquals(5, gate.stats().capacityRejected());
    }
}
