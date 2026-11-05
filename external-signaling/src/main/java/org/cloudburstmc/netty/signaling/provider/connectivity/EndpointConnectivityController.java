package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.util.nethernet.StunObserver;
import tel.schich.libdatachannel.StunBinding;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/**
 * Opt-in same-mux observation lifecycle, one {@link StunObserver} per family that needs STUN.
 * Native monitors send/refresh STUN independently of polling. Call snapshot periodically to observe
 * remaps/expiry; no HTTP, provider profile or reachability is emitted. All operations are serialized,
 * including monitor close/replacement. The caller must stop this owner before its listener and must
 * not use an old snapshot beyond its monotonic freshness deadline.
 */
public final class EndpointConnectivityController implements AutoCloseable {
    public enum State {
        CONFIGURED,
        DISABLED_BY_CONFIG,
        UNSUPPORTED_FAMILY,
        PUBLIC_DIRECT,
        STUN_NOT_CONFIGURED,
        STUN_PENDING,
        STUN_FAILED,
        STUN_FRESH,
        STUN_INELIGIBLE,
        STUN_STALE,
        MONITOR_FAILED,
        CLOSED
    }

    public record Observation(
            InetSocketAddress server,
            InetSocketAddress mapped,
            StunBinding.State transactionState,
            long monitorEpoch,
            long mappingRevision,
            long successfulResponses,
            long failedTransactions,
            long lastSuccessAgeMillis,
            long freshUntilNanos) {
        /** Uses the same monotonic clock as this controller; freshness is never a reachability verdict. */
        public boolean freshAt(long nowNanos) {
            return lastSuccessAgeMillis >= 0 && nowNanos - freshUntilNanos < 0;
        }
    }

    public record FamilySnapshot(
            State state,
            List<EndpointSelection.Candidate> directCandidates,
            Optional<Observation> observation,
            Optional<InetSocketAddress> freshStunEndpoint) {
        public FamilySnapshot {
            directCandidates = List.copyOf(directCandidates);
        }
    }

    /** Revision changes only when the available endpoint set changes, not on unchanged STUN refreshes. */
    public record Snapshot(long candidateRevision, Map<Family, FamilySnapshot> families) {
        public Snapshot {
            families = Map.copyOf(families);
        }
    }

    private static final class Lane {
        final List<EndpointSelection.Candidate> direct;
        StunObserver observer;
        InetSocketAddress fresh;

        Lane(List<EndpointSelection.Candidate> direct) {
            this.direct = direct;
        }
    }

    private final EndpointSelection selection;
    private final StunObserver.MonitorFactory factory;
    private final LongSupplier nanoTime;
    private final Duration maxAge;
    private final EnumMap<Family, Lane> lanes = new EnumMap<>(Family.class);
    private long candidateRevision = 1;
    private boolean closed;

    /** The factory must inherit the listener's exact bind address and fixed port. No utility socket. */
    public EndpointConnectivityController(
            EndpointSelection selection,
            Map<Family, InetSocketAddress> numericServers,
            Duration maxObservationAge,
            StunObserver.MonitorFactory factory) {
        this(selection, numericServers, maxObservationAge, factory, System::nanoTime);
    }

    EndpointConnectivityController(
            EndpointSelection selection,
            Map<Family, InetSocketAddress> numericServers,
            Duration maxObservationAge,
            StunObserver.MonitorFactory factory,
            LongSupplier nanoTime) {
        this.selection = Objects.requireNonNull(selection);
        this.factory = Objects.requireNonNull(factory);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        Objects.requireNonNull(numericServers);
        if (maxObservationAge.isNegative()
                || maxObservationAge.isZero()
                || maxObservationAge.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "STUN observation age must be positive and at most five minutes");
        }
        maxAge = maxObservationAge;
        numericServers.forEach(EndpointConnectivityController::checkServer);
        for (Family family : Family.values()) {
            Lane lane =
                    new Lane(
                            selection.configured()
                                    ? selection.candidates(family)
                                    : selection.publicCandidates(family));
            InetSocketAddress server = numericServers.get(family);
            if (server != null) {
                lane.observer = observer(server);
            }
            lanes.put(family, lane);
        }
    }

    /** Explicit bounded DNS/provider rotation seam. A replacement withdraws the old observation immediately. */
    public synchronized void replaceStunServer(Family family, InetSocketAddress numericServer) {
        requireOpen();
        checkServer(family, numericServer);
        Lane lane = lanes.get(family);
        setFresh(lane, null);
        if (lane.observer == null) {
            lane.observer = observer(numericServer);
        } else {
            lane.observer.replace(numericServer);
        }
    }

    public synchronized Snapshot snapshot() {
        EnumMap<Family, FamilySnapshot> result = new EnumMap<>(Family.class);
        List<StunObserver> polled = new ArrayList<>();
        for (Family family : Family.values()) {
            Lane lane = lanes.get(family);
            State inactive = inactive(family, lane);
            if (inactive != null) {
                result.put(family, view(lane, inactive, null));
            } else {
                result.put(family, sample(lane));
                polled.add(lane.observer);
            }
        }
        // A family that STUN cannot serve only matters while the other has no public mapping either
        boolean covered = lanes.values().stream().anyMatch(lane -> lane.fresh != null);
        for (StunObserver observer : polled) {
            observer.report(covered);
        }
        return new Snapshot(candidateRevision, result);
    }

    /** Why a family has no observation to sample, or null when it does. */
    private State inactive(Family family, Lane lane) {
        if (closed) {
            return State.CLOSED;
        }
        if (selection.configured()) {
            return lane.direct.isEmpty() ? State.DISABLED_BY_CONFIG : State.CONFIGURED;
        }
        if (!selection.socketFamilies().contains(family)) {
            return State.UNSUPPORTED_FAMILY;
        }
        if (!lane.direct.isEmpty()) {
            return State.PUBLIC_DIRECT;
        }
        if (lane.observer == null) {
            return State.STUN_NOT_CONFIGURED;
        }
        return null;
    }

    private FamilySnapshot sample(Lane lane) {
        StunObserver observer = lane.observer;
        observer.poll();
        if (observer.failed()) {
            setFresh(lane, null);
            return view(lane, State.MONITOR_FAILED, null);
        }
        // A numeric server has one address, so one reading
        List<StunObserver.Observed> observed = observer.observed();
        if (observed.isEmpty()) {
            setFresh(lane, null);
            return view(lane, State.STUN_PENDING, null);
        }
        StunObserver.Observed latest = observed.get(0);
        StunObserver.Reading reading = latest.reading();
        boolean answered = reading.answered();
        Observation observation =
                new Observation(
                        reading.server(),
                        reading.mapped(),
                        reading.state(),
                        observer.epoch(),
                        reading.mappingRevision(),
                        reading.successfulResponses(),
                        reading.failedTransactions(),
                        answered ? ageMillis(reading) : -1,
                        latest.freshUntilNanos());
        setFresh(lane, latest.fresh() && latest.usable() ? reading.mapped() : null);
        return view(lane, state(answered, reading.state(), latest.fresh(), latest.usable()), observation);
    }

    private static State state(
            boolean answered, StunBinding.State transaction, boolean fresh, boolean usable) {
        if (!answered) {
            return transaction == StunBinding.State.FAILED ? State.STUN_FAILED : State.STUN_PENDING;
        }
        if (!fresh) {
            return State.STUN_STALE;
        }
        return usable ? State.STUN_FRESH : State.STUN_INELIGIBLE;
    }

    private FamilySnapshot view(Lane lane, State state, Observation observation) {
        return new FamilySnapshot(
                state,
                closed ? List.of() : lane.direct,
                Optional.ofNullable(observation),
                Optional.ofNullable(lane.fresh));
    }

    private StunObserver observer(InetSocketAddress server) {
        return new StunObserver(server, maxAge, selection.bind().getPort(), factory, nanoTime);
    }

    private void setFresh(Lane lane, InetSocketAddress fresh) {
        if (!Objects.equals(lane.fresh, fresh)) {
            candidateRevision = Math.incrementExact(candidateRevision);
            lane.fresh = fresh;
        }
    }

    private static long ageMillis(StunObserver.Reading reading) {
        try {
            return reading.lastSuccessAge().orElseThrow().toMillis();
        } catch (ArithmeticException tooOld) {
            return Long.MAX_VALUE;
        }
    }

    private static void checkServer(Family family, InetSocketAddress server) {
        Objects.requireNonNull(family);
        EndpointSelection.requireEndpoint(server);
        if (Family.of(server.getAddress()) != family
                || server.getAddress().isAnyLocalAddress()
                || server.getAddress().isMulticastAddress()) {
            throw new IllegalArgumentException("Numeric same-family STUN server required");
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Connectivity controller closed");
        }
    }

    /** Closing again retries a monitor that failed to close. */
    @Override
    public synchronized void close() {
        if (!closed) {
            candidateRevision = Math.incrementExact(candidateRevision);
        }
        closed = true;
        RuntimeException failure = null;
        for (Lane lane : lanes.values()) {
            setFresh(lane, null);
            if (lane.observer == null) {
                continue;
            }
            try {
                lane.observer.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
