package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.*;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

/** Local same-mux observations and ordinary profile material; never a reachability verdict. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    public record Publication(NativeCandidateSnapshot candidates,
                              Map<NativeCandidateSnapshot.Candidate, Long> expiries, Runnable current) {
        public Publication { expiries = Map.copyOf(expiries); Objects.requireNonNull(current); }
        public void requireCurrent() { current.run(); }
    }
    private final EndpointSelection selection;
    private final EndpointConnectivityController controller;
    private final ObservationLeaseTracker tracker;
    private final Set<Family> fallback = EnumSet.noneOf(Family.class);
    private volatile boolean closed;
    private Set<Integer> assistedReady = Set.of();

    public MaintainedCandidatePublisher(EndpointSelection selection, EndpointConnectivityController controller,
                                       ObservationLeaseTracker tracker) {
        this.selection = Objects.requireNonNull(selection); this.tracker = Objects.requireNonNull(tracker);
        if (selection.configured() && controller != null || !selection.configured() && controller == null)
            throw new IllegalArgumentException("Configured endpoints suppress the connectivity controller");
        this.controller = controller;
    }

    /** Called on the native event loop, independently of provider requests. */
    public synchronized Publication refresh() {
        requireOpen();
        var candidates = new ArrayList<NativeCandidateSnapshot.Candidate>();
        selection.candidates().stream().filter(candidate -> !fallback.contains(Family.of(candidate.endpoint().getAddress())))
                .forEach(candidate -> candidates.add(new NativeCandidateSnapshot.Candidate(candidate.endpoint(), NativeCandidateSnapshot.Type.HOST)));
        ObservationLeaseTracker.Capture captured = null;
        var ready = new HashSet<Integer>();
        if (controller == null) selection.socketFamilies().forEach(family -> ready.add(family == Family.IPV4 ? 4 : 6));
        if (controller != null) {
            // Recovery retains replay high-water marks: an old success cannot acquire a new expiry.
            if (!tracker.clockValid()) tracker.recoverClockForFutureObservations();
            var sample = controller.snapshot();
            var lanes = new EnumMap<Family, EndpointConnectivityController.FamilySnapshot>(Family.class);
            sample.families().forEach((family, lane) -> {
                switch (lane.state()) {
                    case CONFIGURED, DIRECT_CHECK_SUCCEEDED, STUN_NOT_CONFIGURED, STUN_FAILED,
                         STUN_FRESH, STUN_INELIGIBLE, STUN_STALE, MONITOR_FAILED -> ready.add(family == Family.IPV4 ? 4 : 6);
                    case AWAITING_DIRECT_CHECK -> { if (!controller.canAttemptStun(family)) ready.add(family == Family.IPV4 ? 4 : 6); }
                    default -> { } // In particular, STUN_PENDING cannot promote an empty gathering profile.
                }
                var observation = lane.observation().filter(value -> value.mapped() != null
                        && value.successfulResponses() > 0 && value.mappingRevision() > 0);
                lanes.put(family, new EndpointConnectivityController.FamilySnapshot(lane.state(), lane.directCheck(),
                        lane.directCheckExpiresAtNanos(), lane.directCandidates(), observation, lane.freshStunEndpoint()));
            });
            try { captured = tracker.capture(new EndpointConnectivityController.Snapshot(sample.candidateRevision(), lanes)); }
            catch (RuntimeException unavailable) { /* Withdraw reflexive endpoints; the listener survives. */ }
        }
        var expiries = new HashMap<NativeCandidateSnapshot.Candidate, Long>();
        var owned = candidates.size() < 32 && captured != null && !captured.observations().isEmpty() ? captured : null;
        if (owned != null) for (var observation : owned.observations()) {
            var family = observation.family().equals("ipv4") ? Family.IPV4 : Family.IPV6;
            if (!selection.candidates(family).isEmpty() && !fallback.contains(family))
                throw new IllegalStateException("Direct family cannot publish unsolicited STUN fallback");
            if (candidates.size() == 32) break;
            try {
                var endpoint = new InetSocketAddress(InetAddress.getByAddress(HexFormat.of().parseHex(observation.addressHex())), observation.port());
                var candidate = new NativeCandidateSnapshot.Candidate(endpoint, NativeCandidateSnapshot.Type.SRFLX);
                candidates.add(candidate); expiries.put(candidate, observation.expiresAt());
            } catch (UnknownHostException impossible) { throw new IllegalStateException(impossible); }
        }
        assistedReady = Set.copyOf(ready);
        return new Publication(new NativeCandidateSnapshot(candidates), expiries, () -> {
            requireOpen(); if (owned != null) owned.requireCurrent();
        });
    }

    public synchronized Set<Integer> assistedFallbackReadyFamilies() { return closed ? Set.of() : assistedReady; }

    /** Transport fences revision, time and the original asynchronous delivery window. */
    public synchronized void reportDirectChecks(List<ConnectivityCheck> checks, long nowMillis) {
        requireOpen();
        if (controller == null) return;
        for (Family family : Family.values()) {
            if (fallback.contains(family) || selection.candidates(family).isEmpty()) continue;
            var fresh = checks.stream().filter(check -> check.family() == (family == Family.IPV4 ? 4 : 6)
                    && check.checkedAt() <= nowMillis && check.expiresAt() > nowMillis).toList();
            var positive = fresh.stream().filter(check -> check.outcome() == ConnectivityOutcome.ESTABLISHED).toList();
            var negative = fresh.stream().filter(check -> check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED).toList();
            var selected = !positive.isEmpty() ? positive : negative;
            if (selected.isEmpty()) continue;
            if (positive.isEmpty() && !controller.canAttemptStun(family)) continue;
            long expiresAt = positive.isEmpty()
                    ? selected.stream().mapToLong(ConnectivityCheck::expiresAt).min().orElseThrow()
                    : selected.stream().mapToLong(ConnectivityCheck::expiresAt).max().orElseThrow();
            var token = controller.beginDirectCheck(family, Duration.ofMillis(expiresAt - nowMillis));
            boolean failed = positive.isEmpty();
            if (controller.completeDirectCheck(token, failed ? EndpointConnectivityController.CheckOutcome.FAILED
                    : EndpointConnectivityController.CheckOutcome.SUCCEEDED) && failed) fallback.add(family);
        }
    }

    /** Called by the listener whenever its semantic candidate revision advances, including withdrawal/ABA. */
    public synchronized void materialChanged() {
        requireOpen();
        if (controller != null) controller.invalidateDirectChecks();
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Candidate publisher closed"); }
    @Override public synchronized void close() {
        closed = true; tracker.close(); if (controller != null) controller.close();
    }
}
