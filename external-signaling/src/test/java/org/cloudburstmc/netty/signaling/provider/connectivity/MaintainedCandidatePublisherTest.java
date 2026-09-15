package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
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
            publisher.refresh(false);
            monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001);
        }
        @Override public void close() { publisher.close(); }
    }
    @Test void pendingAndUnissuedOwnerPublishNoReflexiveCandidate() {
        try (var h = new Harness(List.of())) {
            assertTrue(h.publisher.refresh(false).candidates().candidates().isEmpty());
            assertEquals(2, h.monitors.size()); h.both();
            assertTrue(h.publisher.refresh(false).leases().observations().isEmpty());
            var enabled = h.publisher.refresh(true);
            assertEquals(2, enabled.candidates().candidates().size());
            assertTrue(enabled.candidates().candidates().stream().allMatch(c -> c.type() == NativeCandidateSnapshot.Type.SRFLX));
        }
    }
    @Test void unchangedSuccessAndFailedTransactionRetainOriginalBytesUntilLeaseExpiry() {
        try (var h = new Harness(List.of())) {
            h.both(); var first = h.publisher.refresh(true);
            h.advance(15000); h.monitors.values().forEach(m -> m.failed++);
            var retained = h.publisher.refresh(true);
            assertEquals(first.candidates(), retained.candidates()); assertEquals(first.leases().observations(), retained.leases().observations());
            first.leases().requireCurrent(); h.advance(254999);
            assertThrows(IllegalStateException.class, first.leases()::requireCurrent);
            assertTrue(h.publisher.refresh(true).candidates().candidates().isEmpty());
        }
    }
    @Test void newerSameMappingSuccessKeepsOlderCaptureButRemapAndAbaRetireIt() {
        try (var h = new Harness(List.of())) {
            h.both(); var first = h.publisher.refresh(true); h.advance(15000);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            var renewed = h.publisher.refresh(true); first.leases().requireCurrent();
            assertEquals(first.candidates(), renewed.candidates()); assertNotEquals(first.leases().observations(), renewed.leases().observations());
            h.monitors.get(Family.IPV4).success("8.8.4.4", 43002); var remapped = h.publisher.refresh(true);
            assertThrows(IllegalStateException.class, first.leases()::requireCurrent);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000); h.publisher.refresh(true);
            assertThrows(IllegalStateException.class, remapped.leases()::requireCurrent); assertThrows(IllegalStateException.class, first.leases()::requireCurrent);
        }
    }
    @Test void failedReadWithdrawsOnlyItsFamilyAndClosesItsMonitor() {
        try (var h = new Harness(List.of())) {
            h.both(); var both = h.publisher.refresh(true); h.monitors.get(Family.IPV4).broken = true;
            var remaining = h.publisher.refresh(true);
            assertEquals(List.of("ipv6"), remaining.leases().observations().stream().map(o -> o.family()).toList());
            assertTrue(h.monitors.get(Family.IPV4).closed); assertFalse(h.monitors.get(Family.IPV6).closed);
            assertThrows(IllegalStateException.class, both.leases()::requireCurrent);
        }
    }
    @Test void unknownDirectFamilyPrecedesStunAndConfiguredSetHasNoMonitor() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.SERVER_PROPERTIES);
        try (var h = new Harness(List.of(direct))) {
            var initial = h.publisher.refresh(true);
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet()); assertEquals(1, initial.candidates().candidates().size());
            h.monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001); var both = h.publisher.refresh(true);
            assertEquals(2, both.candidates().candidates().size()); assertEquals(1, both.leases().observations().size());
            assertEquals(NativeCandidateSnapshot.Type.HOST, both.candidates().candidates().get(0).type());
        }
        var selected = EndpointSelection.select(endpoint("::", 19132), List.of(endpoint("8.8.4.4", 25565)), List.of(direct));
        try (var configured = new MaintainedCandidatePublisher(selected, null, new ObservationLeaseTracker(INCARNATION))) {
            var publication = configured.refresh(true);
            assertEquals(List.of(endpoint("8.8.4.4", 25565)), publication.candidates().candidates().stream().map(NativeCandidateSnapshot.Candidate::endpoint).toList());
            assertTrue(publication.leases().observations().isEmpty());
        }
    }
    @Test void clockFailureWithdrawsAndOnlyNewSuccessAfterSynchronizedRecoveryRestores() {
        try (var h = new Harness(List.of())) {
            h.both(); var original = h.publisher.refresh(true); h.wall += 30001;
            assertTrue(h.publisher.refresh(true).candidates().candidates().isEmpty());
            assertThrows(IllegalStateException.class, original.leases()::requireCurrent);
            h.publisher.controlSynchronized(); assertTrue(h.publisher.refresh(true).candidates().candidates().isEmpty());
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            assertEquals(1, h.publisher.refresh(true).leases().observations().size());
            assertThrows(IllegalStateException.class, original.leases()::requireCurrent);
        }
    }

    @Test void fullDirectCandidateSetKeepsItsSlotsWithoutOversizedFallbackPublication() {
        var direct = new ArrayList<EndpointSelection.Candidate>();
        for (int index = 1; index <= 32; index++) direct.add(new EndpointSelection.Candidate(endpoint("8.8.8." + index, 19132), EndpointSelection.Provenance.SERVER_PROPERTIES));
        try (var h = new Harness(direct)) {
            h.publisher.refresh(true); h.monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001);
            var publication = h.publisher.refresh(true);
            assertEquals(32, publication.candidates().candidates().size()); assertTrue(publication.leases().observations().isEmpty());
        }
    }
}
