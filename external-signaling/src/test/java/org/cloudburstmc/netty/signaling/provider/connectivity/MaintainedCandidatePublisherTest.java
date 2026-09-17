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
            publisher.configureStunServers(servers);
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
    static ConnectivityCheck check(Harness h, int family, String method, String ip, int port, ConnectivityOutcome outcome) {
        return new ConnectivityCheck(family, method, endpoint(ip, port), outcome, h.wall, h.wall + 60000);
    }
    @Test void pendingMappingsNeedExactWarmProofAndKeepFamiliesIndependent() {
        try (var h = new Harness(List.of())) {
            h.both(); var pending = h.publisher.refresh();
            assertTrue(pending.candidates().candidates().isEmpty());
            assertEquals(2, pending.probeCandidates().candidates().size());
            assertTrue(pending.assistedFamilies().isEmpty());
            assertTrue(h.publisher.assistedStunServers().isEmpty(), "Only selected assisted families may gather per attempt");
            h.publisher.reportDirectChecks(List.of(check(h, 4, "warm_stun", "8.8.8.8", 43000, ConnectivityOutcome.ESTABLISHED)), h.wall);
            var promoted = h.publisher.refresh();
            assertEquals(pending.mappingRevision(), promoted.mappingRevision());
            assertEquals(List.of(endpoint("8.8.8.8", 43000)), promoted.candidates().candidates().stream().map(NativeCandidateSnapshot.Candidate::endpoint).toList());
            h.publisher.reportDirectChecks(List.of(check(h, 6, "warm_stun", "2606:4700:4700::1001", 43001, ConnectivityOutcome.NOT_ESTABLISHED)), h.wall);
            var failed = h.publisher.refresh();
            assertEquals(promoted.mappingRevision(), failed.mappingRevision());
            assertEquals(Set.of(6), failed.assistedFamilies());
            assertEquals(1, failed.probeCandidates().candidates().size());
            assertTrue(h.monitors.get(Family.IPV6).closed);
            var stopped = h.monitors.get(Family.IPV6);
            assertEquals(Map.of(6, stopped.server), h.publisher.assistedStunServers());
            assertSame(stopped, h.monitors.get(Family.IPV6), "Per-attempt configuration cannot replace background monitor");
            assertFalse(h.monitors.get(Family.IPV4).closed);
            h.advance(70000); h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            assertEquals(promoted.candidates(), h.publisher.refresh().candidates(), "Same mapping stays warm after proof expiry");
            assertTrue(h.monitors.get(Family.IPV6).closed, "Failed warm path cannot silently restart");
            assertSame(stopped, h.monitors.get(Family.IPV6));
            assertEquals(Map.of(6, stopped.server), h.publisher.assistedStunServers());
        }
    }
    @Test void wrongEndpointStageUnknownAndExpiredEvidenceCannotPromote() {
        try (var h = new Harness(List.of())) {
            h.both(); h.publisher.refresh();
            h.publisher.reportDirectChecks(List.of(
                check(h, 4, "per_join", "8.8.8.8", 43000, ConnectivityOutcome.ESTABLISHED),
                check(h, 4, "defined", "8.8.8.8", 43000, ConnectivityOutcome.ESTABLISHED),
                check(h, 4, "warm_stun", "8.8.8.8", 43001, ConnectivityOutcome.ESTABLISHED),
                check(h, 4, "warm_stun", "8.8.8.8", 43000, ConnectivityOutcome.UNKNOWN),
                check(h, 4, "warm_stun", "8.8.8.8", 43000, ConnectivityOutcome.UNAVAILABLE),
                new ConnectivityCheck(4, "warm_stun", endpoint("8.8.8.8", 43000), ConnectivityOutcome.ESTABLISHED, h.wall - 2000, h.wall - 1)), h.wall);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertTrue(h.publisher.refresh().assistedFamilies().isEmpty());
        }
    }
    @Test void directFailureSelectsAssistanceNeverStunAndOlderSuccessCannotClearIt() {
        var direct = new EndpointSelection.Candidate(endpoint("8.8.8.8", 19132), EndpointSelection.Provenance.LOCAL_INTERFACE);
        try (var h = new Harness(List.of(direct))) {
            h.publisher.refresh();
            var older = check(h, 4, "discovered", "8.8.8.8", 19132, ConnectivityOutcome.ESTABLISHED);
            h.publisher.reportDirectChecks(List.of(older), h.wall); h.advance(1000);
            h.publisher.reportDirectChecks(List.of(check(h, 4, "defined", "8.8.8.8", 19132, ConnectivityOutcome.NOT_ESTABLISHED)), h.wall);
            h.publisher.reportDirectChecks(List.of(older), h.wall);
            assertEquals(Set.of(4), h.publisher.refresh().assistedFamilies());
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            assertTrue(h.publisher.assistedStunServers().isEmpty(), "A failed public Direct path still forbids STUN");
            h.advance(70000); h.publisher.reportDirectChecks(List.of(), h.wall);
            assertEquals(Set.of(4), h.publisher.refresh().assistedFamilies());
            h.publisher.reportDirectChecks(List.of(check(h, 4, "discovered", "8.8.8.8", 19132, ConnectivityOutcome.ESTABLISHED)), h.wall);
            assertTrue(h.publisher.refresh().assistedFamilies().isEmpty());
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
        }
    }
    @Test void remapAbaAndExpiryWithdrawPromotedMappingWithoutRenewingOldCapture() {
        try (var h = new Harness(List.of())) {
            h.both(); var original = h.publisher.refresh();
            h.publisher.reportDirectChecks(List.of(check(h, 4, "warm_stun", "8.8.8.8", 43000, ConnectivityOutcome.ESTABLISHED)), h.wall);
            assertEquals(1, h.publisher.refresh().candidates().candidates().size());
            h.monitors.get(Family.IPV4).success("8.8.4.4", 43002);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            assertThrows(IllegalStateException.class, original::requireCurrent);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            assertTrue(h.publisher.refresh().candidates().candidates().isEmpty());
            var retained = h.publisher.refresh(); h.advance(270001);
            assertThrows(IllegalStateException.class, retained::requireCurrent);
        }
    }
    @Test void configuredPrivateEndpointsSuppressPublicAssistanceAndAllStun() {
        for (String address : List.of("10.0.0.8", "8.8.8.8")) {
            var endpoint = endpoint(address, 19132);
            var selection = EndpointSelection.select(endpoint("::", 19132), List.of(endpoint), List.of());
            try (var publisher = new MaintainedCandidatePublisher(selection, null, new ObservationLeaseTracker(INCARNATION))) {
                assertFalse(publisher.needsStunServers());
                publisher.configureStunServers(Map.of(Family.IPV4, endpoint("1.1.1.1", 3478)));
                publisher.refresh();
                long now = System.currentTimeMillis();
                publisher.reportDirectChecks(List.of(new ConnectivityCheck(4, "defined", endpoint, ConnectivityOutcome.NOT_ESTABLISHED, now, now + 60000)), now);
                assertEquals(address.startsWith("10.") ? Set.of() : Set.of(4), publisher.refresh().assistedFamilies());
                assertTrue(publisher.assistedStunServers().isEmpty(), "Explicit endpoints suppress per-attempt STUN too");
                assertEquals(List.of(endpoint), publisher.refresh().candidates().candidates().stream().map(NativeCandidateSnapshot.Candidate::endpoint).toList());
            }
        }
    }
}
