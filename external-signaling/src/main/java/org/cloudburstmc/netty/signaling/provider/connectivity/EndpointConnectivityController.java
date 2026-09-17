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
    public enum CheckOutcome { UNKNOWN, SUCCEEDED, FAILED }
    public enum State {
        CONFIGURED, DISABLED_BY_CONFIG, UNSUPPORTED_FAMILY, AWAITING_DIRECT_CHECK, DIRECT_CHECK_SUCCEEDED,
        STUN_STOPPED, STUN_NOT_CONFIGURED, STUN_PENDING, STUN_FAILED, STUN_FRESH, STUN_INELIGIBLE, STUN_STALE, MONITOR_FAILED, CLOSED
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

    /** Opaque correlation token; only the currently outstanding token is accepted, exactly once. */
    public static final class DirectCheck {
        private final Family family;
        private final List<EndpointSelection.Candidate> candidates;
        private final long expiresAtNanos;
        private DirectCheck(Family family, List<EndpointSelection.Candidate> candidates, long expiresAtNanos) {
            this.family = family; this.candidates = candidates; this.expiresAtNanos = expiresAtNanos;
        }
        public Family family() { return family; }
        public List<EndpointSelection.Candidate> candidates() { return candidates; }
        /** Fixed at request creation; accepting a response never extends its original validity. */
        public long expiresAtNanos() { return expiresAtNanos; }
    }

    public record Observation(InetSocketAddress server, InetSocketAddress mapped, TransactionState transactionState,
                              long monitorEpoch, long mappingRevision, long successfulResponses,
                              long failedTransactions, long lastSuccessAgeMillis, long freshUntilNanos) {
        /** Uses the same monotonic clock as this controller; freshness is never a reachability verdict. */
        public boolean freshAt(long nowNanos) { return lastSuccessAgeMillis >= 0 && nowNanos - freshUntilNanos < 0; }
    }
    public record FamilySnapshot(State state, CheckOutcome directCheck, OptionalLong directCheckExpiresAtNanos,
                                 List<EndpointSelection.Candidate> directCandidates,
                                 Optional<Observation> observation, Optional<InetSocketAddress> freshStunEndpoint) {
        public FamilySnapshot { directCandidates = List.copyOf(directCandidates); }
        /** Rechecks a retained report using the controller's monotonic clock. This is not universal reachability. */
        public CheckOutcome directCheckAt(long nowNanos) {
            return directCheckExpiresAtNanos.isPresent() && nowNanos - directCheckExpiresAtNanos.getAsLong() < 0
                    ? directCheck : CheckOutcome.UNKNOWN;
        }
    }
    /** Revision changes only when the available endpoint set changes, not on unchanged STUN refreshes. */
    public record Snapshot(long candidateRevision, Map<Family, FamilySnapshot> families) {
        public Snapshot { families = Map.copyOf(families); }
    }

    private static final class Lane {
        final List<EndpointSelection.Candidate> direct;
        CheckOutcome result = CheckOutcome.UNKNOWN;
        long resultExpiresAtNanos;
        boolean fallbackChosen;
        DirectCheck pendingCheck;
        InetSocketAddress server;
        Monitor monitor;
        long epoch;
        boolean failed;
        boolean stopped;
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
        for (Family family : Family.values()) lanes.put(family, new Lane(selection.candidates(family), numericServers.get(family)));
    }

    public synchronized DirectCheck beginDirectCheck(Family family) {
        return beginDirectCheck(family, Duration.ofSeconds(30));
    }

    /** Bounds both request completion and the resulting report, measured from this call. */
    public synchronized DirectCheck beginDirectCheck(Family family, Duration validity) {
        requireOpen();
        if (validity.isNegative() || validity.isZero() || validity.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Direct-check validity must be positive and at most five minutes");
        }
        Lane lane = lanes.get(Objects.requireNonNull(family));
        if (lane.direct.isEmpty()) throw new IllegalStateException("No direct candidates to check");
        // A new check supersedes old asynchronous work without withdrawing a previous successful result.
        return lane.pendingCheck = new DirectCheck(family, lane.direct, nanoTime.getAsLong() + validity.toNanos());
    }

    public synchronized boolean completeDirectCheck(DirectCheck check, CheckOutcome outcome) {
        Objects.requireNonNull(check); Objects.requireNonNull(outcome);
        Lane lane = lanes.get(check.family);
        if (closed || lane.pendingCheck != check) return false;
        lane.pendingCheck = null;
        long now = nanoTime.getAsLong();
        if (now - check.expiresAtNanos >= 0) return false;
        if (lane.result == CheckOutcome.SUCCEEDED && now - lane.resultExpiresAtNanos < 0) {
            // Regional observations can arrive out of order. A still-fresh success wins until
            // its original deadline; only another success with a later original deadline extends it.
            if (outcome != CheckOutcome.SUCCEEDED) return false;
            if (check.expiresAtNanos - lane.resultExpiresAtNanos > 0)
                lane.resultExpiresAtNanos = check.expiresAtNanos;
            return true;
        }
        lane.result = outcome;
        lane.resultExpiresAtNanos = check.expiresAtNanos;
        if (outcome == CheckOutcome.FAILED) lane.fallbackChosen = true;
        if (outcome == CheckOutcome.SUCCEEDED) {
            lane.fallbackChosen = false;
            stop(lane);
            lane.failed = false;
        }
        return true;
    }

    /** A new native/candidate revision retires reports and outstanding checks, but keeps chosen STUN warm. */
    synchronized void invalidateDirectChecks() {
        requireOpen();
        for (Lane lane : lanes.values()) {
            lane.result = CheckOutcome.UNKNOWN;
            lane.resultExpiresAtNanos = 0;
            lane.pendingCheck = null;
        }
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

    /** A verified failed warm path retires its monitor; later sampling must not restart it. */
    public synchronized void stopStun(Family family) {
        requireOpen();
        Lane lane = lanes.get(family);
        lane.stopped = true;
        stop(lane);
    }

    public synchronized Snapshot snapshot() {
        EnumMap<Family, FamilySnapshot> result = new EnumMap<>(Family.class);
        for (Family family : Family.values()) result.put(family, sample(family, lanes.get(family)));
        return new Snapshot(candidateRevision, result);
    }

    private FamilySnapshot sample(Family family, Lane lane) {
        if (lane.result != CheckOutcome.UNKNOWN && nanoTime.getAsLong() - lane.resultExpiresAtNanos >= 0) {
            // Reporting expiry does not destroy an already selected/maintained NAT mapping.
            lane.result = CheckOutcome.UNKNOWN;
        }
        State inactive = closed ? State.CLOSED : selection.configured()
                ? (lane.direct.isEmpty() ? State.DISABLED_BY_CONFIG : State.CONFIGURED)
                : !selection.socketFamilies().contains(family) ? State.UNSUPPORTED_FAMILY : lane.failed ? State.MONITOR_FAILED
                : lane.result == CheckOutcome.SUCCEEDED ? State.DIRECT_CHECK_SUCCEEDED
                : !lane.direct.isEmpty() ? State.AWAITING_DIRECT_CHECK
                : lane.stopped ? State.STUN_STOPPED
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
        CheckOutcome direct = closed ? CheckOutcome.UNKNOWN : lane.result;
        return new FamilySnapshot(state, direct, direct == CheckOutcome.UNKNOWN ? OptionalLong.empty()
                : OptionalLong.of(lane.resultExpiresAtNanos), closed ? List.of() : lane.direct,
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
            lane.pendingCheck = null;
            try { stop(lane); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
