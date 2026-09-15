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
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Transport boundary. Provider code performs no native allocation or game packet handling.
 */
public interface ProviderTransport {
    enum ApplyResult {PENDING, APPLIED, REJECTED}

    /** Opaque, single-use handle owned by one live transport instance. */
    interface AdmissionUpdate { }

    /** Controlled transports start with new player admission disabled, before binding. */
    default boolean supportsAdmissionStaging() { return false; }

    /**
     * Disable new player admission synchronously; existing peers survive. Supply an absolute System.nanoTime
     * deadline at most 300 seconds ahead. Capture nanoTime before reading the remaining authority lifetime;
     * add that bounded remaining duration to the captured value so a pause cannot extend the deadline.
     */
    default AdmissionUpdate beginAdmissionUpdate(long deadlineNanos) {
        throw new UnsupportedOperationException("Admission staging unavailable");
    }

    /** Install one owned key snapshot while this update remains disabled; required even when keys are unchanged. */
    default CompletionStage<Void> installTicketKeys(AdmissionUpdate update, List<TicketKey> keys) {
        throw new UnsupportedOperationException("Admission staging unavailable");
    }

    /**
     * Call only after durable application storage completes. The nonblocking guard must throw if current
     * authority/application ownership is lost. It runs synchronously before the final native-instance fence;
     * it must not wait for other threads. Failure consumes this update and leaves admission disabled.
     */
    default CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate update, Runnable requireCurrent) {
        throw new UnsupportedOperationException("Admission staging unavailable");
    }

    /**
     * Existing PublishHostProfileRequest, exported from actual bound native metadata.
     */
    CompletionStage<JsonObject> hostProfile();

    /**
     * Immutable profile bytes plus an endpoint-material ownership guard. The guard throws IllegalStateException
     * after semantic replacement or native retirement; it must never wait, acquire a monitor or perform I/O.
     */
    final class HostProfileSnapshot {
        private final JsonObject profile;
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
            if (publicationVersion < 0) throw new IllegalArgumentException("Publication version");
            this.publicationVersion = publicationVersion;
            if (candidateRevision < 0 || candidateRevision > 9007199254740991L) throw new IllegalArgumentException("Candidate revision");
            this.candidateRevision = candidateRevision;
            this.profile = Objects.requireNonNull(profile, "profile").deepCopy();
            this.current = Objects.requireNonNull(requireCurrent, "requireCurrent");
        }
        public JsonObject profile() { return profile.deepCopy(); }
        /** Zero means this adapter has no revisioned native capture. */
        public long candidateRevision() { return candidateRevision; }
        public long publicationVersion() { return publicationVersion; }
        public void requireCurrent() { current.run(); }
    }

    /** Native listener lifetime, independent of endpoint material, ticket keys and control sockets. */
    final class NativeIdentitySnapshot {
        private final String incarnation;
        private final Runnable current;
        public NativeIdentitySnapshot(String incarnation, Runnable requireCurrent) {
            if (incarnation == null || !incarnation.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid native incarnation");
            this.incarnation = incarnation; current = Objects.requireNonNull(requireCurrent);
        }
        public String incarnation() { return incarnation; }
        public void requireCurrent() { current.run(); }
    }

    default boolean supportsNativeIdentityCapture() { return false; }

    /** Bounded nonblocking capture; unsupported adapters cannot opt into issued native ownership. */
    default NativeIdentitySnapshot captureNativeIdentity() { throw new UnsupportedOperationException("Native identity capture unavailable"); }

    /** Cheap local publication counter; freshness changes do not change candidateRevision. */
    default long candidatePublicationVersion() { return 0; }

    enum ConnectivityOutcome { ESTABLISHED, NOT_ESTABLISHED, UNKNOWN }
    record ConnectivityCheck(int family, ConnectivityOutcome outcome, long checkedAt, long expiresAt) {
        public ConnectivityCheck {
            Objects.requireNonNull(outcome);
            if ((family != 4 && family != 6) || checkedAt < 0 || expiresAt > 9007199254740991L
                    || expiresAt <= checkedAt || expiresAt - checkedAt > 300000)
                throw new IllegalArgumentException("Invalid connectivity check");
        }
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
        throw new UnsupportedOperationException("Bounded outcome polling is required for controlled mode");
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
