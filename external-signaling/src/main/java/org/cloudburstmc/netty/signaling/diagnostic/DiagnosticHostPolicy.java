/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.util.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Trusted installed host authority. Incoming permits cannot select their own allowed target or revision. */
public record DiagnosticHostPolicy(Context context, List<Key> keys, Set<Endpoint> endpoints, long expiresAt) {
    public record Endpoint(int family, String addressHex, int port, long candidateRevision) {
        public Endpoint {
            if ((family != 4 && family != 6) || port < 1 || port > 65535 || candidateRevision < 1 || candidateRevision > SAFE) throw invalid();
            byte[] bytes = unhex(addressHex, 16);
            if (family == 4 ? !Arrays.equals(Arrays.copyOf(bytes, 12), new byte[12]) : addressHex.startsWith("00000000000000000000ffff")) throw invalid();
        }
        public static Endpoint from(Claims claims) { return new Endpoint(claims.family(), claims.targetAddressHex(), claims.targetPort(), claims.candidateRevision()); }
    }
    public DiagnosticHostPolicy {
        Objects.requireNonNull(context);
        if (keys.size() > 8 || endpoints.size() > 16 || expiresAt < 1 || expiresAt > SAFE) throw invalid();
        keys = List.copyOf(keys); endpoints = Set.copyOf(endpoints);
        Set<String> ids = new HashSet<>();
        for (Key key : keys) if (!ids.add(key.keyId())) throw invalid();
    }
    Key key(String id) { return keys.stream().filter(key -> key.keyId().equals(id)).findFirst().orElse(null); }
    @Override public String toString() { return "DiagnosticHostPolicy[host=" + context.hostId() + ", endpoints=" + endpoints.size() + "]"; }
}
