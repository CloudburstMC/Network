package org.cloudburstmc.netty.signaling.provider.connectivity;

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.util.nethernet.StunObserver;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/** Host-owned publication policy. Failed public paths remain probeable and recover without restarting. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(MaintainedCandidatePublisher.class);

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
    private final Function<InetSocketAddress, Optional<InetAddress>> routeSource;
    /** The private listener address each family reaches its STUN server from, for players on this network. */
    private final Map<Family, InetSocketAddress> lan = new EnumMap<>(Family.class);
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
        this(selection, controller, assistedJoins, nanoTime, MaintainedCandidatePublisher::routeSource);
    }

    MaintainedCandidatePublisher(
            EndpointSelection selection,
            EndpointConnectivityController controller,
            boolean assistedJoins,
            LongSupplier nanoTime,
            Function<InetSocketAddress, Optional<InetAddress>> routeSource) {
        this.assistedJoins = assistedJoins;
        this.routeSource = Objects.requireNonNull(routeSource);
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
                        updateLan(family, server);
                    }
                });
    }

    private void updateLan(Family family, InetSocketAddress server) {
        var address =
                routeSource.apply(server)
                        .filter(a -> EndpointAddress.scope(a) == EndpointAddress.Scope.PRIVATE)
                        .map(a -> new InetSocketAddress(a, selection.bind().getPort()))
                        .filter(e -> selection.candidates(family).stream().anyMatch(c -> c.endpoint().equals(e)))
                        .orElse(null);
        if (!Objects.equals(lan.get(family), address)) {
            if (address == null) {
                lan.remove(family);
            } else {
                lan.put(family, address);
                log.info("Offering {} to players on this host's network as well",
                        NetUtil.toSocketAddressString(address));
            }
        }
    }

    /** The local address the system sends to the target from. Connecting a UDP socket sends nothing. */
    static Optional<InetAddress> routeSource(InetSocketAddress target) {
        try (var socket = new DatagramSocket()) {
            socket.connect(target);
            InetAddress local = socket.getLocalAddress();
            return local.isAnyLocalAddress()
                    ? Optional.empty()
                    : Optional.of(InetAddress.getByAddress(local.getAddress()));
        } catch (IOException | RuntimeException unroutable) {
            return Optional.empty();
        }
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
        var local = new ArrayList<NativeCandidateSnapshot.Candidate>();
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
                var mapping =
                        new Mapping(
                                endpoint,
                                observation.monitorEpoch(),
                                observation.mappingRevision());
                nextMappings.put(family, mapping);
                observations.add(observation);
                // The ICE port too, for a router that moved the port on the way out; provider
                // checks withdraw it on their own where nothing forwards it
                for (var announced : StunObserver.withIcePort(List.of(endpoint), icePort())) {
                    probes.add(
                            0,
                            new NativeCandidateSnapshot.Candidate(
                                    announced, NativeCandidateSnapshot.Type.SRFLX));
                }
            }
            var current = nextMappings.get(family);
            var twin = current == null ? null : twin(current);
            if (!Objects.equals(mappings.get(family), nextMappings.get(family))) {
                checks.entrySet()
                        .removeIf(
                                e ->
                                        e.getValue().family() == number(family)
                                                && e.getKey().method().equals("warm_stun"));
            }
            // A failing twin only withdraws itself, it says nothing about the family
            boolean failed =
                    probes.stream()
                            .filter(c -> Family.of(c.endpoint().getAddress()) == family)
                            .filter(c -> !c.endpoint().equals(twin))
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
                                        local.add(
                                                new NativeCandidateSnapshot.Candidate(
                                                        c.endpoint(),
                                                        NativeCandidateSnapshot.Type.HOST)));
            } else if (lan.containsKey(family)) {
                // Players on this network reach it without looping through the router
                local.add(new NativeCandidateSnapshot.Candidate(lan.get(family), NativeCandidateSnapshot.Type.HOST));
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
        candidates.addAll(local);
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
            boolean matches;
            if (check.method().equals("warm_stun")) {
                matches =
                        mapping != null
                                && (mapping.endpoint().equals(check.target())
                                        || check.target().equals(twin(mapping)));
            } else {
                matches =
                        selection.publicCandidates(family).stream()
                                .anyMatch(c -> c.endpoint().equals(check.target()));
            }
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

    /** The mapping's address on the ICE port, or null when the mapping is already on it. */
    private InetSocketAddress twin(Mapping mapping) {
        if (mapping.endpoint().getPort() == icePort()) {
            return null;
        }
        return new InetSocketAddress(mapping.endpoint().getAddress(), icePort());
    }

    private int icePort() {
        return selection.bind().getPort();
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
