package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.control.CandidateLeaseCodec;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/**
 * Converts already sampled gameplay-mux observations into fixed lease bytes. This owner neither
 * polls native state nor publishes candidates. Use one tracker per native listener incarnation and
 * close it before that listener; host/generation authentication remains the enclosing control owner.
 *
 * Capture/recovery/close serialize local observation updates. Captured guards use only immutable
 * owner state and raw clocks, without locks, native reads, storage or network work. Preserve guards
 * through every asynchronous publication boundary; a receipt alone never replaces one.
 */
public final class ObservationLeaseTracker implements AutoCloseable {
    private static final long NANOS_PER_MILLI = 1000000;
    private static final long NATIVE_AGE_NANOS = CandidateLeaseCodec.MAX_OBSERVATION_AGE_MILLIS * NANOS_PER_MILLI;
    private static final long LEASE_NANOS = CandidateLeaseCodec.MAX_LEASE_MILLIS * NANOS_PER_MILLI;

    private record Material(long monitorEpoch, long mappingRevision, String addressHex, int port,
                            String serverAddressHex, int serverPort) { }
    private record Entry(CandidateLeaseCodec.Observation observation, long nativeEndNanos, long leaseEndNanos,
                         ClockEpoch clock) { }
    private record HighWater(Material material, long sequence, Entry entry) { }
    private record Active(Material material, Object token) { }
    private record Retained(Entry entry, Family family, Object token) { }
    private record ClockReading(long monotonicNanos, long wallMillis, boolean valid) { }

    private static final class ClockEpoch {
        final long wallAnchor, monotonicAnchor;
        final AtomicReference<ClockReading> reading;
        ClockEpoch(long wallAnchor, long monotonicAnchor) {
            this.wallAnchor = wallAnchor; this.monotonicAnchor = monotonicAnchor;
            reading = new AtomicReference<>(new ClockReading(monotonicAnchor, wallAnchor, true));
        }
        void invalidate() {
            reading.updateAndGet(previous -> new ClockReading(previous.monotonicNanos(), previous.wallMillis(), false));
        }
    }

    /** Immutable original observation bytes and bounds; newer same-mapping successes do not revoke it. */
    public final class Capture {
        private final ClockEpoch clock;
        private final List<Retained> retained;
        private final List<CandidateLeaseCodec.Observation> observations;
        private Capture(ClockEpoch clock, List<Retained> retained) {
            this.clock = clock; this.retained = List.copyOf(retained);
            observations = this.retained.stream().map(item -> item.entry().observation()).toList();
        }
        public String nativeIncarnation() { return nativeIncarnation; }
        public List<CandidateLeaseCodec.Observation> observations() { return observations; }

        /** Rebinds the same response to a new profile/key without renewing its observation dates. */
        public CandidateLeaseCodec.Leases bind(CandidateLeaseCodec.Profile profile, CandidateLeaseCodec.NativeOwner issuedOwner) {
            requireCurrent();
            if (!nativeIncarnation.equals(profile.nativeIncarnation())) throw new IllegalArgumentException("Different native incarnation");
            CandidateLeaseCodec.Leases leases = CandidateLeaseCodec.bind(profile, issuedOwner, observations);
            requireCurrent(); return leases;
        }

        /** Fail closed immediately before actual send and after asynchronous result delivery. */
        public void requireCurrent() {
            requireOwner();
            ClockReading now = readClock(clock);
            for (Retained item : retained) {
                if (!live(item.entry(), clock, now)) throw new IllegalStateException("Candidate lease expired");
            }
            requireOwner();
        }

        private void requireOwner() {
            if (closed || currentClock != clock || !clock.reading.get().valid())
                throw new IllegalStateException("Candidate lease clock or listener no longer owned");
            Map<Family, Active> current = active.get();
            for (Retained item : retained) {
                Active family = current.get(item.family());
                if (family == null || family.token() != item.token())
                    throw new IllegalStateException("Candidate lease mapping no longer owned");
            }
        }
    }

    private final String nativeIncarnation;
    private final LongSupplier wallMillis, nanoTime;
    // These high-water marks survive an empty current association and local clock recovery.
    private final EnumMap<Family, HighWater> highWater = new EnumMap<>(Family.class);
    private final AtomicReference<Map<Family, Active>> active = new AtomicReference<>(Map.of());
    private volatile ClockEpoch currentClock;
    private volatile boolean closed;

    public ObservationLeaseTracker(String nativeIncarnation) {
        this(nativeIncarnation, System::currentTimeMillis, System::nanoTime);
    }

    /** The monotonic supplier must be the same timebase used by the observation controller. */
    public ObservationLeaseTracker(String nativeIncarnation, LongSupplier wallMillis, LongSupplier nanoTime) {
        if (nativeIncarnation == null || !nativeIncarnation.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid native incarnation");
        this.nativeIncarnation = nativeIncarnation;
        this.wallMillis = Objects.requireNonNull(wallMillis); this.nanoTime = Objects.requireNonNull(nanoTime);
        currentClock = anchorClock();
    }

    /**
     * Observe both families atomically from an existing controller snapshot. Missing/unavailable
     * families withdraw their local authority without deleting replay protection. A failed STUN
     * transaction can retain the controller's still-fresh previous success.
     */
    public synchronized Capture capture(EndpointConnectivityController.Snapshot snapshot) {
        requireOpen(); Objects.requireNonNull(snapshot);
        ClockEpoch clock = currentClock; ClockReading now = readClock(clock);
        EnumMap<Family, Active> next = new EnumMap<>(Family.class);
        next.putAll(active.get());
        List<Retained> retained = new ArrayList<>();
        RuntimeException rejected = null;
        try {
            for (Family family : Family.values()) {
                try {
                    var lane = snapshot.families().get(family);
                    if (lane == null || lane.observation().isEmpty()) { next.remove(family); continue; }
                    EndpointConnectivityController.Observation sample = lane.observation().get();
                    HighWater seen = observe(family, highWater.get(family), sample, clock, now);
                    highWater.put(family, seen);
                    boolean available = lane.state() == EndpointConnectivityController.State.STUN_FRESH
                            && lane.freshStunEndpoint().isPresent()
                            && sameEndpoint(lane.freshStunEndpoint().get(), sample.mapped())
                            && EndpointAddress.scope(sample.mapped().getAddress()) == EndpointAddress.Scope.PUBLIC
                            && seen.entry() != null && live(seen.entry(), clock, now);
                    if (!available) { next.remove(family); continue; }
                    Active owned = next.get(family);
                    if (owned == null || !owned.material().equals(seen.material())) owned = new Active(seen.material(), new Object());
                    next.put(family, owned); retained.add(new Retained(seen.entry(), family, owned.token()));
                } catch (RuntimeException failure) {
                    next.remove(family);
                    if (rejected == null) rejected = failure;
                    // Still consume the other lane's material change; its old guard must not survive
                    // merely because this lane was rejected first.
                }
            }
        } finally {
            // A rejected native sample must also revoke the affected old capture's authority.
            active.set(Map.copyOf(next));
        }
        if (rejected != null) throw rejected;
        Capture capture = new Capture(clock, retained); capture.requireCurrent(); return capture;
    }

    private HighWater observe(Family family, HighWater previous, EndpointConnectivityController.Observation sample,
                              ClockEpoch clock, ClockReading now) {
        Objects.requireNonNull(sample.transactionState());
        requireEndpoint(sample.server()); requireEndpoint(sample.mapped());
        if (Family.of(sample.server().getAddress()) != family || Family.of(sample.mapped().getAddress()) != family)
            throw new IllegalArgumentException("Observation family mismatch");
        safe(sample.monitorEpoch(), true); safe(sample.mappingRevision(), true); safe(sample.successfulResponses(), true);
        safe(sample.failedTransactions(), false); safe(sample.lastSuccessAgeMillis(), false);
        Material material = new Material(sample.monitorEpoch(), sample.mappingRevision(), hex(sample.mapped()), sample.mapped().getPort(),
                hex(sample.server()), sample.server().getPort());
        if (previous != null) {
            Material old = previous.material();
            if (material.monitorEpoch() < old.monitorEpoch()
                    || material.monitorEpoch() == old.monitorEpoch() && sample.successfulResponses() < previous.sequence())
                throw new IllegalArgumentException("Native observation replay");
            if (material.monitorEpoch() == old.monitorEpoch()) {
                if (!material.serverAddressHex().equals(old.serverAddressHex()) || material.serverPort() != old.serverPort())
                    throw new IllegalArgumentException("Native monitor changed without an epoch");
                if (sample.successfulResponses() == previous.sequence()) {
                    if (!material.equals(old)) throw new IllegalArgumentException("Changed equal-sequence observation");
                    return previous; // Ignore poll age/failed transactions; retain original bytes and deadlines.
                }
                if (material.mappingRevision() < old.mappingRevision()
                        || material.mappingRevision() == old.mappingRevision()
                        && (!material.addressHex().equals(old.addressHex()) || material.port() != old.port()))
                    throw new IllegalArgumentException("Native mapping revision replay");
            }
        }
        // Even failed clock conversion has now seen this native success. Recovery must not stamp
        // the same sequence again using a new anchor.
        highWater.put(family, new HighWater(material, sample.successfulResponses(), null));
        Entry entry = entry(family, sample, clock, now);
        return new HighWater(material, sample.successfulResponses(), entry);
    }

    private Entry entry(Family family, EndpointConnectivityController.Observation sample, ClockEpoch clock, ClockReading now) {
        // A stale or ineligible new response still advances replay protection but supplies no lease.
        if (sample.lastSuccessAgeMillis() >= CandidateLeaseCodec.MAX_OBSERVATION_AGE_MILLIS
                || EndpointAddress.scope(sample.mapped().getAddress()) != EndpointAddress.Scope.PUBLIC
                || sample.freshUntilNanos() <= now.monotonicNanos()) return null;
        try {
            if (sample.freshUntilNanos() > Math.addExact(now.monotonicNanos(), NATIVE_AGE_NANOS))
                throw new IllegalArgumentException("Observation freshness exceeds native age bound");
            long successLower = Math.subtractExact(Math.subtractExact(sample.freshUntilNanos(), NATIVE_AGE_NANOS), NANOS_PER_MILLI);
            long observedAt = Math.addExact(clock.wallAnchor,
                    Math.floorDiv(Math.subtractExact(successLower, clock.monotonicAnchor), NANOS_PER_MILLI));
            long expiresAt = Math.addExact(observedAt, CandidateLeaseCodec.MAX_LEASE_MILLIS);
            long leaseEnd = Math.addExact(successLower, LEASE_NANOS);
            if (observedAt < 0 || observedAt > CandidateLeaseCodec.MAX_SAFE_INTEGER
                    || expiresAt < 0 || expiresAt > CandidateLeaseCodec.MAX_SAFE_INTEGER)
                throw new ArithmeticException("Derived lease time is not a nonnegative safe integer");
            var observation = new CandidateLeaseCodec.Observation(family == Family.IPV4 ? "ipv4" : "ipv6", hex(sample.mapped()),
                    sample.mapped().getPort(), sample.monitorEpoch(), sample.mappingRevision(), sample.successfulResponses(), observedAt, expiresAt);
            return new Entry(observation, sample.freshUntilNanos(), leaseEnd, clock);
        } catch (ArithmeticException failure) {
            clock.invalidate(); throw new IllegalStateException("Candidate lease clock arithmetic failed", failure);
        }
    }

    private static boolean live(Entry entry, ClockEpoch clock, ClockReading now) {
        return entry.clock() == clock && now.valid() && now.monotonicNanos() < entry.nativeEndNanos()
                && now.monotonicNanos() < entry.leaseEndNanos() && now.wallMillis() < entry.observation().expiresAt();
    }

    public boolean clockValid() { return !closed && currentClock.reading.get().valid(); }

    /**
     * Caller must have just completed a fresh successful control synchronization. This deliberate
     * recovery never retimestamps an already seen success: each family requires a newer native
     * response (or a newer monitor owner) before it can issue a lease in the new clock epoch.
     * No capture from the invalidated epoch can ever become valid again.
     */
    public synchronized void recoverAfterSuccessfulControlSynchronization() {
        requireOpen();
        if (currentClock.reading.get().valid()) throw new IllegalStateException("Candidate lease clock has not failed");
        currentClock = anchorClock(); active.set(Map.of());
    }

    @Override public synchronized void close() {
        closed = true; active.set(Map.of()); currentClock.invalidate();
    }

    private ClockEpoch anchorClock() {
        long before = nanoTime.getAsLong(), wall = wallMillis.getAsLong(), after = nanoTime.getAsLong();
        safe(wall, false);
        if (after < before) throw new IllegalStateException("Monotonic clock reversed while anchoring");
        // Wall precedes the second monotonic sample: preemption shortens, never extends a lease.
        return new ClockEpoch(wall, after);
    }

    private ClockReading readClock(ClockEpoch clock) {
        // CAS prevents concurrent guard/capture reads from mistaking an older in-flight sample for
        // reversal. Retry by resampling after the winning reader, never by reusing sampled times.
        for (int attempt = 0; attempt < 8; attempt++) {
            ClockReading previous = clock.reading.get();
            if (!previous.valid() || currentClock != clock || closed) throw new IllegalStateException("Candidate lease clock unavailable");
            ClockReading next;
            try {
                long before = nanoTime.getAsLong(), rawWall = wallMillis.getAsLong(), after = nanoTime.getAsLong();
                safe(rawWall, false);
                if (before < previous.monotonicNanos() || after < before) throw new IllegalStateException("Monotonic clock reversed");
                long affineWall = Math.addExact(clock.wallAnchor,
                        Math.floorDiv(Math.subtractExact(after, clock.monotonicAnchor), NANOS_PER_MILLI));
                safe(affineWall, false);
                long deviation = Math.subtractExact(rawWall, affineWall);
                if (deviation < -CandidateLeaseCodec.CLOCK_SKEW_MILLIS || deviation > CandidateLeaseCodec.CLOCK_SKEW_MILLIS)
                    throw new IllegalStateException("Wall clock deviated from lease anchor");
                // An allowed raw-wall rollback must never revive authority already expired by an
                // earlier reading. Retain this high-water in the same CAS as monotonic ordering.
                next = new ClockReading(after, Math.max(previous.wallMillis(), Math.max(rawWall, affineWall)), true);
            } catch (RuntimeException failure) {
                // If a concurrent reader won, resample against that state before judging reversal.
                next = new ClockReading(previous.monotonicNanos(), previous.wallMillis(), false);
                if (clock.reading.compareAndSet(previous, next)) throw new IllegalStateException("Candidate lease clock invalid", failure);
                continue;
            }
            if (clock.reading.compareAndSet(previous, next)) return next;
        }
        throw new IllegalStateException("Concurrent candidate lease clock reads; retry without sending");
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Candidate lease tracker closed"); }
    private static void safe(long value, boolean positive) {
        if (value < (positive ? 1 : 0) || value > CandidateLeaseCodec.MAX_SAFE_INTEGER)
            throw new IllegalArgumentException("Invalid observation safe integer");
    }
    private static String hex(InetSocketAddress endpoint) { return HexFormat.of().formatHex(endpoint.getAddress().getAddress()); }
    private static void requireEndpoint(InetSocketAddress endpoint) {
        if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() < 1 || endpoint.getPort() > 65535)
            throw new IllegalArgumentException("Expected a resolved numeric endpoint");
    }
    private static boolean sameEndpoint(InetSocketAddress left, InetSocketAddress right) {
        return !left.isUnresolved() && left.getPort() == right.getPort() && left.getAddress().equals(right.getAddress());
    }
}
