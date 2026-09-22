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

package org.cloudburstmc.netty.signaling.admission;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Bounded admission reservations and records of used tokens. Packet processing stays native.
 */
public final class AdmissionGate {
    public record Limits(int sessions, int claims, int pending, long handshakeMillis) {
        public Limits {
            if (sessions < 1 || sessions > 65536 || claims < sessions || claims > 262144 || pending < 1
                    || pending > sessions || handshakeMillis < 100 || handshakeMillis > 120_000) {
                throw new IllegalArgumentException("Admission limits");
            }
        }

        public static Limits defaults() {
            return new Limits(1024, 8192, 1024, 15_000);
        }
    }

    public static final class Reservation {
        private AdmissionContext admission;
        private final String tokenId;
        private final InetSocketAddress tuple;
        private final long expiresAt, acceptedNanos, loginDeadlineNanos;
        private boolean ready, connected, closing, closed;

        private Reservation(
                AdmissionContext admission, InetSocketAddress tuple, long millis, long nanos) {
            this.admission = admission;
            this.tokenId = admission.tokenId();
            this.tuple = tuple;
            this.expiresAt = admission.expiresAt();
            this.acceptedNanos = nanos;
            this.loginDeadlineNanos = nanos + (admission.expiresAt() - millis) * 1_000_000L;
        }

        public String tokenId() {
            return tokenId;
        }

        public InetSocketAddress tuple() {
            return tuple;
        }

        public long acceptedNanos() {
            return acceptedNanos;
        }

        @Override
        public String toString() {
            return "Reservation[tokenId=" + tokenId + "]";
        }
    }

    public record Stats(int sessions, int pending, int claims, long invalid, long replayRejected, long capacityRejected,
                        long accepted) {
    }

    public record PendingLimitWarning(int pending, int limit, long rejected) {
    }

    private static final long WARNING_INTERVAL_NANOS = 5_000_000_000L;
    private final Limits limits;
    private final AdmissionValidator validator;
    private final Map<String, Reservation> claims = new HashMap<>();
    private final Map<InetSocketAddress, Reservation> tuples = new HashMap<>();
    private int pending;
    private boolean draining, closed;
    private long invalid, replayRejected, capacityRejected, accepted;
    private long pendingLimitRejected, lastPendingWarningNanos;
    private int pendingAtRejection;
    private boolean pendingWarningEmitted;

    public AdmissionGate(Limits limits, AdmissionValidator validator) {
        this.limits = Objects.requireNonNull(limits);
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Reserve capacity after token validation. Native STUN verification must still succeed.
     */
    public synchronized Reservation reserve(AdmissionRequest request, long nowMillis, long nowNanos) {
        if (closed) {
            return null;
        }
        VerifiedAdmission verifiedAdmission = validator.validate(request, nowMillis);
        if (verifiedAdmission == null) {
            invalid++;
            return null;
        }

        return reserveAuthenticated(verifiedAdmission, request.address(), nowMillis, nowNanos);
    }

    synchronized Reservation reserveAuthenticated(
            AdmissionContext verifiedAdmission,
            InetSocketAddress address,
            long nowMillis,
            long nowNanos) {
        if (closed
                || verifiedAdmission.networkId().matches("0+")
                || verifiedAdmission.expiresAt() <= nowMillis) {
            invalid++;
            verifiedAdmission.identityVerifier().close();
            return null;
        }
        if (claims.containsKey(verifiedAdmission.tokenId()) || tuples.containsKey(address)) {
            replayRejected++;
            verifiedAdmission.identityVerifier().close();
            return null;
        }

        if (draining) {
            capacityRejected++;
            verifiedAdmission.identityVerifier().close();
            return null;
        }

        if (pending >= limits.pending()) {
            capacityRejected++;
            pendingLimitRejected++;
            pendingAtRejection = pending;
            verifiedAdmission.identityVerifier().close();
            return null;
        }

        if (tuples.size() >= limits.sessions() || claims.size() >= limits.claims()) {
            capacityRejected++;
            verifiedAdmission.identityVerifier().close();
            return null;
        }

        Reservation reservation = new Reservation(verifiedAdmission, address, nowMillis, nowNanos);
        claims.put(reservation.tokenId, reservation);
        tuples.put(reservation.tuple, reservation);
        pending++;
        return reservation;
    }

    synchronized AdmissionContext admission(Reservation reservation) {
        return current(reservation) && !reservation.closing ? reservation.admission : null;
    }

    /**
     * Native STUN verification and peer creation succeeded. A used token cannot allocate another peer.
     */
    public synchronized boolean ready(Reservation reservation) {
        if (!current(reservation) || reservation.closing || reservation.ready) {
            return false;
        }
        reservation.ready = true;
        pending--;
        accepted++;
        return true;
    }

    public synchronized void connected(Reservation reservation) {
        if (current(reservation) && !reservation.closing) {
            reservation.connected = true;
        }
    }

    public synchronized void invalidNativeRequest() {
        invalid++;
    }

    /**
     * Call only once native teardown is complete, or when native creation never started.
     */
    public synchronized boolean finish(Reservation reservation) {
        if (!current(reservation)) {
            return false;
        }

        if (!reservation.ready) {
            pending--;
        }

        tuples.remove(reservation.tuple);

        reservation.closed = true;
        reservation.admission.identityVerifier().close();
        reservation.admission = null;

        // A copied token with forged STUN integrity must not consume the real client's token.
        if (!reservation.ready || closed) {
            claims.remove(reservation.tokenId);
        }

        return true;
    }

    private boolean current(Reservation reservation) {
        return !reservation.closed && claims.get(reservation.tokenId) == reservation;
    }

    /**
     * Mark timed-out handshakes for closure. Capacity stays reserved until finish is called.
     */
    public synchronized List<Reservation> sweep(long nowMillis, long nowNanos) {
        List<Reservation> timedOut = new ArrayList<>();
        for (Reservation reservation : claims.values()) {
            if (!reservation.closed && !reservation.closing && (reservation.admission.identityVerifier().rejected()
                    || (reservation.admission.identityVerifier().pending() && nowNanos - reservation.loginDeadlineNanos >= 0)
                    || (!reservation.connected && nowNanos - reservation.acceptedNanos >= limits.handshakeMillis() * 1_000_000L))) {
                reservation.closing = true;
                timedOut.add(reservation);
            }
        }
        claims.values().removeIf(reservation -> reservation.closed && reservation.expiresAt <= nowMillis);
        return timedOut;
    }

    public synchronized void drain() {
        draining = true;
    }

    /** Admission policy state only; capacity and installed credentials are checked separately. */
    public synchronized boolean isServing() {
        return !closed && !draining;
    }

    public synchronized List<Reservation> close() {
        closed = true;

        List<Reservation> active = new ArrayList<>(tuples.values());
        for (Reservation reservation : active) {
            reservation.closing = true;
            reservation.admission.identityVerifier().close();
        }

        claims.values().removeIf(reservation -> reservation.closed);
        return active;
    }

    public synchronized Stats stats() {
        return new Stats(tuples.size(), pending, claims.size(), invalid, replayRejected, capacityRejected, accepted);
    }

    /**
     * Read one aggregate warning on the owner thread.
     */
    public synchronized PendingLimitWarning pollPendingLimitWarning(long nowNanos) {
        if (pendingLimitRejected == 0 || (pendingWarningEmitted
                && nowNanos - lastPendingWarningNanos < WARNING_INTERVAL_NANOS)) {
            return null;
        }

        var warning = new PendingLimitWarning(pendingAtRejection, limits.pending(), pendingLimitRejected);
        pendingLimitRejected = 0;
        lastPendingWarningNanos = nowNanos;
        pendingWarningEmitted = true;
        return warning;
    }
}
