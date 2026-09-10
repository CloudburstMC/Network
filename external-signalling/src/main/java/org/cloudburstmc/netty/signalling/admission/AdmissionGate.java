package org.cloudburstmc.netty.signalling.admission;

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
        private VerifiedAdmission admission;
        private final String tokenId;
        private final InetSocketAddress tuple;
        private final long expiresAt, acceptedNanos;
        private boolean ready, connected, closing, closed;

        private Reservation(VerifiedAdmission admission, InetSocketAddress tuple, long nanos) {
            this.admission = admission;
            this.tokenId = admission.tokenId();
            this.tuple = tuple;
            this.expiresAt = admission.expiresAt();
            this.acceptedNanos = nanos;
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
        VerifiedAdmission a = validator.validate(request, nowMillis);
        if (a == null) {
            invalid++;
            return null;
        }
        if (claims.containsKey(a.tokenId()) || tuples.containsKey(request.address())) {
            replayRejected++;
            return null;
        }
        if (draining) {
            capacityRejected++;
            return null;
        }
        if (pending >= limits.pending()) {
            capacityRejected++;
            pendingLimitRejected++;
            pendingAtRejection = pending;
            return null;
        }
        if (tuples.size() >= limits.sessions() || claims.size() >= limits.claims()) {
            capacityRejected++;
            return null;
        }
        Reservation r = new Reservation(a, request.address(), nowNanos);
        claims.put(r.tokenId, r);
        tuples.put(r.tuple, r);
        pending++;
        return r;
    }

    public synchronized VerifiedAdmission admission(Reservation r) {
        return current(r) && !r.closing ? r.admission : null;
    }

    /**
     * Native STUN verification and peer creation succeeded. A used token cannot allocate another peer.
     */
    public synchronized boolean ready(Reservation r) {
        if (!current(r) || r.closing || r.ready) {
            return false;
        }
        r.ready = true;
        pending--;
        accepted++;
        return true;
    }

    public synchronized void connected(Reservation r) {
        if (current(r) && !r.closing) {
            r.connected = true;
        }
    }

    public synchronized void invalidNativeRequest() {
        invalid++;
    }

    /**
     * Call only once native teardown is complete, or when native creation never started.
     */
    public synchronized boolean finish(Reservation r) {
        if (!current(r)) {
            return false;
        }
        if (!r.ready) {
            pending--;
        }
        tuples.remove(r.tuple);
        r.closed = true;
        r.admission = null;
        // A copied token with forged STUN integrity must not consume the real client's token.
        if (!r.ready || closed) {
            claims.remove(r.tokenId);
        }
        return true;
    }

    private boolean current(Reservation r) {
        return !r.closed && claims.get(r.tokenId) == r;
    }

    /**
     * Mark timed-out handshakes for closure. Capacity stays reserved until finish is called.
     */
    public synchronized List<Reservation> sweep(long nowMillis, long nowNanos) {
        List<Reservation> timedOut = new ArrayList<>();
        for (Reservation r : claims.values()) {
            if (!r.closed && !r.closing && !r.connected
                    && nowNanos - r.acceptedNanos >= limits.handshakeMillis() * 1_000_000L) {
                r.closing = true;
                timedOut.add(r);
            }
        }
        claims.values().removeIf(r -> r.closed && r.expiresAt <= nowMillis);
        return timedOut;
    }

    public synchronized void drain() {
        draining = true;
    }

    public synchronized List<Reservation> close() {
        closed = true;
        List<Reservation> active = new ArrayList<>(tuples.values());
        for (Reservation r : active) {
            r.closing = true;
        }
        claims.values().removeIf(r -> r.closed);
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
