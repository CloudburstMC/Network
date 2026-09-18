package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/** The host publishes current mappings immediately and adds private addresses when a public path fails. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    public record Publication(long mappingRevision, NativeCandidateSnapshot candidates, Set<Integer> assistedFamilies,
                              Runnable current) {
        public Publication { assistedFamilies = Set.copyOf(assistedFamilies); Objects.requireNonNull(current); }
        public void requireCurrent() { current.run(); }
    }
    private record Mapping(InetSocketAddress endpoint, long epoch, long revision) { }
    private final EndpointSelection selection;
    private final EndpointConnectivityController controller;
    private final LongSupplier nanoTime;
    private final Map<Family, Mapping> mappings = new EnumMap<>(Family.class);
    private final Map<Family, ConnectivityCheck> checks = new EnumMap<>(Family.class);
    private final Map<Family, InetSocketAddress> servers = new EnumMap<>(Family.class);
    private final Set<Integer> assistedFamilies;
    private volatile long mappingRevision = 1;
    private volatile boolean closed;

    public MaintainedCandidatePublisher(EndpointSelection selection, EndpointConnectivityController controller) {
        this(selection, controller, System::nanoTime);
    }
    MaintainedCandidatePublisher(EndpointSelection selection, EndpointConnectivityController controller, LongSupplier nanoTime) {
        this.selection = Objects.requireNonNull(selection); this.nanoTime = Objects.requireNonNull(nanoTime);
        if (selection.configured() && controller != null) throw new IllegalArgumentException("Configured endpoints suppress STUN");
        this.controller = controller;
        var families = new HashSet<Integer>();
        if (selection.configured()) selection.candidates().stream()
                .filter(c -> EndpointAddress.scope(c.endpoint().getAddress()) == EndpointAddress.Scope.PUBLIC)
                .forEach(c -> families.add(number(Family.of(c.endpoint().getAddress()))));
        else selection.socketFamilies().forEach(f -> families.add(number(f)));
        assistedFamilies = Set.copyOf(families);
    }
    public boolean needsStunServers() {
        return !selection.configured() && selection.socketFamilies().stream().anyMatch(f -> selection.publicCandidates(f).isEmpty());
    }
    public synchronized void configureStunServers(Map<Family, InetSocketAddress> numericServers) {
        requireOpen();
        if (selection.configured()) return;
        numericServers.forEach((family, server) -> {
            if (selection.socketFamilies().contains(family) && selection.publicCandidates(family).isEmpty()
                    && !server.equals(servers.get(family))) {
                if (controller != null) controller.replaceStunServer(family, server);
                servers.put(family, server);
            }
        });
    }
    public synchronized Map<Integer, InetSocketAddress> assistedStunServers() {
        requireOpen();
        var selected = new HashMap<Integer, InetSocketAddress>();
        servers.forEach((family, server) -> { if (assistedFamilies.contains(number(family))) selected.put(number(family), server); });
        return Map.copyOf(selected);
    }
    public synchronized Publication refresh() {
        requireOpen();
        var candidates = new ArrayList<NativeCandidateSnapshot.Candidate>();
        var fallback = new ArrayList<NativeCandidateSnapshot.Candidate>();
        var observations = new ArrayList<EndpointConnectivityController.Observation>();
        var nextMappings = new EnumMap<Family, Mapping>(Family.class);
        var snapshot = controller == null ? null : controller.snapshot();
        for (Family family : Family.values()) {
            var direct = selection.configured() ? selection.candidates(family) : selection.publicCandidates(family);
            direct.forEach(c -> candidates.add(new NativeCandidateSnapshot.Candidate(c.endpoint(), NativeCandidateSnapshot.Type.HOST)));
            var lane = snapshot == null ? null : snapshot.families().get(family);
            if (lane != null && lane.freshStunEndpoint().isPresent()) {
                var observation = lane.observation().orElseThrow();
                var endpoint = lane.freshStunEndpoint().orElseThrow();
                nextMappings.put(family, new Mapping(endpoint, observation.monitorEpoch(), observation.mappingRevision()));
                observations.add(observation);
                candidates.add(0, new NativeCandidateSnapshot.Candidate(endpoint, NativeCandidateSnapshot.Type.SRFLX));
            }
            if (!Objects.equals(mappings.get(family), nextMappings.get(family))) checks.remove(family);
            var check = checks.get(family);
            boolean failed = check != null && check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED;
            if (!selection.configured() && (direct.isEmpty() && !nextMappings.containsKey(family) || failed))
                selection.candidates(family).stream()
                        .filter(c -> EndpointAddress.scope(c.endpoint().getAddress()) == EndpointAddress.Scope.PRIVATE)
                        .forEach(c -> fallback.add(new NativeCandidateSnapshot.Candidate(c.endpoint(), NativeCandidateSnapshot.Type.HOST)));
        }
        if (!nextMappings.equals(mappings)) mappingRevision = Math.incrementExact(mappingRevision);
        mappings.clear(); mappings.putAll(nextMappings);
        candidates.addAll(fallback); // Keep both public families ahead of private fallback within the wire limit.
        long capturedRevision = mappingRevision;
        return new Publication(mappingRevision, new NativeCandidateSnapshot(candidates.stream().limit(32).toList()), assistedFamilies, () -> {
            requireOpen();
            if (mappingRevision != capturedRevision || observations.stream().anyMatch(o -> !o.freshAt(nanoTime.getAsLong())))
                throw new IllegalStateException("STUN observation changed or expired");
        });
    }
    /** Feedback only adds/removes local fallback candidates; it never stops STUN or controls the listener. */
    public synchronized void reportDirectChecks(List<ConnectivityCheck> reports, long nowMillis) {
        requireOpen();
        for (var check : reports) {
            if (check.target() == null || check.checkedAt() > nowMillis || check.expiresAt() <= nowMillis
                    || check.method().equals("per_join") || check.outcome() == ConnectivityOutcome.UNKNOWN
                    || check.outcome() == ConnectivityOutcome.UNAVAILABLE) continue;
            Family family = check.family() == 4 ? Family.IPV4 : Family.IPV6;
            var mapping = mappings.get(family);
            boolean matches = check.method().equals("warm_stun") ? mapping != null && mapping.endpoint().equals(check.target())
                    : selection.publicCandidates(family).stream().anyMatch(c -> c.endpoint().equals(check.target()));
            var previous = checks.get(family);
            if (matches && (previous == null || check.checkedAt() > previous.checkedAt()
                    || check.checkedAt() == previous.checkedAt() && check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED))
                checks.put(family, check);
        }
    }
    private static int number(Family family) { return family == Family.IPV4 ? 4 : 6; }
    private void requireOpen() { if (closed) throw new IllegalStateException("Candidate publisher closed"); }
    @Override public synchronized void close() { closed = true; if (controller != null) controller.close(); }
}
