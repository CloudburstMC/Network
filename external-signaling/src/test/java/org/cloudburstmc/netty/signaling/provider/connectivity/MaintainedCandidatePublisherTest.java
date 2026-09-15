package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;
import static org.junit.jupiter.api.Assertions.*;

class MaintainedCandidatePublisherTest {
    static final String INCARNATION = "0123456789abcdef0123456789abcdef";
    static InetSocketAddress endpoint(String ip, int port) {
        try { return new InetSocketAddress(EndpointAddress.parse(ip), port); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    static final class Harness implements AutoCloseable {
        long wall = 1789400000000L, nanos = 10000000000L;
        final Map<Family, Monitor> monitors = new EnumMap<>(Family.class);
        final MaintainedCandidatePublisher publisher;
        Harness(List<EndpointSelection.Candidate> hints) {
            var selection = EndpointSelection.select(endpoint("::", 19132), List.of(), hints);
            var servers = Map.of(Family.IPV4, endpoint("1.1.1.1", 3478), Family.IPV6, endpoint("2606:4700:4700::1111", 3478));
            var controller = new EndpointConnectivityController(selection, servers, Duration.ofMinutes(5), server -> {
                var monitor = new Monitor(server); monitors.put(Family.of(server.getAddress()), monitor); return monitor;
            }, () -> nanos);
            publisher = new MaintainedCandidatePublisher(selection, controller, new ObservationLeaseTracker(INCARNATION, () -> wall, () -> nanos));
        }
        void advance(long millis) { wall += millis; nanos += millis * 1000000; }
        final class Monitor implements EndpointConnectivityController.Monitor {
            final InetSocketAddress server; InetSocketAddress mapped;
            long succeededAt, sequence, revision = 1, failed;
            boolean broken, closed;
            Monitor(InetSocketAddress server) { this.server = server; }
            void success(String address, int port) {
                var next = endpoint(address, port); if (mapped != null && !mapped.equals(next)) revision++;
                mapped = next; sequence++; succeededAt = nanos;
            }
            @Override public Optional<EndpointConnectivityController.Sample> read() {
                if (broken) throw new IllegalStateException("monitor unavailable");
                return Optional.of(new EndpointConnectivityController.Sample(server, mapped,
                        failed > 0 ? EndpointConnectivityController.TransactionState.FAILED : sequence == 0 ? EndpointConnectivityController.TransactionState.PENDING : EndpointConnectivityController.TransactionState.SUCCEEDED,
                        sequence, failed, revision, sequence == 0 ? Optional.empty() : Optional.of(Duration.ofNanos(nanos - succeededAt))));
            }
            @Override public void close() { closed = true; }
        }
        void both() {
            publisher.refresh();
            monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001);
        }
        @Override public void close() { publisher.close(); }
    }
    @Test void pendingHasNoReflexiveCandidateButFreshNativeObservationNeedsNoOwnerProtocol() {
        try (var h = new Harness(List.of())) {
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertEquals(2, h.monitors.size()); h.both();
            var enabled = h.publisher.refresh();
            assertEquals(2, enabled.candidates().candidates().size());
            assertTrue(enabled.candidates().candidates().stream().allMatch(c -> c.type() == NativeCandidateSnapshot.Type.SRFLX));
        }
    }
    @Test void unchangedSuccessAndFailedTransactionRetainOriginalBytesUntilLeaseExpiry() {
        try (var h = new Harness(List.of())) {
            h.both(); var first = h.publisher.refresh();
            h.advance(15000); h.monitors.values().forEach(m -> m.failed++);
            var retained = h.publisher.refresh();
            assertEquals(first.candidates(), retained.candidates()); assertEquals(first.expiries(), retained.expiries());
            first.requireCurrent(); h.advance(254999);
            assertThrows(IllegalStateException.class, first::requireCurrent);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
        }
    }
    @Test void newerSameMappingSuccessKeepsOlderCaptureButRemapAndAbaRetireIt() {
        try (var h = new Harness(List.of())) {
            h.both(); var first = h.publisher.refresh(); h.advance(15000);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            var renewed = h.publisher.refresh(); first.requireCurrent();
            assertEquals(first.candidates(), renewed.candidates()); assertNotEquals(first.expiries(), renewed.expiries());
            h.monitors.get(Family.IPV4).success("8.8.4.4", 43002); var remapped = h.publisher.refresh();
            assertThrows(IllegalStateException.class, first::requireCurrent);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000); h.publisher.refresh();
            assertThrows(IllegalStateException.class, remapped::requireCurrent); assertThrows(IllegalStateException.class, first::requireCurrent);
        }
    }
    @Test void failedReadWithdrawsOnlyItsFamilyAndClosesItsMonitor() {
        try (var h = new Harness(List.of())) {
            h.both(); var both = h.publisher.refresh(); h.monitors.get(Family.IPV4).broken = true;
            var remaining = h.publisher.refresh();
            assertEquals(List.of(Family.IPV6), remaining.expiries().keySet().stream().map(c -> c.family()).toList());
            assertTrue(h.monitors.get(Family.IPV4).closed); assertFalse(h.monitors.get(Family.IPV6).closed);
            assertThrows(IllegalStateException.class, both::requireCurrent);
        }
    }
    @Test void unknownDirectFamilyPrecedesStunAndConfiguredSetHasNoMonitor() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        try (var h = new Harness(List.of(direct))) {
            var initial = h.publisher.refresh();
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet()); assertEquals(1, initial.candidates().candidates().size());
            h.monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001); var both = h.publisher.refresh();
            assertEquals(2, both.candidates().candidates().size()); assertEquals(1, both.expiries().size());
            assertEquals(NativeCandidateSnapshot.Type.HOST, both.candidates().candidates().get(0).type());
        }
        var selected = EndpointSelection.select(endpoint("::", 19132), List.of(endpoint("8.8.4.4", 25565)), List.of(direct));
        try (var configured = new MaintainedCandidatePublisher(selected, null, new ObservationLeaseTracker(INCARNATION))) {
            var publication = configured.refresh();
            assertEquals(List.of(endpoint("8.8.4.4", 25565)), publication.candidates().candidates().stream().map(NativeCandidateSnapshot.Candidate::endpoint).toList());
            assertTrue(publication.expiries().isEmpty());
        }
    }
    @Test void clockFailureWithdrawsAndOnlyNewSuccessAfterSynchronizedRecoveryRestores() {
        try (var h = new Harness(List.of())) {
            h.both(); var original = h.publisher.refresh(); h.wall += 30001;
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertThrows(IllegalStateException.class, original::requireCurrent);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            assertEquals(1, h.publisher.refresh().expiries().size());
            assertThrows(IllegalStateException.class, original::requireCurrent);
        }
    }

    @Test void fullDirectCandidateSetKeepsItsSlotsWithoutOversizedFallbackPublication() {
        var direct = new ArrayList<EndpointSelection.Candidate>();
        for (int index = 1; index <= 32; index++) direct.add(new EndpointSelection.Candidate(endpoint("8.8.8." + index, 19132), EndpointSelection.Provenance.SERVER_PROPERTIES));
        try (var h = new Harness(direct)) {
            h.publisher.refresh(); h.monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001);
            var publication = h.publisher.refresh();
            assertEquals(32, publication.candidates().candidates().size()); assertTrue(publication.expiries().isEmpty());
        }
    }

    @Test void onlyFreshNegativeDirectFeedbackStartsItsFamilyAndPositiveWins() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        try (var h = new Harness(List.of(direct))) {
            h.publisher.refresh();
            var negative = new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED, h.wall, h.wall + 1000);
            var positive = new ConnectivityCheck(4, ConnectivityOutcome.ESTABLISHED, h.wall, h.wall + 1000);
            for (var checks : List.of(List.of(new ConnectivityCheck(4, ConnectivityOutcome.UNKNOWN, h.wall, h.wall + 1000)),
                    List.of(new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED, h.wall - 2000, h.wall - 1000)),
                    List.of(new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED, h.wall + 1, h.wall + 1000)),
                    List.of(negative, positive))) {
                h.publisher.reportDirectChecks(checks, h.wall); h.publisher.refresh();
                assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            }
            h.publisher.reportDirectChecks(List.of(negative), h.wall);
            assertEquals(1, h.publisher.refresh().candidates().candidates().size(), "A previous fresh success wins across batches too");
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            h.advance(1000);
            h.publisher.reportDirectChecks(List.of(new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED,
                    h.wall, h.wall + 1000)), h.wall);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertEquals(Set.of(Family.IPV4, Family.IPV6), h.monitors.keySet());
            h.monitors.get(Family.IPV4).success("8.8.4.4", 43000);
            var mapped = h.publisher.refresh();
            assertEquals(List.of(NativeCandidateSnapshot.Type.SRFLX), mapped.candidates().candidates().stream().map(c -> c.type()).toList());
            h.publisher.reportDirectChecks(List.of(positive), h.wall);
            mapped.requireCurrent(); // A late direct result cannot tear down the chosen mapping.
            assertEquals(mapped.candidates(), h.publisher.refresh().candidates());
        }
    }

    @Test void longestOriginalPositiveDeadlineSurvivesRegressingBatchesWithoutRenewal() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        try (var h = new Harness(List.of(direct))) {
            var shortPositive = new ConnectivityCheck(4, ConnectivityOutcome.ESTABLISHED, h.wall, h.wall + 1000);
            var longPositive = new ConnectivityCheck(4, ConnectivityOutcome.ESTABLISHED, h.wall, h.wall + 2000);
            var negative = new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED, h.wall, h.wall + 3000);
            h.publisher.reportDirectChecks(List.of(shortPositive, longPositive), h.wall);
            h.advance(500);
            h.publisher.reportDirectChecks(List.of(shortPositive), h.wall);
            h.advance(500);
            h.publisher.reportDirectChecks(List.of(negative), h.wall);
            assertEquals(1, h.publisher.refresh().candidates().candidates().size());
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            h.advance(999);
            h.publisher.reportDirectChecks(List.of(longPositive), h.wall);
            h.publisher.reportDirectChecks(List.of(negative), h.wall);
            assertEquals(1, h.publisher.refresh().candidates().candidates().size());
            h.advance(1);
            h.publisher.reportDirectChecks(List.of(negative), h.wall);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty(), "Replaying success cannot move its original expiry");
            assertEquals(Set.of(Family.IPV4, Family.IPV6), h.monitors.keySet());
        }
    }

    @Test void materialRevisionRetiresPositiveWithoutStartingOrStoppingIndependentMonitor() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        try (var h = new Harness(List.of(direct))) {
            h.publisher.refresh();
            var independent = h.monitors.get(Family.IPV6);
            h.publisher.reportDirectChecks(List.of(new ConnectivityCheck(4, ConnectivityOutcome.ESTABLISHED,
                    h.wall, h.wall + 60000)), h.wall);
            h.publisher.materialChanged();
            assertEquals(1, h.publisher.refresh().candidates().candidates().size());
            assertFalse(independent.closed);
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            h.publisher.reportDirectChecks(List.of(new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED,
                    h.wall, h.wall + 1000)), h.wall);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertEquals(Set.of(Family.IPV4, Family.IPV6), h.monitors.keySet());
            assertSame(independent, h.monitors.get(Family.IPV6));
        }
    }

    @Test void configuredEndpointsIgnoreFailuresAndNoServerDoesNotWithdrawDirect() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        long now = System.currentTimeMillis();
        var failure = new ConnectivityCheck(4, ConnectivityOutcome.NOT_ESTABLISHED, now, now + 1000);
        for (boolean configured : List.of(false, true)) {
            var selected = EndpointSelection.select(endpoint("::", 19132), configured ? List.of(direct.endpoint()) : List.of(), List.of(direct));
            var controller = configured ? null : new EndpointConnectivityController(selected, Map.of(), Duration.ofMinutes(5), server -> { throw new AssertionError("No STUN"); });
            try (var publisher = new MaintainedCandidatePublisher(selected, controller, new ObservationLeaseTracker(INCARNATION))) {
                var before = publisher.refresh(); publisher.reportDirectChecks(List.of(failure), now);
                assertEquals(before.candidates(), publisher.refresh().candidates()); before.requireCurrent();
            }
        }
    }
}
