/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.net.InetSocketAddress;
import java.util.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Neutral trusted installation values, not a wire decoder, workload authorization or player principal. */
public final class DiagnosticAdmission {
    private DiagnosticAdmission() { }

    public record Binding(Context context, String authorityIncarnation, long nativeOwnerEpoch, String hostProfileRevision, String hostProfileSha256,
                          long policyRevision, String installationSha256, String hostFingerprintHex) {
        public Binding {
            Objects.requireNonNull(context); integer(nativeOwnerEpoch, 1, SAFE); integer(policyRevision, 1, SAFE);
            if (authorityIncarnation == null || !authorityIncarnation.matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
            if (hostProfileRevision == null || !hostProfileRevision.matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
            digest(hostProfileSha256); digest(installationSha256); unhex(hostFingerprintHex, 32);
        }
    }
    public record Endpoint(DiagnosticHostPolicy.Endpoint target, String type, long expiresAt) {
        public Endpoint {
            Objects.requireNonNull(target);
            if (!"host".equals(type) && !"srflx".equals(type)) throw invalid();
            integer(expiresAt, 1, SAFE);
        }
    }
    public record Policy(Binding binding, List<Key> keys, List<Endpoint> endpoints, long notBefore, long expiresAt) {
        public Policy {
            Objects.requireNonNull(binding); integer(notBefore, 0, SAFE); integer(expiresAt, notBefore + 1, SAFE);
            if (expiresAt - notBefore > 300_000 || keys.size() > 8 || endpoints.size() > 32) throw invalid();
            keys = List.copyOf(keys); endpoints = List.copyOf(endpoints);
            Set<String> ids = new HashSet<>(), tuples = new HashSet<>();
            for (Key key : keys) if (!ids.add(key.keyId())) throw invalid();
            for (Endpoint endpoint : endpoints) {
                var target = endpoint.target();
                if (endpoint.expiresAt() <= notBefore || endpoint.expiresAt() > expiresAt
                        || !tuples.add(target.family() + ":" + target.addressHex() + ":" + target.port())) throw invalid();
            }
        }
        public DiagnosticHostPolicy hostPolicy() {
            var deadlines = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
            endpoints.forEach(endpoint -> deadlines.put(endpoint.target(), endpoint.expiresAt()));
            return new DiagnosticHostPolicy(binding.context(), keys, deadlines.keySet(), expiresAt, deadlines, binding);
        }
        @Override public String toString() { return "DiagnosticAdmission.Policy[redacted, endpoints=" + endpoints.size() + "]"; }
    }
    /** Exact current install acknowledgement; historical results deliberately have a different lifetime. */
    public static final class Installation {
        private final Binding binding;
        private final Runnable current;
        public Installation(Binding binding, Runnable current) { this.binding = Objects.requireNonNull(binding); this.current = Objects.requireNonNull(current); }
        public Binding binding() { return binding; }
        public void requireCurrent() { current.run(); }
    }
    public record UdpCounters(long reserved, long sent, long sentBytes, long rejected) { }
    /** Immutable native observation. Null association means an explicitly unassociated low-level test installation. */
    public record Completion(Binding installation, Context context, String keyId, String attemptId, String offerDigestHex,
                             String clientFingerprintHex, long expiresAt, DiagnosticHostPolicy.Endpoint target,
                             boolean success, boolean cleanupComplete, String reason,
                             InetSocketAddress selectedLocal, InetSocketAddress selectedRemote, UdpCounters udp,
                             int sentFrames, int sentBytes, int receivedFrames, int receivedBytes,
                             String completionDigestHex, long completedAt) { }
    private static void digest(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}") || Base64.getUrlDecoder().decode(value).length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)).equals(value)) throw invalid();
    }
}
