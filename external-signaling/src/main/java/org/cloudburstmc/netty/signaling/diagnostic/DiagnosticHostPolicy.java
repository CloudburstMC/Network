/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.util.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Trusted local host configuration. Incoming permits cannot select their own allowed target or revision. */
public record DiagnosticHostPolicy(Context context, List<Key> keys, Set<Endpoint> endpoints, long expiresAt,
                                   Map<Endpoint, Long> endpointExpiries) {
    public DiagnosticHostPolicy(Context context, List<Key> keys, Set<Endpoint> endpoints, long expiresAt) {
        this(context, keys, endpoints, expiresAt, deadlines(endpoints, expiresAt));
    }
    private static Map<Endpoint, Long> deadlines(Set<Endpoint> endpoints, long expiresAt) {
        var result = new HashMap<Endpoint, Long>(); endpoints.forEach(endpoint -> result.put(endpoint, expiresAt)); return result;
    }
    public record Endpoint(int family, String addressHex, int port, long candidateRevision) {
        public Endpoint {
            if ((family != 4 && family != 6) || port < 0 || port > 65535 || (port == 0 && !"00".repeat(16).equals(addressHex)) || candidateRevision < 1 || candidateRevision > SAFE) throw invalid();
            byte[] bytes = unhex(addressHex, 16);
            if (family == 4 ? !Arrays.equals(Arrays.copyOf(bytes, 12), new byte[12]) : addressHex.startsWith("00000000000000000000ffff")) throw invalid();
        }
        public static Endpoint assisted(int family, long candidateRevision) { return new Endpoint(family, "00".repeat(16), 0, candidateRevision); }
        public boolean assisted() { return port == 0; }
        public static Endpoint from(Claims claims) { return new Endpoint(claims.family(), claims.targetAddressHex(), claims.targetPort(), claims.candidateRevision()); }
    }
    public DiagnosticHostPolicy {
        Objects.requireNonNull(context);
        if (keys.size() > 8 || endpoints.size() > 34 || expiresAt < 1 || expiresAt > SAFE) throw invalid();
        var assistedFamilies = new HashSet<Integer>();
        int concrete = 0;
        for (Endpoint endpoint : endpoints) {
            if (endpoint.assisted()) { if (!assistedFamilies.add(endpoint.family())) throw invalid(); }
            else if (++concrete > 32) throw invalid();
        }
        keys = List.copyOf(keys); endpoints = Set.copyOf(endpoints); endpointExpiries = Map.copyOf(endpointExpiries);
        if (!endpoints.equals(endpointExpiries.keySet())) throw invalid();
        for (long expiry : endpointExpiries.values()) if (expiry < 1 || expiry > expiresAt) throw invalid();
        Set<String> ids = new HashSet<>();
        for (Key key : keys) if (!ids.add(key.keyId())) throw invalid();
    }
    Key key(String id) { return keys.stream().filter(key -> key.keyId().equals(id)).findFirst().orElse(null); }
    long endpointExpiry(Endpoint endpoint) { return endpointExpiries.getOrDefault(endpoint, 0L); }
    @Override public String toString() { return "DiagnosticHostPolicy[host=" + context.hostId() + ", endpoints=" + endpoints.size() + "]"; }
}
