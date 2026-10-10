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
    static InetSocketAddress endpoint(String ip, int port) {
        try {
            return new InetSocketAddress(EndpointAddress.parse(ip), port);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    static final class Harness implements AutoCloseable {
        long wall = 1789400000000L, nanos = 10000000000L;
        final Map<Family, Monitor> monitors = new EnumMap<>(Family.class);
        final MaintainedCandidatePublisher publisher;

        Harness(List<EndpointSelection.Candidate> hints) {
            var selection = EndpointSelection.select(endpoint("::", 19132), List.of(), hints);
            var servers =
                    Map.of(
                            Family.IPV4,
                            endpoint("1.1.1.1", 3478),
                            Family.IPV6,
                            endpoint("2606:4700:4700::1111", 3478));
            var controller =
                    new EndpointConnectivityController(
                            selection,
                            servers,
                            Duration.ofMinutes(5),
                            server -> {
                                var monitor = new Monitor(server);
                                monitors.put(Family.of(server.getAddress()), monitor);
                                return monitor;
                            },
                            () -> nanos);
            publisher = new MaintainedCandidatePublisher(selection, controller, () -> nanos);
            publisher.configureStunServers(servers);
        }

        void advance(long millis) {
            wall += millis;
            nanos += millis * 1000000;
        }

        final class Monitor implements EndpointConnectivityController.Monitor {
            final InetSocketAddress server;
            InetSocketAddress mapped;
            long succeededAt, sequence, revision = 1, failed;
            boolean broken, closed;

            Monitor(InetSocketAddress server) {
                this.server = server;
            }

            void success(String address, int port) {
                var next = endpoint(address, port);
                if (mapped != null && !mapped.equals(next)) {
                    revision++;
                }
                mapped = next;
                sequence++;
                succeededAt = nanos;
            }

            @Override
            public Optional<EndpointConnectivityController.Sample> read() {
                if (broken) {
                    throw new IllegalStateException("monitor unavailable");
                }
                return Optional.of(
                        new EndpointConnectivityController.Sample(
                                server,
                                mapped,
                                failed > 0
                                        ? EndpointConnectivityController.TransactionState.FAILED
                                        : sequence == 0
                                                ? EndpointConnectivityController.TransactionState
                                                        .PENDING
                                                : EndpointConnectivityController.TransactionState
                                                        .SUCCEEDED,
                                sequence,
                                failed,
                                revision,
                                sequence == 0
                                        ? Optional.empty()
                                        : Optional.of(Duration.ofNanos(nanos - succeededAt))));
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        void both() {
            publisher.refresh();
            monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            monitors.get(Family.IPV6).success("2606:4700:4700::1001", 43001);
        }

        @Override
        public void close() {
            publisher.close();
        }
    }

    static ConnectivityCheck check(
            Harness h,
            int family,
            String method,
            String ip,
            int port,
            ConnectivityOutcome outcome) {
        return new ConnectivityCheck(
                family, method, endpoint(ip, port), outcome, h.wall, h.wall + 60000);
    }

    static EndpointSelection.Candidate local(String ip) {
        return new EndpointSelection.Candidate(
                endpoint(ip, 19132), EndpointSelection.Provenance.LOCAL_INTERFACE);
    }

    static List<InetSocketAddress> published(Harness h) {
        return h.publisher.refresh().candidates().candidates().stream()
                .map(NativeCandidateSnapshot.Candidate::endpoint)
                .toList();
    }

    @Test
    void withdrawsFailedStunFromPlayersWhileKeepingRecoveryChecks() {
        try (var h = new Harness(List.of(local("10.0.0.8"), local("fd00::8")))) {
            assertEquals(
                    List.of(endpoint("10.0.0.8", 19132), endpoint("fd00::8", 19132)), published(h));
            h.both();
            assertEquals(
                    List.of(endpoint("8.8.8.8", 43000), endpoint("2606:4700:4700::1001", 43001)),
                    published(h));
            h.publisher.reportDirectChecks(
                    List.of(
                            check(
                                    h,
                                    4,
                                    "warm_stun",
                                    "8.8.8.8",
                                    43000,
                                    ConnectivityOutcome.NOT_ESTABLISHED)),
                    h.wall);
            assertEquals(2, published(h).size());
            assertTrue(published(h).contains(endpoint("10.0.0.8", 19132)));
            assertFalse(published(h).contains(endpoint("8.8.8.8", 43000)));
            assertTrue(
                    h.publisher.refresh().probeCandidates().candidates().stream()
                            .anyMatch(c -> c.endpoint().equals(endpoint("8.8.8.8", 43000))));
            assertFalse(published(h).contains(endpoint("fd00::8", 19132)));
            assertFalse(h.monitors.get(Family.IPV4).closed);
            h.advance(1000);
            h.publisher.reportDirectChecks(
                    List.of(
                            check(
                                    h,
                                    4,
                                    "warm_stun",
                                    "8.8.8.8",
                                    43000,
                                    ConnectivityOutcome.ESTABLISHED)),
                    h.wall);
            assertEquals(2, published(h).size());
            assertTrue(h.monitors.values().stream().noneMatch(m -> m.closed));
        }
    }

    @Test
    void ignoresUnrelatedAssistedAndExpiredFeedback() {
        try (var h = new Harness(List.of(local("10.0.0.8")))) {
            h.both();
            var first = h.publisher.refresh();
            h.publisher.reportDirectChecks(
                    List.of(
                            check(
                                    h,
                                    4,
                                    "per_join",
                                    "8.8.8.8",
                                    43000,
                                    ConnectivityOutcome.NOT_ESTABLISHED),
                            check(
                                    h,
                                    4,
                                    "defined",
                                    "8.8.8.8",
                                    43000,
                                    ConnectivityOutcome.NOT_ESTABLISHED),
                            check(
                                    h,
                                    4,
                                    "warm_stun",
                                    "8.8.8.8",
                                    43001,
                                    ConnectivityOutcome.NOT_ESTABLISHED),
                            check(h, 4, "warm_stun", "8.8.8.8", 43000, ConnectivityOutcome.UNKNOWN),
                            new ConnectivityCheck(
                                    4,
                                    "warm_stun",
                                    endpoint("8.8.8.8", 43000),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    h.wall - 2000,
                                    h.wall - 1)),
                    h.wall);
            assertEquals(first.candidates(), h.publisher.refresh().candidates());
            first.requireCurrent();
        }
    }

    @Test
    void remapClearsOldFailureAndExpiryRestoresPrivateFallbackWithoutStoppingWarming() {
        try (var h = new Harness(List.of(local("10.0.0.8")))) {
            h.both();
            var original = h.publisher.refresh();
            h.publisher.reportDirectChecks(
                    List.of(
                            check(
                                    h,
                                    4,
                                    "warm_stun",
                                    "8.8.8.8",
                                    43000,
                                    ConnectivityOutcome.NOT_ESTABLISHED)),
                    h.wall);
            assertEquals(2, published(h).size());
            h.monitors.get(Family.IPV4).success("8.8.4.4", 43002);
            assertEquals(2, published(h).size());
            assertThrows(IllegalStateException.class, original::requireCurrent);
            h.monitors.get(Family.IPV4).success("8.8.8.8", 43000);
            var retained = h.publisher.refresh();
            assertTrue(retained.mappingRevision() > original.mappingRevision());
            h.advance(300001);
            assertThrows(IllegalStateException.class, retained::requireCurrent);
            assertEquals(List.of(endpoint("10.0.0.8", 19132)), published(h));
            h.both();
            assertEquals(2, published(h).size());
            assertTrue(h.monitors.values().stream().noneMatch(m -> m.closed));
        }
    }

    @Test
    void publicFamilyDoesNotNeedStunAndPrivateFallbackDoesNotChangeAssistance() {
        try (var h = new Harness(List.of(local("8.8.8.8"), local("10.0.0.8")))) {
            assertEquals(List.of(endpoint("8.8.8.8", 19132)), published(h));
            h.publisher.reportDirectChecks(
                    List.of(
                            check(
                                    h,
                                    4,
                                    "discovered",
                                    "8.8.8.8",
                                    19132,
                                    ConnectivityOutcome.NOT_ESTABLISHED)),
                    h.wall);
            assertEquals(1, published(h).size());
            assertEquals(Set.of(Family.IPV6), h.monitors.keySet());
            assertEquals(Set.of(4, 6), h.publisher.refresh().assistedFamilies());
        }
    }

    @Test
    void configuredEndpointsRemainExactAndDisableAutomaticStun() {
        var explicit = endpoint("10.0.0.8", 29132);
        var selection =
                EndpointSelection.select(
                        endpoint("::", 19132), List.of(explicit), List.of(local("8.8.8.8")));
        try (var publisher = new MaintainedCandidatePublisher(selection, null)) {
            assertFalse(publisher.needsStunServers());
            assertEquals(
                    List.of(
                            new NativeCandidateSnapshot.Candidate(
                                    explicit, NativeCandidateSnapshot.Type.HOST)),
                    publisher.refresh().candidates().candidates());
            assertTrue(publisher.refresh().assistedFamilies().isEmpty());
        }
    }

    @Test
    void assistanceCanUseLocalFallbackWithoutBackgroundWarming() {
        var selection =
                EndpointSelection.select(
                        endpoint("::", 19132), List.of(), List.of(local("10.0.0.8")));
        try (var publisher = new MaintainedCandidatePublisher(selection, null, true)) {
            var server = endpoint("1.1.1.1", 3478);
            publisher.configureStunServers(Map.of(Family.IPV4, server));
            assertEquals(1, publisher.refresh().candidates().candidates().size());
            assertEquals(Map.of(4, server), publisher.assistedStunServers());
            assertEquals(Set.of(4, 6), publisher.refresh().assistedFamilies());
        }
    }

    @Test
    void assistanceKeepsPublicAddressAndFamilyAfterFailedChecks() {
        var selection =
                EndpointSelection.select(
                        endpoint("::", 19132),
                        List.of(),
                        List.of(local("8.8.8.8"), local("10.0.0.8")));
        try (var publisher = new MaintainedCandidatePublisher(selection, null, true)) {
            var before = publisher.refresh();
            long now = System.currentTimeMillis();
            publisher.reportDirectChecks(
                    List.of(
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "per_join",
                                    endpoint("8.8.8.8", 19132),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    now,
                                    now + 60000),
                            new ConnectivityCheck(
                                    "vin1",
                                    4,
                                    "discovered",
                                    endpoint("8.8.8.8", 19132),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    now,
                                    now + 60000)),
                    now);
            var after = publisher.refresh();
            assertTrue(
                    after.candidates().candidates().stream()
                            .anyMatch(c -> c.endpoint().equals(endpoint("8.8.8.8", 19132))));
            assertEquals(before.assistedFamilies(), after.assistedFamilies());
        }
    }

    @Test
    void configuredPublicEndpointsWithdrawIndependentlyAndRecoverWithoutWarming() {
        var first = endpoint("8.8.8.8", 29132);
        var second = endpoint("8.8.4.4", 29132);
        var selection =
                EndpointSelection.select(endpoint("::", 19132), List.of(first, second), List.of());
        try (var publisher = new MaintainedCandidatePublisher(selection, null, false)) {
            long now = System.currentTimeMillis();
            publisher.refresh();
            publisher.reportDirectChecks(
                    List.of(
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "defined",
                                    first,
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    now,
                                    now + 60000)),
                    now);
            var failed = publisher.refresh();
            assertEquals(
                    List.of(
                            new NativeCandidateSnapshot.Candidate(
                                    second, NativeCandidateSnapshot.Type.HOST)),
                    failed.candidates().candidates());
            assertEquals(2, failed.probeCandidates().candidates().size());
            publisher.reportDirectChecks(List.of(), now + 60001);
            assertEquals(
                    failed.candidates(),
                    publisher.refresh().candidates(),
                    "Expiry cannot reoffer a known failed endpoint");
            publisher.reportDirectChecks(
                    List.of(
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "defined",
                                    first,
                                    ConnectivityOutcome.ESTABLISHED,
                                    now + 60002,
                                    now + 120000)),
                    now + 60002);
            assertEquals(2, publisher.refresh().candidates().candidates().size());
        }
    }

    @Test
    void regionalSuccessProtectsTheSameTargetButNotOtherTargets() {
        try (var h = new Harness(List.of(local("8.8.8.8"), local("8.8.4.4")))) {
            published(h);
            h.publisher.reportDirectChecks(
                    List.of(
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "discovered",
                                    endpoint("8.8.8.8", 19132),
                                    ConnectivityOutcome.ESTABLISHED,
                                    h.wall - 1000,
                                    h.wall + 60000),
                            new ConnectivityCheck(
                                    "vin1",
                                    4,
                                    "discovered",
                                    endpoint("8.8.8.8", 19132),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    h.wall,
                                    h.wall + 60000),
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "discovered",
                                    endpoint("8.8.4.4", 19132),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    h.wall,
                                    h.wall + 60000)),
                    h.wall);
            assertEquals(List.of(endpoint("8.8.8.8", 19132)), published(h));
            h.advance(60001);
            h.publisher.reportDirectChecks(List.of(), h.wall);
            assertEquals(
                    List.of(endpoint("8.8.8.8", 19132)),
                    published(h),
                    "Expiry alone cannot undo a successful regional result");
            h.publisher.reportDirectChecks(
                    List.of(
                            new ConnectivityCheck(
                                    "lim1",
                                    4,
                                    "discovered",
                                    endpoint("8.8.8.8", 19132),
                                    ConnectivityOutcome.NOT_ESTABLISHED,
                                    h.wall,
                                    h.wall + 60000)),
                    h.wall);
            assertTrue(published(h).isEmpty());
            assertEquals(2, h.publisher.refresh().probeCandidates().candidates().size());
        }
    }
}
