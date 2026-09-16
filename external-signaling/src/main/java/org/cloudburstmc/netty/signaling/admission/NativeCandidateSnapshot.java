package org.cloudburstmc.netty.signaling.admission;

import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Immutable endpoint material. Observation ages and counters do not belong in this semantic snapshot. */
public final class NativeCandidateSnapshot {
    public enum Type {
        HOST("host"), SRFLX("srflx");
        private final String wire;
        Type(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }
    public record Candidate(InetSocketAddress endpoint, Type type) {
        public Candidate {
            Objects.requireNonNull(type, "type");
            if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() < 1
                    || EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.UNUSABLE)
                throw new IllegalArgumentException("Concrete numeric UDP endpoint required");
            try { endpoint = new InetSocketAddress(InetAddress.getByAddress(endpoint.getAddress().getAddress()), endpoint.getPort()); }
            catch (UnknownHostException impossible) { throw new IllegalArgumentException("Invalid IP address", impossible); }
        }
        public EndpointSelection.Family family() { return EndpointSelection.Family.of(endpoint.getAddress()); }
    }

    private final List<Candidate> candidates;
    private final String materialRevision;

    public NativeCandidateSnapshot(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.size() > 32) throw new IllegalArgumentException("At most 32 endpoint candidates");
        this.candidates = candidates.stream().map(Objects::requireNonNull).distinct().sorted(Comparator
                .comparing(Candidate::family).thenComparing(c -> c.endpoint().getAddress().getHostAddress())
                .thenComparingInt(c -> c.endpoint().getPort()).thenComparing(Candidate::type)).toList();
        StringBuilder material = new StringBuilder("native-candidates-v1\n");
        for (Candidate candidate : this.candidates) material.append(candidate.family()).append(' ')
                .append(candidate.endpoint().getAddress().getHostAddress()).append(' ').append(candidate.endpoint().getPort())
                .append(' ').append(candidate.type().wire()).append('\n');
        try { materialRevision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.toString().getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static NativeCandidateSnapshot hosts(List<InetSocketAddress> endpoints) {
        Objects.requireNonNull(endpoints, "endpoints");
        if (endpoints.size() > 32) throw new IllegalArgumentException("At most 32 endpoint candidates");
        return new NativeCandidateSnapshot(endpoints.stream().map(endpoint -> new Candidate(endpoint, Type.HOST)).toList());
    }
    public List<Candidate> candidates() { return candidates; }
    public String materialRevision() { return materialRevision; }
    @Override public boolean equals(Object value) { return value instanceof NativeCandidateSnapshot other && candidates.equals(other.candidates); }
    @Override public int hashCode() { return candidates.hashCode(); }
}
