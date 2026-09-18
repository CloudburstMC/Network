/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.net.InetSocketAddress;
import java.util.Set;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Transport boundary. Provider code performs no native allocation or game packet handling.
 */
public interface ProviderTransport {
    enum ApplyResult {PENDING, APPLIED, REJECTED}

    /**
     * Existing PublishHostProfileRequest, exported from actual bound native metadata.
     */
    CompletionStage<JsonObject> hostProfile();

    default boolean supportsAssistedJoins() { return false; }
    default CompletionStage<String> assistedJoin(org.cloudburstmc.netty.signaling.control.AssistedJoin join, Runnable requireCurrent) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Assisted joins unavailable"));
    }

    /** Candidate material changed while the native listener remained available; capture a fresh profile. */
    final class HostProfileSnapshotChangedException extends IllegalStateException {
        public HostProfileSnapshotChangedException() { super("Host profile candidate snapshot changed"); }
    }

    /**
     * Immutable profile bytes plus an endpoint-material ownership guard. The guard throws IllegalStateException
     * after native retirement, or HostProfileSnapshotChangedException after live semantic replacement;
     * it must never wait, acquire a monitor or perform I/O.
     */
    final class HostProfileSnapshot {
        private final JsonObject profile;
        private final JsonArray probeCandidates;
        private final Set<Integer> assistedFamilies;
        private final long candidateRevision;
        private final long publicationVersion;
        private final Runnable current;
        public HostProfileSnapshot(JsonObject profile, Runnable requireCurrent) {
            this(profile, 0, requireCurrent);
        }
        public HostProfileSnapshot(JsonObject profile, long candidateRevision, Runnable requireCurrent) {
            this(profile, candidateRevision, 0, requireCurrent);
        }
        public HostProfileSnapshot(JsonObject profile, long candidateRevision, long publicationVersion, Runnable requireCurrent) {
            this(profile, candidateRevision, publicationVersion, profile.getAsJsonArray("candidates"), Set.of(), requireCurrent);
        }
        public HostProfileSnapshot(JsonObject profile, long candidateRevision, long publicationVersion,
                                   JsonArray probeCandidates, Set<Integer> assistedFamilies, Runnable requireCurrent) {
            this.probeCandidates = Objects.requireNonNull(probeCandidates).deepCopy();
            this.assistedFamilies = Set.copyOf(assistedFamilies);
            if (probeCandidates.size() > 32 || !Set.of(4, 6).containsAll(assistedFamilies)) throw new IllegalArgumentException("Connectivity snapshot bounds");
            if (publicationVersion < 0) throw new IllegalArgumentException("Publication version");
            this.publicationVersion = publicationVersion;
            if (candidateRevision < 0 || candidateRevision > 9007199254740991L) throw new IllegalArgumentException("Candidate revision");
            this.candidateRevision = candidateRevision;
            this.profile = Objects.requireNonNull(profile, "profile").deepCopy();
            this.current = Objects.requireNonNull(requireCurrent, "requireCurrent");
        }
        public JsonObject profile() { return profile.deepCopy(); }
        public JsonArray probeCandidates() { return probeCandidates.deepCopy(); }
        /** Families eligible for assistance when explicitly enabled by ProviderClient configuration. */
        public Set<Integer> assistedFamilies() { return assistedFamilies; }
        /** Zero means this adapter has no revisioned native capture. */
        public long candidateRevision() { return candidateRevision; }
        public long publicationVersion() { return publicationVersion; }
        public void requireCurrent() { current.run(); }
    }

    /** Cheap local publication counter; freshness changes do not change candidateRevision. */
    default long candidatePublicationVersion() { return 0; }

    enum ConnectivityOutcome { ESTABLISHED, NOT_ESTABLISHED, UNKNOWN, UNAVAILABLE }
    record ConnectivityCheck(int family, String method, InetSocketAddress target, ConnectivityOutcome outcome, long checkedAt, long expiresAt) {
        public ConnectivityCheck(int family, ConnectivityOutcome outcome, long checkedAt, long expiresAt) {
            this(family, "defined", null, outcome, checkedAt, expiresAt);
        }
        public ConnectivityCheck {
            Objects.requireNonNull(outcome);
            if (!Set.of("defined", "discovered", "warm_stun", "per_join").contains(method)
                    || target != null && (target.isUnresolved() || target.getPort() < 1
                    || (target.getAddress() instanceof java.net.Inet4Address ? 4 : 6) != family))
                throw new IllegalArgumentException("Invalid connectivity method/target");
            if ((family != 4 && family != 6) || checkedAt < 0 || expiresAt > 9007199254740991L
                    || expiresAt <= checkedAt || expiresAt - checkedAt > 300000)
                throw new IllegalArgumentException("Invalid connectivity check");
        }
    }

    record StunServer(String host, int port) {
        public StunServer {
            if (host == null || host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.:-]+") || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid provider STUN server");
        }
    }
    /** Provider discovery only. Explicit endpoints suppress resolution and monitoring. */
    default CompletionStage<Void> configureStunServers(List<StunServer> servers) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** Existing heartbeat feedback only; no state/health command or reachability assertion. */
    default CompletionStage<Void> reportConnectivityChecks(long candidateRevision, List<ConnectivityCheck> checks) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /** Local opt-in, independent of player serving state. Incoming probes cannot configure their own authority. */
    default boolean supportsDiagnosticAdmission() { return false; }
    default CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy) {
        throw new UnsupportedOperationException("Diagnostic admission unavailable");
    }
    /** Original successful-heartbeat monotonic deadline; asynchronous installation must not renew it. */
    default CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy, long deadlineNanos) {
        throw new UnsupportedOperationException("Bounded diagnostic configuration unavailable");
    }
    default CompletionStage<Void> disableDiagnostics() {
        throw new UnsupportedOperationException("Diagnostic admission unavailable");
    }

    /**
     * Capture endpoint ownership across asynchronous publication, persistence and application.
     * Legacy adapters retain their unversioned behavior; versioned profiles require an owned override.
     */
    default CompletionStage<HostProfileSnapshot> captureHostProfile() {
        return hostProfile().thenApply(profile -> {
            if (profile == null || profile.has("version")) throw new IllegalStateException("Versioned host profiles require snapshot ownership");
            return new HostProfileSnapshot(profile, () -> { });
        });
    }

    /**
     * Atomic native snapshot installation. Durable application storage remains the caller's responsibility.
     */
    CompletionStage<Void> installTicketKeys(List<TicketKey> keys);

    /**
     * Apply serving/draining/closed background state before acknowledging its revision.
     */
    CompletionStage<ApplyResult> applyState(String state);

    /**
     * Whether this integration can observe the application join/rejection boundary.
     */
    default boolean supportsGameOutcomes() {
        return false;
    }

    /**
     * Bounded ticket-correlated transport and application observations.
     */
    List<JsonObject> pollEvents();

    /** Drain at most the caller's available retained capacity; unsupported transports must not drain anything. */
    default List<JsonObject> pollEvents(int maximum) {
        if (maximum < 0 || maximum > 256) throw new IllegalArgumentException("Invalid outcome poll bound");
        if (maximum == 0) return List.of();
        throw new UnsupportedOperationException("Bounded outcome polling unavailable");
    }

    CompletionStage<Void> drain();

    CompletionStage<Void> close();

    record TicketKey(String keyId, String secret, long notBefore, long retireAfter) {
        public TicketKey(String keyId, String secret) {
            this(keyId, secret, 0, Long.MAX_VALUE);
        }

        @Override
        public String toString() {
            return "TicketKey[keyId=" + keyId + "]";
        }
    }
}
