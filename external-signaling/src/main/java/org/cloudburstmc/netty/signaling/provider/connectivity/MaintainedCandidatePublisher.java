package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/** Host-owned publication policy. Failed public paths remain probeable and recover without restarting. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    public record Publication(
            long mappingRevision,
            NativeCandidateSnapshot candidates,
            NativeCandidateSnapshot probeCandidates,
            Set<Integer> assistedFamilies,
            Runnable current) {
        public Publication {
            assistedFamilies = Set.copyOf(assistedFamilies);
            Objects.requireNonNull(current);
        }

        public void requireCurrent() {
            current.run();
        }
    }

    private record Mapping(InetSocketAddress endpoint, long epoch, long revision) {}

    private final EndpointSelection selection;
    private final EndpointConnectivityController controller;
    private final LongSupplier nanoTime;
    private final Map<Family, Mapping> mappings = new EnumMap<>(Family.class);

    private record CheckKey(String region, String method, InetSocketAddress target) {}

    private final Map<CheckKey, ConnectivityCheck> checks = new HashMap<>();
    private final boolean assistedJoins;
    private final Map<Family, InetSocketAddress> servers = new EnumMap<>(Family.class);
    private final Set<Integer> assistedFamilies;
    private volatile long mappingRevision = 1;
    private volatile boolean closed;

    public MaintainedCandidatePublisher(
            EndpointSelection selection, EndpointConnectivityController controller) {
        this(selection, controller, false);
    }

    public MaintainedCandidatePublisher(
            EndpointSelection selection,
            EndpointConnectivityController controller,
            boolean assistedJoins) {
        this(selection, controller, assistedJoins, System::nanoTime);
    }

    MaintainedCandidatePublisher(
            EndpointSelection selection,
            EndpointConnectivityController controller,
            LongSupplier nanoTime) {
        this(selection, controller, false, nanoTime);
    }

    MaintainedCandidatePublisher(
            EndpointSelection selection,
            EndpointConnectivityController controller,
            boolean assistedJoins,
            LongSupplier nanoTime) {
        this.assistedJoins = assistedJoins;
        this.selection = Objects.requireNonNull(selection);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (selection.configured() && controller != null) {
            throw new IllegalArgumentException("Configured endpoints suppress STUN");
        }
        this.controller = controller;
        var families = new HashSet<Integer>();
        if (selection.configured()) {
            selection.candidates().stream()
                    .filter(
                            c ->
                                    EndpointAddress.scope(c.endpoint().getAddress())
                                            == EndpointAddress.Scope.PUBLIC)
                    .forEach(c -> families.add(number(Family.of(c.endpoint().getAddress()))));
        } else {
            selection.socketFamilies().forEach(f -> families.add(number(f)));
        }
        assistedFamilies = Set.copyOf(families);
    }

    public boolean needsStunServers() {
        return !selection.configured()
                && (controller != null || assistedJoins)
                && selection.socketFamilies().stream()
                        .anyMatch(f -> selection.publicCandidates(f).isEmpty());
    }

    public synchronized void configureStunServers(Map<Family, InetSocketAddress> numericServers) {
        requireOpen();
        if (selection.configured()) {
            return;
        }
        numericServers.forEach(
                (family, server) -> {
                    if (selection.socketFamilies().contains(family)
                            && selection.publicCandidates(family).isEmpty()
                            && !server.equals(servers.get(family))) {
                        if (controller != null) {
                            controller.replaceStunServer(family, server);
                        }
                        servers.put(family, server);
                    }
                });
    }

    public synchronized Map<Integer, InetSocketAddress> assistedStunServers() {
        requireOpen();
        var selected = new HashMap<Integer, InetSocketAddress>();
        servers.forEach(
                (family, server) -> {
                    if (assistedFamilies.contains(number(family))) {
                        selected.put(number(family), server);
                    }
                });
        return Map.copyOf(selected);
    }

    public synchronized Publication refresh() {
        requireOpen();
        var candidates = new ArrayList<NativeCandidateSnapshot.Candidate>();
        var fallback = new ArrayList<NativeCandidateSnapshot.Candidate>();
        var probes = new ArrayList<NativeCandidateSnapshot.Candidate>();
        var observations = new ArrayList<EndpointConnectivityController.Observation>();
        var nextMappings = new EnumMap<Family, Mapping>(Family.class);
        var snapshot = controller == null ? null : controller.snapshot();
        for (Family family : Family.values()) {
            var direct =
                    selection.configured()
                            ? selection.candidates(family)
                            : selection.publicCandidates(family);
            direct.forEach(
                    c ->
                            probes.add(
                                    new NativeCandidateSnapshot.Candidate(
                                            c.endpoint(), NativeCandidateSnapshot.Type.HOST)));
            var lane = snapshot == null ? null : snapshot.families().get(family);
            if (lane != null && lane.freshStunEndpoint().isPresent()) {
                var observation = lane.observation().orElseThrow();
                var endpoint = lane.freshStunEndpoint().orElseThrow();
                nextMappings.put(
                        family,
                        new Mapping(
                                endpoint,
                                observation.monitorEpoch(),
                                observation.mappingRevision()));
                observations.add(observation);
                probes.add(
                        0,
                        new NativeCandidateSnapshot.Candidate(
                                endpoint, NativeCandidateSnapshot.Type.SRFLX));
            }
            if (!Objects.equals(mappings.get(family), nextMappings.get(family))) {
                checks.entrySet()
                        .removeIf(
                                e ->
                                        e.getValue().family() == number(family)
                                                && e.getKey().method().equals("warm_stun"));
            }
            boolean failed =
                    probes.stream()
                            .filter(c -> Family.of(c.endpoint().getAddress()) == family)
                            .anyMatch(c -> failed(c.endpoint()));
            if (!selection.configured()
                    && (direct.isEmpty() && !nextMappings.containsKey(family) || failed)) {
                selection.candidates(family).stream()
                        .filter(
                                c ->
                                        EndpointAddress.scope(c.endpoint().getAddress())
                                                == EndpointAddress.Scope.PRIVATE)
                        .forEach(
                                c ->
                                        fallback.add(
                                                new NativeCandidateSnapshot.Candidate(
                                                        c.endpoint(),
                                                        NativeCandidateSnapshot.Type.HOST)));
            }
        }
        if (!nextMappings.equals(mappings)) {
            mappingRevision = Math.incrementExact(mappingRevision);
        }
        mappings.clear();
        mappings.putAll(nextMappings);
        // Test destinations survive withdrawal from player offers. Private addresses remain useful
        // on LAN/VPN.
        probes.stream()
                .filter(c -> assistedJoins || !failed(c.endpoint()))
                .forEach(candidates::add);
        candidates.addAll(fallback);
        var probeCandidates = new NativeCandidateSnapshot(probes.stream().limit(32).toList());
        checks.entrySet()
                .removeIf(
                        e ->
                                probes.stream()
                                        .noneMatch(c -> c.endpoint().equals(e.getKey().target())));
        long capturedRevision = mappingRevision;
        return new Publication(
                mappingRevision,
                new NativeCandidateSnapshot(candidates.stream().limit(32).toList()),
                probeCandidates,
                assistedFamilies,
                () -> {
                    requireOpen();
                    if (mappingRevision != capturedRevision
                            || observations.stream()
                                    .anyMatch(o -> !o.freshAt(nanoTime.getAsLong()))) {
                        throw new IllegalStateException("STUN observation changed or expired");
                    }
                });
    }

    /** Only current, public, directly tested endpoints affect offers. Assistance is never disabled by a probe. */
    public synchronized void reportDirectChecks(List<ConnectivityCheck> reports, long nowMillis) {
        requireOpen();
        for (var check : reports) {
            if (check.target() == null
                    || check.checkedAt() > nowMillis
                    || check.expiresAt() <= nowMillis
                    || check.method().equals("per_join")
                    || check.outcome() == ConnectivityOutcome.UNKNOWN
                    || check.outcome() == ConnectivityOutcome.UNAVAILABLE
                    || EndpointAddress.scope(check.target().getAddress())
                            != EndpointAddress.Scope.PUBLIC) {
                continue;
            }
            Family family = check.family() == 4 ? Family.IPV4 : Family.IPV6;
            var mapping = mappings.get(family);
            boolean matches =
                    check.method().equals("warm_stun")
                            ? mapping != null && mapping.endpoint().equals(check.target())
                            : selection.publicCandidates(family).stream()
                                    .anyMatch(c -> c.endpoint().equals(check.target()));
            var key = new CheckKey(check.region(), check.method(), check.target());
            var previous = checks.get(key);
            if (matches
                    && (previous == null
                            || check.checkedAt() > previous.checkedAt()
                            || check.checkedAt() == previous.checkedAt()
                                    && check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED)) {
                checks.put(key, check);
            }
        }
    }

    private boolean failed(InetSocketAddress endpoint) {
        var matching = checks.values().stream().filter(c -> endpoint.equals(c.target())).toList();
        // A successful region proves a usable path even when another region fails. The latest
        // terminal decision
        // per region survives feedback expiry; only another result or endpoint replacement changes
        // it.
        return matching.stream().anyMatch(c -> c.outcome() == ConnectivityOutcome.NOT_ESTABLISHED)
                && matching.stream().noneMatch(c -> c.outcome() == ConnectivityOutcome.ESTABLISHED);
    }

    private static int number(Family family) {
        return family == Family.IPV4 ? 4 : 6;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Candidate publisher closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (controller != null) {
            controller.close();
        }
    }
}
