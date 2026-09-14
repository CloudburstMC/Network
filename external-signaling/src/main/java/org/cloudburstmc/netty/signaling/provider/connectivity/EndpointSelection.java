package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.io.IOException;
import java.net.*;
import java.util.*;

/** Opt-in endpoint policy. Address provenance never asserts inbound reachability. */
public final class EndpointSelection {
    public enum Family {
        IPV4, IPV6;

        public static Family of(InetAddress address) {
            return address instanceof Inet4Address ? IPV4 : IPV6;
        }
    }

    public enum Provenance { CONFIGURED, LOCAL_BIND, SERVER_PROPERTIES, LOCAL_INTERFACE, NATIVE_HOST }

    public record Candidate(InetSocketAddress endpoint, Provenance provenance) {
        public Candidate {
            requireEndpoint(endpoint);
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    private final InetSocketAddress bind;
    private final boolean configured;
    private final List<Candidate> candidates;
    private final Set<Family> socketFamilies;

    private EndpointSelection(InetSocketAddress bind, boolean configured, List<Candidate> candidates,
                              Set<Family> socketFamilies) {
        this.bind = bind;
        this.configured = configured;
        this.candidates = List.copyOf(candidates);
        this.socketFamilies = Set.copyOf(socketFamilies);
    }

    /**
     * Completes local discovery without DNS or STUN. Hints must describe host addresses on the
     * gameplay port, not an independently gathered ephemeral socket or the Java backend port.
     * Configured forwarding endpoints may translate address families and override every hint.
     */
    public static EndpointSelection select(InetSocketAddress bind, List<InetSocketAddress> configured,
                                           List<Candidate> localHints) {
        requireEndpoint(bind);
        if (bind.getAddress().isMulticastAddress()) throw new IllegalArgumentException("Unicast or wildcard bind required");
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(localHints, "localHints");
        Set<Family> families = bind.getAddress() instanceof Inet6Address && bind.getAddress().isAnyLocalAddress()
                ? EnumSet.allOf(Family.class) : EnumSet.of(Family.of(bind.getAddress()));
        Map<InetSocketAddress, Candidate> result = new LinkedHashMap<>();
        if (!configured.isEmpty()) {
            if (configured.size() > 32) throw new IllegalArgumentException("At most 32 configured endpoints");
            for (InetSocketAddress endpoint : configured) {
                requireEndpoint(endpoint);
                if (!EndpointAddress.advertisable(endpoint.getAddress(), false)) {
                    throw new IllegalArgumentException("Configured endpoint must be a usable unicast address");
                }
                result.putIfAbsent(endpoint, new Candidate(endpoint, Provenance.CONFIGURED));
            }
        } else {
            if (localHints.size() > 256) throw new IllegalArgumentException("At most 256 local discovery hints");
            addLocal(result, new Candidate(bind, Provenance.LOCAL_BIND), bind, families);
            // Fixed order makes duplicate provenance stable when interface enumeration order changes.
            localHints.stream().sorted(Comparator.comparing(Candidate::provenance)).forEach(hint -> {
                if (hint.provenance() == Provenance.CONFIGURED || hint.provenance() == Provenance.LOCAL_BIND) {
                    throw new IllegalArgumentException("Local hints cannot claim configured or bound provenance");
                }
                addLocal(result, hint, bind, families);
            });
        }
        if (result.size() > 32) throw new IllegalArgumentException("At most 32 selected endpoints");
        List<Candidate> sorted = result.values().stream().sorted(Comparator
                .comparing((Candidate c) -> Family.of(c.endpoint().getAddress()))
                .thenComparing(c -> c.endpoint().getAddress().getHostAddress())
                .thenComparingInt(c -> c.endpoint().getPort())).toList();
        return new EndpointSelection(bind, !configured.isEmpty(), sorted, families);
    }

    /** Enumerates local interfaces only; configured endpoints suppress even interface enumeration. */
    public static EndpointSelection discover(InetSocketAddress bind, List<InetSocketAddress> configured,
                                             List<Candidate> hostHints) throws IOException {
        requireEndpoint(bind);
        if (!configured.isEmpty() || !bind.getAddress().isAnyLocalAddress()) {
            return select(bind, configured, hostHints);
        }
        List<Candidate> hints = new ArrayList<>(hostHints);
        var networks = NetworkInterface.getNetworkInterfaces();
        if (networks != null) for (NetworkInterface network : Collections.list(networks)) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (hints.size() == 256) throw new IOException("Too many local discovery hints");
                hints.add(new Candidate(new InetSocketAddress(InetAddress.getByAddress(address.getAddress()), bind.getPort()),
                        Provenance.LOCAL_INTERFACE));
            }
        }
        return select(bind, configured, hints);
    }

    private static void addLocal(Map<InetSocketAddress, Candidate> result, Candidate candidate,
                                 InetSocketAddress bind, Set<Family> families) {
        if (candidate.endpoint().getPort() != bind.getPort()) {
            throw new IllegalArgumentException("Local candidate must use the fixed gameplay mux port");
        }
        if (EndpointAddress.scope(candidate.endpoint().getAddress()) == EndpointAddress.Scope.PUBLIC
                && families.contains(Family.of(candidate.endpoint().getAddress()))) {
            // A concrete socket only owns that interface; other interfaces are not listener candidates.
            if (!bind.getAddress().isAnyLocalAddress() && candidate.provenance() == Provenance.LOCAL_INTERFACE
                    && !candidate.endpoint().getAddress().equals(bind.getAddress())) return;
            result.putIfAbsent(candidate.endpoint(), candidate);
        }
    }

    static void requireEndpoint(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() < 1) {
            throw new IllegalArgumentException("Numeric address and fixed UDP port required");
        }
    }

    public InetSocketAddress bind() { return bind; }
    public boolean configured() { return configured; }
    public Set<Family> socketFamilies() { return socketFamilies; }
    public List<Candidate> candidates() { return candidates; }
    public List<Candidate> candidates(Family family) {
        return candidates.stream().filter(c -> Family.of(c.endpoint().getAddress()) == family).toList();
    }
}
