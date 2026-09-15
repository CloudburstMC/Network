package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.ControlClientCoordinator;
import org.cloudburstmc.netty.signaling.control.ControlFrameCodec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit configuration for an already enrolled, reconciled controlled host. It does not enroll,
 * discover endpoints, or import trust from server responses. Removing this configuration after
 * migration cannot restore the legacy lifecycle.
 */
public record ProviderControlConfiguration(ControlClientCoordinator.Config routes,
        List<ControlFrameCodec.VerificationKey> providerKeys, ReportingSeed migrationSeed) {
    /** Reporting floor only. The new native instance must still apply actual state before acknowledging it. */
    public record ReportingSeed(long generation, long appliedRevision, String reportedState) {
        public ReportingSeed {
            if (generation < 1 || generation > ControlFrameCodec.MAX_SAFE_INTEGER || appliedRevision < 0
                    || appliedRevision > ControlFrameCodec.MAX_SAFE_INTEGER
                    || !Set.of("serving", "draining", "closed").contains(reportedState)) {
                throw new IllegalArgumentException("Invalid reconciled reporting seed");
            }
        }
    }
    public ProviderControlConfiguration {
        Objects.requireNonNull(routes); Objects.requireNonNull(migrationSeed);
        providerKeys = List.copyOf(providerKeys);
        if (providerKeys.isEmpty() || providerKeys.size() > 8
                || !routes.operations().keySet().containsAll(Set.of("heartbeat", "outcomes", "rotate", "retire", "deregister"))) {
            throw new IllegalArgumentException("Controlled routes and bounded provider-control trust are required");
        }
        var names = new HashSet<String>();
        for (var key : providerKeys) {
            if (key.family() != ControlFrameCodec.KeyFamily.PROVIDER_CONTROL || !names.add(key.keyId())) {
                throw new IllegalArgumentException("Invalid provider-control key catalog");
            }
        }
        // Assisted traffic needs a separately owned dispatcher; this first integration has none.
        if (routes.capabilities().stream().anyMatch(cap -> cap.startsWith("assisted-"))) {
            throw new IllegalArgumentException("ProviderClient assisted dispatch is not installed");
        }
    }
    ControlClientCoordinator.ProviderKeys trustedKeys() {
        var keys = providerKeys.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ControlFrameCodec.VerificationKey::keyId, key -> key));
        return keys::get;
    }
    @Override public String toString() { return "ProviderControlConfiguration[audience=" + routes.audience()
            + ", transport=" + routes.transport() + "]"; }
}
