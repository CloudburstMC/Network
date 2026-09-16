package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointConnectivityController.*;
import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.*;
import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelectionTest.endpoint;
import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelectionTest.hint;
import static org.junit.jupiter.api.Assertions.*;

class EndpointConnectivityControllerTest {
    static final Map<Family, InetSocketAddress> SERVERS = Map.of(Family.IPV4, endpoint("1.1.1.1", 3478),
            Family.IPV6, endpoint("2606:4700:4700::1111", 3478));
    static final class FakeMonitor implements Monitor {
        Optional<Sample> sample = Optional.empty();
        boolean closed, fail;
        int closeFailures, closeCalls;
        Runnable beforeRead = () -> {};
        public Optional<Sample> read() { if (closed || fail) throw new IllegalStateException(); beforeRead.run(); return sample; }
        public void close() { closeCalls++; if (closeFailures-- > 0) throw new IllegalStateException("close failed"); closed = true; }
        void success(Family family, String ip, int port, long revision, long responses, long age) {
            sample = Optional.of(new Sample(SERVERS.get(family), endpoint(ip, port), TransactionState.SUCCEEDED,
                    responses, 0, revision, Optional.of(Duration.ofMillis(age))));
        }
    }
    static final class Fixture {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final Map<Family, FakeMonitor> monitors = new EnumMap<>(Family.class);
        int opens;
        final EndpointConnectivityController controller;
        Fixture(EndpointSelection selection) {
            controller = new EndpointConnectivityController(selection, SERVERS, Duration.ofSeconds(30), server -> {
                opens++;
                var monitor = new FakeMonitor(); monitors.put(Family.of(server.getAddress()), monitor); return monitor;
            }, clock::get);
        }
        Snapshot snapshot() { return controller.snapshot(); }
        State state(Family family) { return snapshot().families().get(family).state(); }
    }
    static EndpointSelection automatic(List<Candidate> hints) { return select(endpoint("::", 19133), List.of(), hints); }

    @Test void explicitEndpointsDisableStunAcrossBothFamiliesEvenAfterFailedChecks() {
        var f = new Fixture(select(endpoint("::", 19133), List.of(endpoint("8.8.8.8", 40000)), List.of()));
        var check = f.controller.beginDirectCheck(Family.IPV4);
        assertTrue(f.controller.completeDirectCheck(check, CheckOutcome.FAILED));
        assertEquals(State.CONFIGURED, f.state(Family.IPV4));
        assertEquals(State.DISABLED_BY_CONFIG, f.state(Family.IPV6));
        assertEquals(0, f.opens);
        f.controller.close();
    }

    @Test void directChecksAreCorrelatedAndUnknownDoesNotStartStun() {
        var f = new Fixture(automatic(List.of(hint("8.8.8.8", Provenance.NATIVE_HOST),
                hint("2606:4700:4700::1111", Provenance.SERVER_PROPERTIES))));
        assertEquals(State.AWAITING_DIRECT_CHECK, f.state(Family.IPV4));
        assertEquals(State.AWAITING_DIRECT_CHECK, f.state(Family.IPV6));
        assertEquals(0, f.opens);
        var old = f.controller.beginDirectCheck(Family.IPV4);
        var check = f.controller.beginDirectCheck(Family.IPV4);
        assertFalse(f.controller.completeDirectCheck(old, CheckOutcome.FAILED));
        assertTrue(f.controller.completeDirectCheck(check, CheckOutcome.UNKNOWN));
        assertEquals(State.AWAITING_DIRECT_CHECK, f.state(Family.IPV4));
        assertEquals(0, f.opens);
        assertFalse(f.controller.completeDirectCheck(check, CheckOutcome.FAILED));
        f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.FAILED);
        f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV6), CheckOutcome.SUCCEEDED);
        assertEquals(State.STUN_PENDING, f.state(Family.IPV4));
        assertEquals(State.DIRECT_CHECK_SUCCEEDED, f.state(Family.IPV6));
        assertEquals(1, f.opens);
        f.controller.close();
    }

    @Test void remapsRefreshesExpiryAndRecoveryPreserveIndependentFamilyState() {
        var f = new Fixture(automatic(List.of()));
        f.snapshot(); assertEquals(2, f.opens);
        var v4 = f.monitors.get(Family.IPV4); var v6 = f.monitors.get(Family.IPV6);
        v4.success(Family.IPV4, "8.8.8.8", 40000, 1, 1, 0);
        v6.success(Family.IPV6, "2606:4700:4700::1111", 40000, 1, 1, 0);
        var first = f.snapshot();
        assertEquals(State.STUN_FRESH, first.families().get(Family.IPV4).state());
        v4.success(Family.IPV4, "8.8.8.8", 40000, 1, 2, 0);
        assertEquals(first.candidateRevision(), f.snapshot().candidateRevision(), "Unchanged keepalive is not a candidate change");
        v4.success(Family.IPV4, "8.8.8.8", 40001, 2, 3, 0);
        var remapped = f.snapshot();
        assertEquals(first.candidateRevision() + 1, remapped.candidateRevision());
        assertEquals(40001, remapped.families().get(Family.IPV4).freshStunEndpoint().orElseThrow().getPort());
        var observation = remapped.families().get(Family.IPV4).observation().orElseThrow();
        assertTrue(observation.freshAt(f.clock.get()));
        f.clock.addAndGet(Duration.ofSeconds(30).toNanos());
        assertFalse(observation.freshAt(f.clock.get()), "Retaining an old snapshot cannot extend its deadline");
        v4.success(Family.IPV4, "8.8.8.8", 40001, 2, 3, 30_000);
        var stale = f.snapshot();
        assertEquals(State.STUN_STALE, stale.families().get(Family.IPV4).state());
        assertTrue(stale.families().get(Family.IPV4).freshStunEndpoint().isEmpty());
        assertEquals(State.STUN_FRESH, stale.families().get(Family.IPV6).state());
        assertEquals(remapped.candidateRevision() + 1, stale.candidateRevision());
        v4.success(Family.IPV4, "8.8.8.8", 40001, 2, 4, 0);
        assertEquals(State.STUN_FRESH, f.state(Family.IPV4));
        assertEquals(2, f.opens, "Native retries keep the existing monitor");
        f.controller.close();
        assertTrue(v4.closed && v6.closed);
        assertTrue(f.snapshot().families().values().stream().allMatch(s -> s.state() == State.CLOSED
                && s.directCandidates().isEmpty() && s.freshStunEndpoint().isEmpty()));
        assertEquals(2, f.opens);
    }

    @Test void replacementEpochWithdrawsOldStateAndFailuresNeverSpinNewMonitors() {
        var f = new Fixture(automatic(List.of()));
        f.snapshot(); var first = f.monitors.get(Family.IPV4);
        first.success(Family.IPV4, "8.8.8.8", 40000, 1, 1, 0);
        long epoch = f.snapshot().families().get(Family.IPV4).observation().orElseThrow().monitorEpoch();
        f.controller.replaceStunServer(Family.IPV4, SERVERS.get(Family.IPV4));
        assertTrue(first.closed);
        assertTrue(f.snapshot().families().get(Family.IPV4).freshStunEndpoint().isEmpty());
        var second = f.monitors.get(Family.IPV4);
        second.success(Family.IPV4, "8.8.8.8", 40000, 1, 1, 0);
        assertEquals(epoch + 1, f.snapshot().families().get(Family.IPV4).observation().orElseThrow().monitorEpoch());
        second.fail = true;
        assertEquals(State.MONITOR_FAILED, f.state(Family.IPV4));
        assertTrue(second.closed);
        assertEquals(State.MONITOR_FAILED, f.state(Family.IPV4));
        assertEquals(3, f.opens);
        assertThrows(IllegalArgumentException.class, () -> f.controller.replaceStunServer(Family.IPV4, SERVERS.get(Family.IPV6)));
        f.controller.close();
    }

    @Test void privateMappingsAndWrongSourcesNeverBecomePublicCandidates() {
        var f = new Fixture(automatic(List.of()));
        f.snapshot(); var monitor = f.monitors.get(Family.IPV4);
        monitor.success(Family.IPV4, "192.168.1.10", 40000, 1, 1, 0);
        assertEquals(State.STUN_INELIGIBLE, f.state(Family.IPV4));
        assertTrue(f.snapshot().families().get(Family.IPV4).freshStunEndpoint().isEmpty());
        monitor.sample = Optional.of(new Sample(SERVERS.get(Family.IPV6), endpoint("8.8.8.8", 40000),
                TransactionState.SUCCEEDED, 1, 0, 1, Optional.of(Duration.ZERO)));
        assertEquals(State.MONITOR_FAILED, f.state(Family.IPV4));
        f.controller.close();
    }

    @Test void unsupportedFamilyNeverCreatesAnIndependentSocket() {
        var f = new Fixture(select(endpoint("0.0.0.0", 19133), List.of(), List.of()));
        assertEquals(State.UNSUPPORTED_FAMILY, f.state(Family.IPV6));
        assertEquals(1, f.opens);
        f.controller.close();
    }

    @Test void failedRefreshRetainsHistoricalObservationOnlyUntilItsOriginalDeadline() {
        var f = new Fixture(automatic(List.of()));
        f.snapshot(); var monitor = f.monitors.get(Family.IPV4);
        monitor.sample = Optional.of(new Sample(SERVERS.get(Family.IPV4), null, TransactionState.FAILED,
                0, 1, 0, Optional.empty()));
        assertEquals(State.STUN_FAILED, f.state(Family.IPV4));
        monitor.sample = Optional.of(new Sample(SERVERS.get(Family.IPV4), endpoint("8.8.8.8", 40000), TransactionState.FAILED,
                1, 2, 1, Optional.of(Duration.ofSeconds(29))));
        var warm = f.snapshot().families().get(Family.IPV4);
        assertEquals(State.STUN_FRESH, warm.state());
        assertEquals(TransactionState.FAILED, warm.observation().orElseThrow().transactionState());
        monitor.beforeRead = () -> f.clock.addAndGet(Duration.ofSeconds(2).toNanos());
        var delayed = f.snapshot().families().get(Family.IPV4);
        assertEquals(State.STUN_STALE, delayed.state(), "A delayed native read consumes the remaining observation lifetime");
        assertTrue(delayed.freshStunEndpoint().isEmpty());
        assertEquals(2, f.opens, "Native transaction failure must not create fresh monitors or reset native age");
        f.controller.close();
    }

    @Test void lateDirectCompletionCannotStartFallbackOrExtendAReport() {
        var f = new Fixture(automatic(List.of(hint("8.8.8.8", Provenance.NATIVE_HOST))));
        var late = f.controller.beginDirectCheck(Family.IPV4, Duration.ofSeconds(10));
        f.clock.addAndGet(Duration.ofSeconds(10).toNanos());
        assertFalse(f.controller.completeDirectCheck(late, CheckOutcome.FAILED));
        assertEquals(State.AWAITING_DIRECT_CHECK, f.state(Family.IPV4));
        assertNull(f.monitors.get(Family.IPV4));
        var current = f.controller.beginDirectCheck(Family.IPV4, Duration.ofSeconds(10));
        f.clock.addAndGet(Duration.ofSeconds(9).toNanos());
        assertTrue(f.controller.completeDirectCheck(current, CheckOutcome.SUCCEEDED));
        var report = f.snapshot().families().get(Family.IPV4);
        assertEquals(current.expiresAtNanos(), report.directCheckExpiresAtNanos().orElseThrow());
        assertEquals(CheckOutcome.SUCCEEDED, report.directCheckAt(f.clock.get()));
        f.clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(CheckOutcome.UNKNOWN, report.directCheckAt(f.clock.get()));
        var expired = f.snapshot().families().get(Family.IPV4);
        assertEquals(CheckOutcome.UNKNOWN, expired.directCheck());
        assertEquals(State.AWAITING_DIRECT_CHECK, expired.state());
        assertTrue(expired.directCheckExpiresAtNanos().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.controller.beginDirectCheck(Family.IPV4, Duration.ofMinutes(6)));
        assertThrows(IllegalArgumentException.class, () -> f.controller.beginDirectCheck(Family.IPV4, Duration.ZERO));
        f.controller.close();
    }

    @Test void retainedSuccessKeepsItsOriginalDeadlineAndMaterialChangeFencesOutstandingChecks() {
        var f = new Fixture(automatic(List.of(hint("8.8.8.8", Provenance.NATIVE_HOST))));
        var positive = f.controller.beginDirectCheck(Family.IPV4, Duration.ofSeconds(10));
        assertTrue(f.controller.completeDirectCheck(positive, CheckOutcome.SUCCEEDED));
        f.clock.addAndGet(Duration.ofSeconds(5).toNanos());
        assertFalse(f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.FAILED));
        assertFalse(f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.UNKNOWN));
        assertTrue(f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4, Duration.ofSeconds(1)), CheckOutcome.SUCCEEDED));
        assertEquals(positive.expiresAtNanos(), f.snapshot().families().get(Family.IPV4).directCheckExpiresAtNanos().orElseThrow());
        var pending = f.controller.beginDirectCheck(Family.IPV4);
        f.controller.invalidateDirectChecks();
        assertFalse(f.controller.completeDirectCheck(pending, CheckOutcome.SUCCEEDED));
        assertEquals(State.AWAITING_DIRECT_CHECK, f.state(Family.IPV4));
        assertNull(f.monitors.get(Family.IPV4));
        assertTrue(f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.FAILED));
        assertEquals(State.STUN_PENDING, f.state(Family.IPV4));
        var monitor = f.monitors.get(Family.IPV4);
        f.controller.invalidateDirectChecks();
        assertEquals(State.STUN_PENDING, f.state(Family.IPV4));
        assertSame(monitor, f.monitors.get(Family.IPV4));
        assertFalse(monitor.closed);
        f.controller.close();
    }

    @Test void expiredFailureBecomesUnknownWhilePreviouslyStartedStunStaysWarm() {
        var f = new Fixture(automatic(List.of(hint("8.8.8.8", Provenance.NATIVE_HOST))));
        var check = f.controller.beginDirectCheck(Family.IPV4, Duration.ofSeconds(10));
        assertTrue(f.controller.completeDirectCheck(check, CheckOutcome.FAILED));
        f.snapshot(); var monitor = f.monitors.get(Family.IPV4);
        monitor.success(Family.IPV4, "8.8.8.8", 40000, 1, 1, 0);
        var first = f.snapshot();
        f.clock.addAndGet(Duration.ofSeconds(11).toNanos());
        monitor.success(Family.IPV4, "8.8.8.8", 40000, 1, 2, 0);
        var expired = f.snapshot();
        var family = expired.families().get(Family.IPV4);
        assertEquals(CheckOutcome.UNKNOWN, family.directCheck());
        assertEquals(State.STUN_FRESH, family.state());
        assertEquals(first.candidateRevision(), expired.candidateRevision());
        assertFalse(monitor.closed);
        assertEquals(2, f.opens, "Report expiry must not replace either family's warm monitor");
        f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.UNKNOWN);
        assertEquals(State.STUN_FRESH, f.state(Family.IPV4));
        assertFalse(monitor.closed);
        f.controller.completeDirectCheck(f.controller.beginDirectCheck(Family.IPV4), CheckOutcome.SUCCEEDED);
        assertTrue(monitor.closed, "A fresh successful direct check retires fallback");
        assertEquals(State.DIRECT_CHECK_SUCCEEDED, f.state(Family.IPV4));
        assertTrue(f.snapshot().families().get(Family.IPV4).freshStunEndpoint().isEmpty());
        f.controller.close();
    }

    @Test void failedCloseRetainsOwnedHandleWithoutFreshEndpointOrDuplicateMonitor() {
        var f = new Fixture(automatic(List.of()));
        f.snapshot(); var monitor = f.monitors.get(Family.IPV4);
        monitor.success(Family.IPV4, "8.8.8.8", 40000, 1, 1, 0);
        assertTrue(f.snapshot().families().get(Family.IPV4).freshStunEndpoint().isPresent());
        monitor.fail = true; monitor.closeFailures = 3;
        assertEquals(State.MONITOR_FAILED, f.state(Family.IPV4));
        assertTrue(f.snapshot().families().get(Family.IPV4).freshStunEndpoint().isEmpty());
        assertEquals(1, monitor.closeCalls, "Ordinary snapshots do not spin a failed close");
        assertThrows(IllegalStateException.class, () -> f.controller.replaceStunServer(Family.IPV4, SERVERS.get(Family.IPV4)));
        assertEquals(2, f.opens, "Replacement cannot open while its previous handle failed to close");
        assertThrows(IllegalStateException.class, f.controller::close);
        assertFalse(monitor.closed);
        assertTrue(f.snapshot().families().values().stream().allMatch(s -> s.state() == State.CLOSED
                && s.freshStunEndpoint().isEmpty() && s.directCheck() == CheckOutcome.UNKNOWN));
        f.controller.close();
        assertTrue(monitor.closed);
        assertEquals(4, monitor.closeCalls);
        assertEquals(2, f.opens);
    }
}
