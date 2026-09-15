package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;

/** Serialized same-mux observations and advertisement material; never a reachability verdict. */
public final class MaintainedCandidatePublisher implements AutoCloseable {
    public record Publication(NativeCandidateSnapshot candidates, ProviderTransport.CandidateLeaseSnapshot leases) { }
    private final EndpointSelection selection;
    private final EndpointConnectivityController controller;
    private final ObservationLeaseTracker tracker;
    private volatile boolean closed;

    /** Configured endpoints need no controller: they suppress monitor creation, including omitted families. */
    public MaintainedCandidatePublisher(EndpointSelection selection, EndpointConnectivityController controller,
                                       ObservationLeaseTracker tracker) {
        this.selection = Objects.requireNonNull(selection); this.tracker = Objects.requireNonNull(tracker);
        if (selection.configured() && controller != null || !selection.configured() && controller == null)
            throw new IllegalArgumentException("Configured endpoints must suppress the connectivity controller");
        this.controller = controller;
    }

    public synchronized Publication refresh(boolean reflexivePublicationAllowed) {
        requireOpen();
        var candidates = new ArrayList<NativeCandidateSnapshot.Candidate>();
        selection.candidates().forEach(candidate -> candidates.add(new NativeCandidateSnapshot.Candidate(candidate.endpoint(), NativeCandidateSnapshot.Type.HOST)));
        ObservationLeaseTracker.Capture captured = null;
        if (controller != null) {
            // Pending transactions with no successful mapping carry no observation authority.
            var sample = controller.snapshot();
            var lanes = new EnumMap<EndpointSelection.Family, EndpointConnectivityController.FamilySnapshot>(EndpointSelection.Family.class);
            sample.families().forEach((family, lane) -> {
                var observation = lane.observation().filter(value -> value.mapped() != null && value.successfulResponses() > 0 && value.mappingRevision() > 0);
                lanes.put(family, new EndpointConnectivityController.FamilySnapshot(lane.state(), lane.directCheck(), lane.directCheckExpiresAtNanos(),
                        lane.directCandidates(), observation, lane.freshStunEndpoint()));
            });
            try { captured = tracker.capture(new EndpointConnectivityController.Snapshot(sample.candidateRevision(), lanes)); }
            catch (RuntimeException unavailable) { /* Retire reflexive publication; direct endpoints and the native listener survive. */ }
        }
        // Direct endpoints retain every advertised slot. With 32 direct candidates the optional
        // other-family fallback must not turn an otherwise valid profile into an oversized one.
        var owned = reflexivePublicationAllowed && candidates.size() < 32 ? captured : null;
        if (owned != null) for (var observation : owned.observations()) {
            var family = observation.family().equals("ipv4") ? EndpointSelection.Family.IPV4 : EndpointSelection.Family.IPV6;
            // Direct public candidates retain precedence even when their reachability is unknown.
            if (!selection.candidates(family).isEmpty()) throw new IllegalStateException("Direct candidate family cannot publish STUN fallback");
            try {
                candidates.add(new NativeCandidateSnapshot.Candidate(new InetSocketAddress(InetAddress.getByAddress(HexFormat.of().parseHex(observation.addressHex())), observation.port()), NativeCandidateSnapshot.Type.SRFLX));
            } catch (UnknownHostException impossible) { throw new IllegalStateException(impossible); }
        }
        var material = new NativeCandidateSnapshot(candidates);
        var lease = new ProviderTransport.CandidateLeaseSnapshot(material.materialRevision(), owned == null ? List.of() : owned.observations(), () -> {
            requireOpen(); if (owned != null) owned.requireCurrent();
        });
        return new Publication(material, lease);
    }

    public synchronized void controlSynchronized() {
        requireOpen(); if (!tracker.clockValid()) tracker.recoverAfterSuccessfulControlSynchronization();
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Candidate publisher closed"); }
    @Override public synchronized void close() {
        closed = true; tracker.close(); if (controller != null) controller.close();
    }
}
