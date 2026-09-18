package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/** Host-owned family policy. Pending same-mux mappings are diagnostic targets, never player candidates. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    public record Publication(long mappingRevision, NativeCandidateSnapshot candidates, NativeCandidateSnapshot probeCandidates,
                              Map<NativeCandidateSnapshot.Candidate, Long> expiries, Set<Integer> assistedFamilies,
                              Runnable current) {
        public Publication { expiries = Map.copyOf(expiries); assistedFamilies = Set.copyOf(assistedFamilies); Objects.requireNonNull(current); }
        public void requireCurrent() { current.run(); }
    }
    private record Mapping(NativeCandidateSnapshot.Candidate candidate, long epoch, long revision) { }
    private final EndpointSelection selection;
    private final EndpointConnectivityController controller;
    private final ObservationLeaseTracker tracker;
    private final Map<Family, Mapping> mappings = new EnumMap<>(Family.class);
    private final Set<Family> promoted = EnumSet.noneOf(Family.class);
    private final Set<Integer> assistedFamilies;
    private final Map<Family, Long> failedAt = new EnumMap<>(Family.class), establishedAt = new EnumMap<>(Family.class);
    private final Map<Family, InetSocketAddress> servers = new EnumMap<>(Family.class);
    private Publication current;
    private long mappingRevision;
    private volatile boolean closed;

    public MaintainedCandidatePublisher(EndpointSelection selection, EndpointConnectivityController controller,
                                       ObservationLeaseTracker tracker) {
        this.selection = Objects.requireNonNull(selection); this.tracker = Objects.requireNonNull(tracker);
        if (selection.configured() && controller != null)
            throw new IllegalArgumentException("Configured endpoints suppress the connectivity controller");
        this.controller = controller;
        // These are transport capabilities, not a connectivity verdict. ProviderClient applies
        // the host's explicit assisted-joins setting. Configured addresses never open other families.
        var families = new HashSet<Integer>();
        if (selection.configured()) {
            selection.candidates().stream()
                    .filter(candidate -> EndpointAddress.scope(candidate.endpoint().getAddress()) == EndpointAddress.Scope.PUBLIC)
                    .forEach(candidate -> families.add(number(Family.of(candidate.endpoint().getAddress()))));
        } else selection.socketFamilies().forEach(family -> families.add(number(family)));
        assistedFamilies = Set.copyOf(families);
    }

    public boolean needsStunServers() {
        return !selection.configured() && selection.socketFamilies().stream().anyMatch(f -> selection.candidates(f).isEmpty());
    }
    public synchronized void configureStunServers(Map<Family, InetSocketAddress> numericServers) {
        requireOpen();
        if (selection.configured()) return;
        numericServers.forEach((family, server) -> {
            if (selection.socketFamilies().contains(family) && selection.candidates(family).isEmpty()
                    && !server.equals(servers.get(family))) {
                if (controller != null) controller.replaceStunServer(family, server);
                servers.put(family, server);
            }
        });
    }

    /** Trusted discovery endpoints for a bounded attempt; reading them never restarts background warming. */
    public synchronized Map<Integer, InetSocketAddress> assistedStunServers() {
        requireOpen();
        var selected = new HashMap<Integer, InetSocketAddress>();
        servers.forEach((family, server) -> {
            if (assistedFamilies.contains(number(family))) selected.put(number(family), server);
        });
        return Map.copyOf(selected);
    }

    /** Called on the native event loop independently of provider requests. */
    public synchronized Publication refresh() {
        requireOpen();
        var players = new ArrayList<NativeCandidateSnapshot.Candidate>();
        selection.candidates().forEach(candidate -> players.add(new NativeCandidateSnapshot.Candidate(candidate.endpoint(), NativeCandidateSnapshot.Type.HOST)));
        var probes = new ArrayList<>(players);
        var expiries = new HashMap<NativeCandidateSnapshot.Candidate, Long>();
        ObservationLeaseTracker.Capture captured = null;
        if (controller != null) {
            if (!tracker.clockValid()) tracker.recoverClockForFutureObservations();
            var sample = controller.snapshot();
            sample.families().forEach((family, lane) -> {
                if (lane.directCandidates().isEmpty() && switch (lane.state()) {
                    case STUN_FAILED, STUN_INELIGIBLE, STUN_STALE, MONITOR_FAILED, STUN_STOPPED -> true;
                    default -> false;
                }) {
                    controller.stopStun(family);
                }
            });
            try { captured = tracker.capture(controller.snapshot()); }
            catch (RuntimeException unavailable) { /* Withdraw observations if their ownership clock is unavailable. */ }
        }
        var owned = captured;
        var nextMappings = new EnumMap<Family, Mapping>(Family.class);
        if (owned != null) for (var observation : owned.observations()) {
            var family = observation.family().equals("ipv4") ? Family.IPV4 : Family.IPV6;
            if (!selection.candidates(family).isEmpty()) throw new IllegalStateException("Public direct family cannot use STUN");
            if (probes.size() == 32) break;
            try {
                var endpoint = new InetSocketAddress(InetAddress.getByAddress(HexFormat.of().parseHex(observation.addressHex())), observation.port());
                var candidate = new NativeCandidateSnapshot.Candidate(endpoint, NativeCandidateSnapshot.Type.SRFLX);
                var mapping = new Mapping(candidate, observation.monitorEpoch(), observation.mappingRevision());
                nextMappings.put(family, mapping);
                if (!mapping.equals(mappings.get(family))) {
                    mappingRevision = Math.incrementExact(mappingRevision);
                    promoted.remove(family); failedAt.remove(family); establishedAt.remove(family);
                }
                probes.add(candidate); expiries.put(candidate, observation.expiresAt());
                if (promoted.contains(family)) players.add(candidate);
            } catch (UnknownHostException impossible) { throw new IllegalStateException(impossible); }
        }
        for (Family family : Family.values()) if (!nextMappings.containsKey(family)) promoted.remove(family);
        mappings.clear(); mappings.putAll(nextMappings);
        current = new Publication(mappingRevision, new NativeCandidateSnapshot(players), new NativeCandidateSnapshot(probes), expiries, assistedFamilies, () -> {
            requireOpen(); if (owned != null) owned.requireCurrent();
        });
        return current;
    }

    /** Warm mappings alone depend on probe feedback. Revision and observation lifetime are fenced by the transport. */
    public synchronized void reportDirectChecks(List<ConnectivityCheck> checks, long nowMillis) {
        requireOpen();
        if (current == null) return;
        for (Family family : Family.values()) {
            var mapping = mappings.get(family);
            if (mapping == null) continue;
            var fresh = checks.stream().filter(check -> check.family() == number(family) && check.target() != null
                    && check.checkedAt() <= nowMillis && check.expiresAt() > nowMillis
                    && "warm_stun".equals(check.method()) && mapping.candidate().endpoint().equals(check.target())
                    && check.expiresAt() <= current.expiries().get(mapping.candidate())).toList();
            long positive = fresh.stream().filter(c -> c.outcome() == ConnectivityOutcome.ESTABLISHED).mapToLong(ConnectivityCheck::checkedAt).max().orElse(-1);
            long negative = fresh.stream().filter(c -> c.outcome() == ConnectivityOutcome.NOT_ESTABLISHED).mapToLong(ConnectivityCheck::checkedAt).max().orElse(-1);
            // Failure wins equal timestamps. Older successes cannot undo a newer failed selection.
            if (negative >= positive && negative >= 0 && negative >= establishedAt.getOrDefault(family, -1L)
                    && negative >= failedAt.getOrDefault(family, -1L)) {
                failedAt.put(family, negative); promoted.remove(family);
                controller.stopStun(family);
            } else if (positive > failedAt.getOrDefault(family, -1L) && positive >= establishedAt.getOrDefault(family, -1L)) {
                establishedAt.put(family, positive); promoted.add(family);
            }
        }
    }

    private static int number(Family family) { return family == Family.IPV4 ? 4 : 6; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Candidate publisher closed"); }
    @Override public synchronized void close() { closed = true; tracker.close(); if (controller != null) controller.close(); }
}
