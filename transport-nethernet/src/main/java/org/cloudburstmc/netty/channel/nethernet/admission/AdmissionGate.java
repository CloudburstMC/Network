package org.cloudburstmc.netty.channel.nethernet.admission;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Consumer;

/** Fixed-size replay and session reservation state. No native APIs under this monitor. */
public final class AdmissionGate {
    public record Limits(int sessions, int claims, int pending, long handshakeMillis) {
        public Limits {
            if (sessions < 1 || sessions > 65536 || claims < sessions || claims > 262144 || pending < 1 || pending > sessions || handshakeMillis < 100 || handshakeMillis > 120_000)
                throw new IllegalArgumentException("Admission limits");
        }
        public static Limits defaults() { return new Limits(1024, 8192, 1024, 15_000); }
    }
    public static final class Reservation {
        private VerifiedAdmission admission;
        private byte[] initialPacket;
        private final String tokenId;
        private final InetSocketAddress tuple;
        private final long expiresAt, acceptedNanos;
        private boolean ready, connected, closed;
        private Reservation(VerifiedAdmission admission, InetSocketAddress tuple, long nanos, byte[] packet) {
            this.admission = admission; this.tokenId = admission.tokenId(); this.tuple = tuple;
            this.expiresAt = admission.expiresAt(); this.acceptedNanos = nanos;
            this.initialPacket = packet.clone();
        }
        public String tokenId() { return tokenId; }
        public InetSocketAddress tuple() { return tuple; }
        public long acceptedNanos() { return acceptedNanos; }
        @Override public String toString() { return "Reservation[tokenId=" + tokenId + "]"; }
    }
    public record Stats(int sessions, int pending, int claims, long invalid, long replayRejected, long capacityRejected, long accepted, long retransmissions) {}
    public record PendingLimitWarning(int pending, int limit, long rejected) {}
    private static final long WARNING_INTERVAL_NANOS = 5_000_000_000L;
    private final Limits limits;
    private final AdmissionValidator validator;
    private final Map<String, Reservation> claims = new HashMap<>();
    private final Map<InetSocketAddress, Reservation> tuples = new HashMap<>();
    private int pending;
    private boolean draining, closed;
    private long invalid, replayRejected, capacityRejected, accepted, retransmissions;
    private long pendingLimitRejected, lastPendingWarningNanos;
    private int pendingAtRejection;
    private boolean pendingWarningEmitted;

    public AdmissionGate(Limits limits, AdmissionValidator validator) { this.limits = Objects.requireNonNull(limits); this.validator = Objects.requireNonNull(validator); }

    /** enqueue MUST be bounded and nonblocking, and never execute creation inline. */
    public synchronized boolean ingress(byte[] packet, InetSocketAddress tuple, long nowMillis, long nowNanos, Consumer<Reservation> enqueue) {
        if (closed) return false;
        Reservation existing = tuples.get(tuple);
        StunBinding binding = StunBinding.parse(packet);
        if (existing != null) {
            if (binding != null) {
                VerifiedAdmission a = existing.admission;
                if (!binding.localUfrag().equals(a.localUfrag()) || !binding.remoteUfrag().equals(a.remoteUfrag()) || !binding.verify(packet, a.localPassword())) { invalid++; return false; }
                retransmissions++;
                // Token expiry ends NEW admission. Consent/retransmits on the same live session remain valid.
                return existing.ready;
            }
            // DTLS and ICE responses are authenticated by the existing native peer. Malformed Binding requests never pass.
            return existing.ready && packet.length >= 13 && ((packet[0] >= 20 && packet[0] <= 63) ||
                (packet.length >= 20 && packet[0] == 1 && (packet[1] == 1 || packet[1] == 17)));
        }
        if (binding == null) { invalid++; return false; }
        VerifiedAdmission a = validator.validate(packet, binding, nowMillis);
        if (a == null) { invalid++; return false; }
        if (claims.containsKey(a.tokenId())) { replayRejected++; return false; }
        if (draining) { capacityRejected++; return false; }
        if (pending >= limits.pending()) {
            capacityRejected++; pendingLimitRejected++; pendingAtRejection = pending;
            return false;
        }
        if (tuples.size() >= limits.sessions() || claims.size() >= limits.claims()) { capacityRejected++; return false; }
        // Only authenticated, capacity-admitted requests are retained. The parser
        // caps each at 2048 bytes and pending reservations bound the number held.
        Reservation r = new Reservation(a, tuple, nowNanos, packet);
        claims.put(r.tokenId, r); tuples.put(tuple, r); pending++; accepted++;
        try { enqueue.accept(r); }
        catch (RuntimeException rejected) { finish(r); capacityRejected++; }
        return false; // defer this packet until creation; never wait for a client retry
    }

    public synchronized VerifiedAdmission admission(Reservation r) { return current(r) ? r.admission : null; }
    /** Activate and transfer the first packet once, atomically releasing its pending slot. */
    public synchronized byte[] ready(Reservation r) {
        if (!current(r) || r.ready) return null;
        byte[] packet = r.initialPacket;
        r.initialPacket = null;
        r.ready = true;
        pending--;
        return packet;
    }
    public synchronized void connected(Reservation r) { if (current(r)) r.connected = true; }
    public synchronized boolean finish(Reservation r) {
        if (!current(r)) return false;
        if (!r.ready) pending--;
        tuples.remove(r.tuple); r.closed = true; r.admission = null; r.initialPacket = null; // retain only a bounded replay tombstone
        return true;
    }
    private boolean current(Reservation r) { return !r.closed && claims.get(r.tokenId) == r; }

    /** Periodic sweep, independent of incoming traffic. Caller closes native peers outside the monitor. */
    public synchronized List<Reservation> sweep(long nowMillis, long nowNanos) {
        List<Reservation> timedOut = new ArrayList<>();
        for (Reservation r : claims.values()) if (!r.closed && !r.connected && nowNanos - r.acceptedNanos >= limits.handshakeMillis() * 1_000_000L) timedOut.add(r);
        for (Reservation r : timedOut) finish(r);
        claims.values().removeIf(r -> r.closed && r.expiresAt <= nowMillis);
        return timedOut;
    }
    public synchronized void drain() { draining = true; }
    public synchronized List<Reservation> close() {
        closed = true;
        List<Reservation> active = new ArrayList<>(tuples.values());
        for (Reservation r : active) finish(r);
        claims.clear(); return active;
    }
    public synchronized Stats stats() { return new Stats(tuples.size(), pending, claims.size(), invalid, replayRejected, capacityRejected, accepted, retransmissions); }
    /** Drain an aggregate on the owner thread; never invoke a logger in raw ingress. */
    public synchronized PendingLimitWarning pollPendingLimitWarning(long nowNanos) {
        if (pendingLimitRejected == 0 || (pendingWarningEmitted && nowNanos - lastPendingWarningNanos < WARNING_INTERVAL_NANOS)) return null;
        var warning = new PendingLimitWarning(pendingAtRejection, limits.pending(), pendingLimitRejected);
        pendingLimitRejected = 0; lastPendingWarningNanos = nowNanos; pendingWarningEmitted = true;
        return warning;
    }
}
