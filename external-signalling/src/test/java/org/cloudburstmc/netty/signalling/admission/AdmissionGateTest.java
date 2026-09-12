package org.cloudburstmc.netty.signalling.admission;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionGateTest extends AdmissionFixture {
    final InetSocketAddress other = new InetSocketAddress("127.0.0.1", 23451);

    AdmissionGate gate() {
        return new AdmissionGate(new AdmissionGate.Limits(2, 2, 1, 1000), validator());
    }

    AdmissionRequest elsewhere() {
        return new AdmissionRequest(token, remote, other);
    }

    /** A second ticket, minted for the same client so only the source tuple differs. */
    private String anotherToken() throws Exception {
        var trusted = validator().validate(request(), now);
        return TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199,
                now + 30_000, f.getAsJsonObject("context").get("audience").getAsString(), false).token();
    }

    @Test
    void refusesLimitsItCouldNotHonour() {
        assertDoesNotThrow(AdmissionGate.Limits::defaults);

        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(0, 2, 1, 1000), "no sessions");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(65537, 65537, 1, 1000),
                "more sessions than it will hold");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(4, 3, 1, 1000),
                "fewer claims than sessions, which would strand capacity");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(2, 262145, 1, 1000),
                "more claims than it will hold");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(2, 2, 0, 1000), "no pending");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(2, 2, 3, 1000),
                "more pending than sessions");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(2, 2, 1, 99),
                "a handshake window too short to complete one");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionGate.Limits(2, 2, 1, 120_001),
                "or long enough to hold capacity all day");
    }

    @Test
    void holdsOneReservationPerSourceTuple() throws Exception {
        // Another ticket from the same address must not take a second slot
        var gate = gate();
        assertNotNull(gate.reserve(request(), now, 0));

        assertNull(gate.reserve(request(anotherToken(), remote), now, 0));
        assertEquals(1, gate.stats().replayRejected());
        assertEquals(1, gate.stats().sessions());
    }

    @Test
    void refusesWhenTheClaimsItRemembersAreFull() throws Exception {
        // Sessions are free, but every token it has seen is still remembered
        var gate = new AdmissionGate(new AdmissionGate.Limits(2, 2, 2, 1000), validator());
        var first = gate.reserve(request(), now, 0);
        assertNotNull(first);
        assertTrue(gate.ready(first));
        gate.finish(first);

        assertNotNull(gate.reserve(request(anotherToken(), remote), now, 0));
        assertNull(gate.reserve(new AdmissionRequest(anotherToken(), remote, other), now, 0));
        assertEquals(1, gate.stats().capacityRejected());
    }

    @Test
    void tellsTheCallerWhatItReservedWithoutPrintingIt() {
        var gate = gate();
        var reservation = gate.reserve(request(), now, 12345);

        assertEquals(validator().validate(request(), now).tokenId(), reservation.tokenId());
        assertEquals(new InetSocketAddress("127.0.0.1", 23450), reservation.tuple());
        assertEquals(12345, reservation.acceptedNanos());
        assertFalse(reservation.toString().contains(token), "a ticket must never reach a log");
    }

    @Test
    void letsAReservationBecomeReadyOnlyOnce() {
        // A used token must not allocate a second peer
        var gate = gate();
        var reservation = gate.reserve(request(), now, 0);

        assertTrue(gate.ready(reservation));
        assertFalse(gate.ready(reservation));
        assertEquals(1, gate.stats().accepted());
    }

    @Test
    void refusesToReadyAReservationThatTimedOut() {
        var gate = gate();
        var reservation = gate.reserve(request(), now, 0);

        assertEquals(List.of(reservation), gate.sweep(now, 2_000_000_000L));
        assertFalse(gate.ready(reservation), "a handshake that ran out of time cannot finish later");
        assertNull(gate.admission(reservation));
    }

    @Test
    void finishesAReservationOnlyOnce() {
        var gate = gate();
        var reservation = gate.reserve(request(), now, 0);

        assertTrue(gate.finish(reservation));
        assertFalse(gate.finish(reservation), "a second teardown must not free capacity twice");
        assertNull(gate.admission(reservation));
        assertFalse(gate.ready(reservation));
    }

    @Test
    void invalidTokensNeverReserveCapacity() {
        var gate = gate();
        for (int i = 0; i < 1000; i++) {
            assertNull(gate.reserve(request("invalidToken", remote), now, 0));
        }
        assertEquals(0, gate.stats().sessions());
        assertEquals(0, gate.stats().claims());
        assertEquals(1000, gate.stats().invalid());
    }

    @Test
    void concurrentAttemptsShareOneTokenReservation() throws Exception {
        var gate = gate();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<AdmissionGate.Reservation>> calls = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                calls.add(() -> gate.reserve(request(), now, 0));
            }
            int reserved = 0;
            for (var result : executor.invokeAll(calls)) {
                if (result.get() != null) {
                    reserved++;
                }
            }
            assertEquals(1, reserved);
        } finally {
            executor.shutdown();
        }
        assertEquals(1, gate.stats().sessions());
        assertEquals(1, gate.stats().pending());
        assertNull(gate.reserve(elsewhere(), now, 0));
    }

    @Test
    void nativeVerificationFailureDoesNotConsumeToken() {
        var gate = gate();
        var forged = gate.reserve(request(), now, 0);
        assertNotNull(forged);
        assertEquals(0, gate.stats().accepted());
        gate.invalidNativeRequest();
        assertTrue(gate.finish(forged));
        assertEquals(0, gate.stats().claims());
        assertEquals(0, gate.stats().sessions());
        var legitimate = gate.reserve(elsewhere(), now, 1);
        assertNotNull(legitimate);
        assertTrue(gate.ready(legitimate));
        assertFalse(gate.ready(legitimate));
        assertEquals(1, gate.stats().accepted());
    }

    @Test
    void acceptedTokensCannotAllocateAgainAndActiveSessionsOutliveTokenExpiry() {
        var gate = gate();
        var r = gate.reserve(request(), now, 0);
        assertTrue(gate.ready(r));
        gate.connected(r);
        assertNull(gate.admission(r).identityVerifier().acceptForwardedIdentity());
        assertNull(gate.reserve(elsewhere(), now, 0));
        assertEquals(1, gate.stats().replayRejected());
        assertTrue(gate.sweep(now + 120_000, 120_000_000_000L).isEmpty());
        assertNotNull(gate.admission(r));
        assertTrue(gate.finish(r));
        assertNull(gate.admission(r));
        assertNull(gate.reserve(request(), now, 0));
        assertEquals(1, gate.stats().claims());
        gate.sweep(now + 120_000, 120_000_000_000L);
        assertEquals(0, gate.stats().claims());
    }

    @Test
    void timeoutAndShutdownKeepCapacityUntilNativeTeardownCompletes() {
        var gate = gate();
        var r = gate.reserve(request(), now, 0);
        assertTrue(gate.ready(r));
        assertEquals(List.of(r), gate.sweep(now + 1000, 1_000_000_000L));
        assertTrue(gate.sweep(now + 2000, 2_000_000_000L).isEmpty());
        assertNull(gate.admission(r));
        assertEquals(1, gate.stats().sessions());
        assertTrue(gate.finish(r));
        assertEquals(0, gate.stats().sessions());
        gate = gate();
        r = gate.reserve(request(), now, 0);
        assertEquals(List.of(r), gate.close());
        assertFalse(gate.ready(r));
        assertNull(gate.admission(r));
        assertEquals(1, gate.stats().pending());
        assertNull(gate.reserve(request(), now, 0));
        assertTrue(gate.finish(r));
        assertEquals(0, gate.stats().pending());
        assertEquals(0, gate.stats().claims());
    }

    @Test
    void drainingRejectsNewReservations() {
        var gate = gate();
        gate.drain();
        assertNull(gate.reserve(request(), now, 0));
        assertEquals(0, gate.stats().claims());
        assertEquals(1, gate.stats().capacityRejected());
    }

    @Test
    void pendingWarningsAreAggregatedAndRateLimited() throws Exception {
        assertEquals(1024, AdmissionGate.Limits.defaults().pending());
        var trusted = validator().validate(request(), now);
        var v = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        var gate = new AdmissionGate(new AdmissionGate.Limits(2, 4, 1, 1000), v);
        var first = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199,
                now + 30_000, TestSignallingProvider.AUDIENCE, false);
        var next = TestSignallingProvider.answer(trusted.remoteDescription(), trusted.remoteFingerprint(), 49199,
                now + 30_000, TestSignallingProvider.AUDIENCE, false);
        var r = gate.reserve(request(first.token(), remote), now, 0);
        var nextRequest = new AdmissionRequest(next.token(), remote, other);
        assertNull(gate.reserve(request("invalidToken", remote), now, 0));
        assertNull(gate.pollPendingLimitWarning(0));
        for (int i = 0; i < 3; i++) {
            assertNull(gate.reserve(nextRequest, now, 0));
        }
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 3), gate.pollPendingLimitWarning(0));
        for (int i = 0; i < 2; i++) {
            assertNull(gate.reserve(nextRequest, now, 0));
        }
        assertNull(gate.pollPendingLimitWarning(4_999_999_999L));
        assertTrue(gate.ready(r));
        assertEquals(new AdmissionGate.PendingLimitWarning(1, 1, 2), gate.pollPendingLimitWarning(5_000_000_000L));
        assertNotNull(gate.reserve(nextRequest, now, 0));
        assertEquals(1, gate.stats().pending());
        assertEquals(5, gate.stats().capacityRejected());
    }
}
