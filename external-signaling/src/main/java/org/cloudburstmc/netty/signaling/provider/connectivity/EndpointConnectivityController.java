package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/**
 * Opt-in same-mux observation lifecycle. Native monitors send/refresh STUN independently of polling.
 * Call snapshot periodically to observe remaps/expiry; no HTTP, provider profile or reachability is emitted.
 * All operations are serialized, including monitor close/replacement. The caller must stop this owner
 * before its listener and must not use an old snapshot beyond its monotonic freshness deadline.
 */
public final class EndpointConnectivityController implements AutoCloseable {
    public enum State {
        CONFIGURED, DISABLED_BY_CONFIG, UNSUPPORTED_FAMILY, PUBLIC_DIRECT,
        STUN_NOT_CONFIGURED, STUN_PENDING, STUN_FAILED, STUN_FRESH, STUN_INELIGIBLE, STUN_STALE, MONITOR_FAILED, CLOSED
    }
    public enum TransactionState { PENDING, SUCCEEDED, FAILED }

    /** Atomic native sample. Empty age means no successful Binding response has been received. */
    public record Sample(InetSocketAddress server, InetSocketAddress mapped, TransactionState state,
                         long successfulResponses, long failedTransactions, long mappingRevision,
                         Optional<Duration> lastSuccessAge) {
        public Sample {
            Objects.requireNonNull(server); Objects.requireNonNull(state); Objects.requireNonNull(lastSuccessAge);
            EndpointSelection.requireEndpoint(server);
            if (mapped != null) EndpointSelection.requireEndpoint(mapped);
            if (successfulResponses < 0 || failedTransactions < 0 || mappingRevision < 0
                    || lastSuccessAge.filter(Duration::isNegative).isPresent()) {
                throw new IllegalArgumentException("Invalid native STUN sample");
            }
        }
    }

    public interface Monitor extends AutoCloseable {
        Optional<Sample> read();
        @Override void close();
    }
    /** The factory must inherit the listener's exact bind address and fixed port. No utility socket. */
    public interface MonitorFactory { Monitor open(InetSocketAddress numericServer); }

    public record Observation(InetSocketAddress server, InetSocketAddress mapped, TransactionState transactionState,
                              long monitorEpoch, long mappingRevision, long successfulResponses,
                              long failedTransactions, long lastSuccessAgeMillis, long freshUntilNanos) {
        /** Uses the same monotonic clock as this controller; freshness is never a reachability verdict. */
        public boolean freshAt(long nowNanos) { return lastSuccessAgeMillis >= 0 && nowNanos - freshUntilNanos < 0; }
    }
    public record FamilySnapshot(State state, List<EndpointSelection.Candidate> directCandidates,
                                 Optional<Observation> observation, Optional<InetSocketAddress> freshStunEndpoint) {
        public FamilySnapshot { directCandidates = List.copyOf(directCandidates); }
    }
    /** Revision changes only when the available endpoint set changes, not on unchanged STUN refreshes. */
    public record Snapshot(long candidateRevision, Map<Family, FamilySnapshot> families) {
        public Snapshot { families = Map.copyOf(families); }
    }

    private static final class Lane {
        final List<EndpointSelection.Candidate> direct;
        InetSocketAddress server;
        Monitor monitor;
        long epoch;
        boolean failed;
        InetSocketAddress fresh;
        Lane(List<EndpointSelection.Candidate> direct, InetSocketAddress server) { this.direct = direct; this.server = server; }
    }

    private final EndpointSelection selection;
    private final MonitorFactory factory;
    private final LongSupplier nanoTime;
    private final long maxAgeNanos;
    private final EnumMap<Family, Lane> lanes = new EnumMap<>(Family.class);
    private long candidateRevision = 1;
    private boolean closed;

    public EndpointConnectivityController(EndpointSelection selection, Map<Family, InetSocketAddress> numericServers,
                                          Duration maxObservationAge, MonitorFactory factory) {
        this(selection, numericServers, maxObservationAge, factory, System::nanoTime);
    }

    EndpointConnectivityController(EndpointSelection selection, Map<Family, InetSocketAddress> numericServers,
                                   Duration maxObservationAge, MonitorFactory factory, LongSupplier nanoTime) {
        this.selection = Objects.requireNonNull(selection);
        this.factory = Objects.requireNonNull(factory);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        Objects.requireNonNull(numericServers);
        if (maxObservationAge.isNegative() || maxObservationAge.isZero() || maxObservationAge.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("STUN observation age must be positive and at most five minutes");
        }
        maxAgeNanos = maxObservationAge.toNanos();
        numericServers.forEach(EndpointConnectivityController::checkServer);
        for (Family family : Family.values()) lanes.put(family, new Lane(selection.configured() ? selection.candidates(family) : selection.publicCandidates(family), numericServers.get(family)));
    }

    /** Explicit bounded DNS/provider rotation seam. A replacement withdraws the old observation immediately. */
    public synchronized void replaceStunServer(Family family, InetSocketAddress numericServer) {
        requireOpen();
        checkServer(family, numericServer);
        Lane lane = lanes.get(family);
        stop(lane);
        lane.server = numericServer;
        lane.failed = false;
    }

    public synchronized Snapshot snapshot() {
        EnumMap<Family, FamilySnapshot> result = new EnumMap<>(Family.class);
        for (Family family : Family.values()) result.put(family, sample(family, lanes.get(family)));
        return new Snapshot(candidateRevision, result);
    }

    private FamilySnapshot sample(Family family, Lane lane) {
        State inactive = closed ? State.CLOSED : selection.configured()
                ? (lane.direct.isEmpty() ? State.DISABLED_BY_CONFIG : State.CONFIGURED)
                : !selection.socketFamilies().contains(family) ? State.UNSUPPORTED_FAMILY : lane.failed ? State.MONITOR_FAILED
                : !lane.direct.isEmpty() ? State.PUBLIC_DIRECT
                : lane.server == null ? State.STUN_NOT_CONFIGURED : null;
        if (inactive != null) return view(lane, inactive, null);
        try {
            if (lane.monitor == null) {
                lane.epoch = Math.incrementExact(lane.epoch);
                lane.monitor = Objects.requireNonNull(factory.open(lane.server));
            }
            long sampledAt = nanoTime.getAsLong(); // Conservative deadline: sampled before the native read.
            Optional<Sample> value = lane.monitor.read();
            if (value.isEmpty()) { setFresh(lane, null); return view(lane, State.STUN_PENDING, null); }
            Sample sample = value.get();
            if (!sample.server().equals(lane.server) || sample.mapped() != null
                    && Family.of(sample.mapped().getAddress()) != family) throw new IllegalStateException("STUN source/family mismatch");
            long age = sample.lastSuccessAge().map(EndpointConnectivityController::saturatedNanos).orElse(Long.MAX_VALUE);
            boolean observed = sample.lastSuccessAge().isPresent() && sample.mapped() != null
                    && sample.successfulResponses() > 0 && sample.mappingRevision() > 0;
            long readElapsed = Math.max(0, nanoTime.getAsLong() - sampledAt);
            boolean fresh = observed && age < maxAgeNanos && readElapsed < maxAgeNanos - age;
            boolean eligible = observed && EndpointAddress.scope(sample.mapped().getAddress()) == EndpointAddress.Scope.PUBLIC;
            long remaining = fresh ? maxAgeNanos - age : 0;
            Observation observation = new Observation(sample.server(), sample.mapped(), sample.state(), lane.epoch,
                    sample.mappingRevision(), sample.successfulResponses(), sample.failedTransactions(),
                    observed ? age / 1_000_000 : -1, sampledAt + remaining);
            setFresh(lane, fresh && eligible ? sample.mapped() : null);
            return view(lane, !observed ? (sample.state() == TransactionState.FAILED ? State.STUN_FAILED : State.STUN_PENDING) : !fresh ? State.STUN_STALE
                    : !eligible ? State.STUN_INELIGIBLE : State.STUN_FRESH, observation);
        } catch (RuntimeException failure) {
            lane.failed = true;
            // A failed close retains its handle for explicit close/replacement retry. It must
            // neither leave a fresh endpoint visible nor allocate a second monitor on later reads.
            try { stop(lane); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            return view(lane, State.MONITOR_FAILED, null);
        }
    }

    private FamilySnapshot view(Lane lane, State state, Observation observation) {
        return new FamilySnapshot(state, closed ? List.of() : lane.direct,
                Optional.ofNullable(observation), Optional.ofNullable(lane.fresh));
    }

    private void setFresh(Lane lane, InetSocketAddress fresh) {
        if (!Objects.equals(lane.fresh, fresh)) {
            candidateRevision = Math.incrementExact(candidateRevision);
            lane.fresh = fresh;
        }
    }

    private void stop(Lane lane) {
        setFresh(lane, null);
        Monitor monitor = lane.monitor;
        if (monitor != null) {
            try { monitor.close(); } catch (RuntimeException failure) { lane.failed = true; throw failure; }
        }
        lane.monitor = null;
    }

    private static long saturatedNanos(Duration duration) {
        try { return duration.toNanos(); } catch (ArithmeticException tooOld) { return Long.MAX_VALUE; }
    }

    private static void checkServer(Family family, InetSocketAddress server) {
        Objects.requireNonNull(family);
        EndpointSelection.requireEndpoint(server);
        if (Family.of(server.getAddress()) != family || server.getAddress().isAnyLocalAddress()
                || server.getAddress().isMulticastAddress()) throw new IllegalArgumentException("Numeric same-family STUN server required");
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Connectivity controller closed"); }

    @Override
    public synchronized void close() {
        if (closed && lanes.values().stream().allMatch(lane -> lane.monitor == null)) return;
        if (!closed) candidateRevision = Math.incrementExact(candidateRevision);
        closed = true;
        RuntimeException failure = null;
        for (Lane lane : lanes.values()) {
            try { stop(lane); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
