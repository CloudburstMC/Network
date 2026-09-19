/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import org.cloudburstmc.netty.signaling.admission.NativeHostIdentity;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Explicit diagnostic consumer; never constructs a player child, principal, CPK verifier or pipeline event. */
public final class NativeDiagnosticHostGate implements AutoCloseable {
    public record Result(Context context, String keyId, String attemptId, String offerDigestHex, String clientFingerprintHex, long expiresAt,
                         DiagnosticHostPolicy.Endpoint target, boolean success, String reason,
                         InetSocketAddress selectedLocal, InetSocketAddress selectedRemote,
                         int sentFrames, int sentBytes, int receivedFrames, int receivedBytes, long completedAt,
                         boolean cleanupComplete) { }
    public record Stats(int active, int pending, int retainedAttempts, int liveNativePeers, long rejected, long droppedResults) { }
    private static final class Session {
        final VerifiedDiagnosticAdmission admission;
        final Key key;
        final InetSocketAddress remote;
        final long deadlineNanos, handshakeDeadlineNanos;
        final DiagnosticChannels channels = new DiagnosticChannels();
        Runnable requireCurrent;
        PeerConnection peer;
        DiagnosticExchange exchange;
        boolean settled, created, wantsClose, complete;
        volatile boolean closing;
        String reason = "incomplete";
        String phase = "channels";
        long completeNanos;
        InetSocketAddress selectedLocal, selectedRemote, gatheredLocal;
        Session(VerifiedDiagnosticAdmission admission, Key key, InetSocketAddress remote, long deadlineNanos, long handshakeDeadlineNanos) {
            this.admission = admission; this.key = key; this.remote = remote; this.deadlineNanos = deadlineNanos;
            this.handshakeDeadlineNanos = handshakeDeadlineNanos;
        }
    }
    private final NativeHostIdentity identity;
    private final InetSocketAddress listenerAddress;
    private final Clock clock;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Long> used = new HashMap<>();
    private final Set<PeerConnection> uncleaned = new HashSet<>();
    private final ArrayBlockingQueue<Result> results = new ArrayBlockingQueue<>(32);
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private DiagnosticHostPolicy policy;
    private long rejected, droppedResults, anchorMillis, anchorNanos, lastNanos;
    private boolean anchored, clockFailed, closed;
    private Throwable closeFailure;

    public NativeDiagnosticHostGate(NativeHostIdentity identity, InetSocketAddress listenerAddress, DiagnosticHostPolicy policy) { this(identity, listenerAddress, policy, Clock.system()); }
    NativeDiagnosticHostGate(NativeHostIdentity identity, InetSocketAddress listenerAddress, DiagnosticHostPolicy policy, Clock clock) {
        this.identity = Objects.requireNonNull(identity); this.policy = Objects.requireNonNull(policy); this.clock = Objects.requireNonNull(clock);
        if (listenerAddress.isUnresolved() || listenerAddress.getPort() == 0) throw invalid();
        this.listenerAddress = listenerAddress;
        if (observe() >= policy.expiresAt()) throw invalid();
    }
    /** Trusted local configuration only. Does not renew any admitted attempt or its original deadline. */
    public synchronized void replacePolicy(DiagnosticHostPolicy replacement) {
        Objects.requireNonNull(replacement);
        if (closed || !replacement.context().hostId().equals(policy.context().hostId()) ||
                !replacement.context().providerOrigin().equals(policy.context().providerOrigin()) || observe() >= replacement.expiresAt()) throw invalid();
        policy = replacement;
        for (Session session : new ArrayList<>(sessions.values())) if (!authorized(session, observe())) stop(session, "authority_changed");
    }
    public synchronized Stats stats() {
        int pending = 0, nativePeers = uncleaned.size();
        for (Session session : sessions.values()) { if (!session.complete) pending++; if (session.created) nativePeers++; }
        return new Stats(sessions.size(), pending, used.size(), nativePeers, rejected, droppedResults);
    }
    public synchronized Throwable failure() { return closeFailure; }
    public List<Result> pollResults() { return pollResults(32); }
    public List<Result> pollResults(int maximum) {
        if (maximum < 0 || maximum > 32) throw new IllegalArgumentException("Diagnostic result poll bound");
        List<Result> out = new ArrayList<>(maximum); results.drainTo(out, maximum); return List.copyOf(out);
    }
    /** Remove only endpoints that ceased to belong to this listener's advertised snapshot. Preserve replay history. */
    public synchronized void retainEndpoints(Set<DiagnosticHostPolicy.Endpoint> retained) {
        Objects.requireNonNull(retained);
        var deadlines = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
        policy.endpointExpiries().forEach((endpoint, expiry) -> { if (retained.contains(endpoint)) deadlines.put(endpoint, expiry); });
        policy = new DiagnosticHostPolicy(policy.context(), policy.keys(), deadlines.keySet(), policy.expiresAt(), deadlines);
        for (Session session : new ArrayList<>(sessions.values())) if (!authorized(session, observe())) stop(session, "authority_changed");
    }
    public CompletionStage<Void> termination() { return termination.minimalCompletionStage(); }

    /** Same-listener hook. Native code verifies the first STUN integrity before this acceptance creates a peer. */
    public synchronized IceUdpMuxListener.Acceptance admit(IceUdpMuxListener.Request request) {
        long now = observe(), nanos = lastNanos;
        prune(now);
        if (closed || closeFailure != null || clockFailed || now >= policy.expiresAt() || sessions.size() >= 4 || used.size() >= 16 ||
                request.localUfrag().length() < 8 || !request.localUfrag().startsWith("NXD1")) { rejected++; return null; }
        Key key = policy.key(request.localUfrag().substring(4, 8));
        if (key == null) { rejected++; return null; }
        VerifiedDiagnosticAdmission admission = open(policy.context(), key, request.localUfrag(), request.remoteUfrag(), policy.expiresAt(), clock);
        if (admission == null) { rejected++; return null; }
        try {
            Claims claims = admission.claims();
            InetSocketAddress remote = new InetSocketAddress(EndpointAddress.parse(request.remoteAddress()), request.remotePort());
            int family = remote.getAddress() instanceof Inet6Address ? 6 : 4;
            if (claims.profile() != PROFILE || family != claims.family() || claims.expiresAt() > policy.endpointExpiry(DiagnosticHostPolicy.Endpoint.from(claims)) ||
                    used.containsKey(claims.attemptIdHex()) || sessions.values().stream().anyMatch(s -> s.remote.equals(remote)) || !admission.usable()) throw invalid();
            long remaining = claims.expiresAt() - now;
            if (remaining < 1 || remaining > MAX_ATTEMPT_MILLIS) throw invalid();
            Session session = new Session(admission, key, remote, nanos + remaining * 1_000_000L,
                nanos + Math.min(remaining, MAX_HANDSHAKE_MILLIS) * 1_000_000L);
            sessions.put(claims.attemptIdHex(), session); used.put(claims.attemptIdHex(), claims.expiresAt());
            request.completion().whenComplete((peer, failure) -> settled(session, failure));
            return IceUdpMuxListener.Acceptance.builder(remoteDescription(admission, remote), admission.credentials().icePwd())
                .configuration(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withMtu(1248).withMaxMessageSize(MAX_MESSAGE_SIZE))
                .identity(new DtlsIdentity(identity.certificate(), identity.privateKey()))
                .expiresAt(Instant.ofEpochMilli(claims.expiresAt())).initialize(peer -> initialize(session, peer)).build();
        } catch (RuntimeException | UnknownHostException invalid) { admission.close(); rejected++; return null; }
    }
    /** Authenticated WebSocket path only; never accepts a profile2 permit from an unknown inbound packet. */
    public synchronized String assist(AssistedJoin join, Runnable requireCurrent) {
        Objects.requireNonNull(requireCurrent); requireCurrent.run();
        long now = observe(), nanos = lastNanos;
        prune(now);
        if (!join.diagnostic() || closed || closeFailure != null || clockFailed || now >= policy.expiresAt()
                || sessions.size() >= 4 || used.size() >= 16 || !join.hostFingerprint().equalsIgnoreCase(identity.fingerprint())
                || !join.instanceId().equals(policy.context().hostId()) || join.generation() != policy.context().generation()
                || !join.incarnation().equals(policy.context().incarnation())) throw invalid();
        Key key = policy.key(join.keyId());
        if (key == null) throw invalid();
        String remoteUfrag = AssistedJoin.parseOffer(join.offer()).ufrag();
        VerifiedDiagnosticAdmission admission = open(policy.context(),key,join.localUfrag(),remoteUfrag,policy.expiresAt(),clock);
        if (admission == null) throw invalid();
        Session session = null;
        try {
            Claims claims = admission.claims();
            var remote = DiagnosticAssertionCodec.candidate(utf8(join.offer()),claims,remoteUfrag);
            var scope = EndpointAddress.scope(remote.getAddress());
            boolean privateIpv4Host = claims.family() == 4 && scope == EndpointAddress.Scope.PRIVATE
                    && join.offer().lines().filter(line -> line.startsWith("a=candidate:")).findFirst().orElseThrow().split(" ")[7].equals("host");
            if (claims.profile() != ASSISTED_PROFILE || !claims.attemptIdHex().equals(join.id()) || claims.expiresAt() != join.expiresAt()
                    || !admission.credentials().icePwd().equals(join.localPassword()) || !admission.verifies(join.diagnosticAssertion())
                    || claims.expiresAt() > policy.endpointExpiry(DiagnosticHostPolicy.Endpoint.from(claims))
                    || used.containsKey(claims.attemptIdHex()) || sessions.values().stream().anyMatch(s -> s.remote.equals(remote))
                    || (scope != EndpointAddress.Scope.PUBLIC && !privateIpv4Host
                    && !(listenerAddress.getAddress().isLoopbackAddress() && scope == EndpointAddress.Scope.LOOPBACK))) throw invalid();
            long remaining = claims.expiresAt() - now;
            if (remaining < 1 || remaining > MAX_ATTEMPT_MILLIS) throw invalid();
            session = new Session(admission,key,remote,nanos+remaining*1_000_000L,
                    nanos+Math.min(remaining,MAX_HANDSHAKE_MILLIS)*1_000_000L);
            session.requireCurrent = requireCurrent;
            sessions.put(claims.attemptIdHex(),session); used.put(claims.attemptIdHex(),claims.expiresAt());
            session.peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT
                    .withBindAddress(listenerAddress.getAddress()).withPortRangeBegin(listenerAddress.getPort()).withPortRangeEnd(listenerAddress.getPort())
                    .withEnableIceUdpMux(true).withIceServers(List.of()).withEnableIceTcp(false)
                    .withDisableAutoNegotiation(true).withMtu(1248).withMaxMessageSize(MAX_MESSAGE_SIZE), Runnable::run,
                    identity.certificate(),identity.privateKey());
            session.created = true; session.settled = true;
            // A private client address is not a diagnostic destination. Wait for an
            // authenticated incoming check to discover its public peer-reflexive tuple.
            String remoteDescription = privateIpv4Host ? String.join("\r\n", join.offer().lines()
                    .filter(line -> !line.startsWith("a=candidate:") && !line.startsWith("a=remote-candidates:")
                            && !line.equals("a=end-of-candidates")).toList()) + "\r\n" : join.offer();
            session.peer.setRemoteDescription(remoteDescription,SessionDescriptionType.OFFER);
            session.peer.setLocalDescription("answer",join.localUfrag(),join.localPassword());
            initialize(session,session.peer);
            if (!authorized(session,observe())) throw invalid();
            return session.peer.localDescription();
        } catch (RuntimeException failure) {
            if (session == null) admission.close();
            else { session.settled = true; stop(session,"assisted_failed"); }
            rejected++; throw failure;
        }
    }
    /** Reuse a proactively prepared diagnostic peer; unknown profile2 attempts remain rejected. */
    public synchronized IceUdpMuxListener.Acceptance reuse(IceUdpMuxListener.Request request) {
        Session session = sessions.values().stream().filter(s -> s.requireCurrent != null
                && s.admission.credentials().localUfrag().equals(request.localUfrag())).findFirst().orElse(null);
        if (session == null || session.peer == null || session.closing || !authorized(session,observe())
                || !session.admission.remoteUfrag().equals(request.remoteUfrag())) return null;
        try {
            if (!assistedRemoteAllowed(session, new InetSocketAddress(EndpointAddress.parse(request.remoteAddress()), request.remotePort()))) return null;
        } catch (UnknownHostException | IllegalArgumentException invalid) { return null; }
        return IceUdpMuxListener.Acceptance.reuse(session.peer,Instant.ofEpochMilli(session.admission.claims().expiresAt()));
    }
    private boolean assistedRemoteAllowed(Session session, InetSocketAddress remote) {
        var scope = EndpointAddress.scope(remote.getAddress());
        return (remote.getAddress() instanceof Inet6Address ? 6 : 4) == session.admission.claims().family()
                && (scope == EndpointAddress.Scope.PUBLIC
                || listenerAddress.getAddress().isLoopbackAddress() && scope == EndpointAddress.Scope.LOOPBACK);
    }
    /** A pending answer cannot outlive its original native attempt while local gathering completes. */
    public synchronized void requireAssistedCurrent(AssistedJoin join) {
        Session session = sessions.get(join.id());
        if (session == null || session.closing || !session.admission.credentials().localUfrag().equals(join.localUfrag())
                || !authorized(session, observe())) throw invalid();
    }
    public synchronized void cancelAssisted(AssistedJoin join) {
        Session session = sessions.get(join.id());
        if (session != null && session.admission.credentials().localUfrag().equals(join.localUfrag())) stop(session, "assisted_answer_failed");
    }
    /** Trusted same-mux gatherer capture, before the signed answer is exposed. */
    public synchronized void assistedAnswerCandidate(AssistedJoin join, InetSocketAddress candidate) {
        requireAssistedCurrent(join);
        Session session = sessions.get(join.id());
        if (candidate.isUnresolved() || candidate.getPort() < 1
                || (candidate.getAddress() instanceof Inet6Address ? 6 : 4) != session.admission.claims().family()) throw invalid();
        session.gatheredLocal = candidate;
    }
    private synchronized void initialize(Session session, PeerConnection peer) {
        session.peer = peer; session.created = true; // Own it before anything that may reject initialization.
        if (!authorized(session, observe()) || session.wantsClose) throw invalid();
        String local = peer.localDescription();
        if (!local.contains("a=fingerprint:" + identity.fingerprint() + "\r\n") ||
                !local.contains("a=ice-ufrag:" + session.admission.credentials().localUfrag() + "\r\n")) throw invalid();
        session.channels.attach(peer, true);
    }

    private synchronized void settled(Session session, Throwable failure) {
        session.settled = true;
        if (failure != null) { session.complete = false; session.reason = "native_acceptance_failed"; session.wantsClose = true; }
        if (session.wantsClose || closed) stop(session, session.reason);
    }
    private boolean authorized(Session session, long now) {
        Claims claims = session.admission.claims();
        // Admission capped this permit by its original policy and endpoint deadlines. A later
        // same-endpoint renewal governs new admissions without shortening this captured permit.
        // Explicit context/key/endpoint withdrawal still retires it immediately.
        try { if (session.requireCurrent != null) session.requireCurrent.run(); }
        catch (RuntimeException withdrawn) { return false; }
        return !closed && closeFailure == null && !clockFailed && session.admission.context().equals(policy.context()) && now < claims.expiresAt() && lastNanos - session.deadlineNanos < 0 &&
            keyEquals(session.key, policy.key(session.key.keyId())) && now >= session.key.notBefore() && now < session.key.retireAt() &&
            policy.endpoints().contains(DiagnosticHostPolicy.Endpoint.from(claims));
    }
    private static boolean keyEquals(Key first, Key second) { return second != null && first.equals(second); }
    /** Called by the existing host maintenance loop; never schedules unbounded per-packet work. */
    public synchronized void tick() {
        long now = observe(); prune(now);
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.closing || session.wantsClose) continue;
            if (!authorized(session, now)) { stop(session, "expired_or_withdrawn"); continue; }
            if (session.channels.protocolFailed()) { stop(session, "invalid_diagnostic_protocol"); continue; }
            if (session.channels.failed()) { stop(session, session.complete ? "pong_sent" : "transport_failed"); continue; }
            if (!session.complete && lastNanos - session.handshakeDeadlineNanos >= 0) { stop(session, "handshake_timeout"); continue; }
            try {
                boolean ready = session.channels.ready();
                if (session.exchange == null && ready) {
                    session.phase = "selected_path";
                    if (!authorized(session, observe())) throw invalid();
                    capture(session); // Validate the actual pinned transport before sending a pong.
                    session.exchange = new DiagnosticExchange(session.admission.claims().attemptIdHex(), true, (index, bytes) -> {
                        if (!authorized(session, observe()) || session.closing) throw invalid();
                        session.channels.send(index, bytes);
                    });
                    session.exchange.start();
                    session.phase = "ping";
                }
                if (session.exchange != null) {
                    byte[] incoming = session.channels.poll();
                    if (incoming != null) {
                        session.exchange.receive(0, incoming);
                        session.complete = true;
                        session.completeNanos = lastNanos;
                    }
                }
                // The client closes as soon as it verifies the pong. Bound hosts whose client disappears.
                if (session.complete && lastNanos - session.completeNanos >= 1_000_000_000L) stop(session, "pong_sent");
            } catch (RuntimeException invalid) { stop(session, "invalid_" + session.phase); }
        }
    }
    private void capture(Session session) {
        CandidatePair pair = session.peer.selectedCandidatePair();
        try {
            InetSocketAddress local = new InetSocketAddress(EndpointAddress.parse(pair.local().getHostString()), pair.local().getPort());
            InetSocketAddress remote = new InetSocketAddress(EndpointAddress.parse(pair.remote().getHostString()), pair.remote().getPort());
            boolean boundLocal = local.getPort() == listenerAddress.getPort()
                    && (listenerAddress.getAddress().isAnyLocalAddress() || listenerAddress.getAddress().equals(local.getAddress()));
            boolean remoteAllowed = session.requireCurrent == null ? remote.equals(session.remote) : assistedRemoteAllowed(session, remote);
            if (!remoteAllowed || !(boundLocal || session.requireCurrent != null && local.equals(session.gatheredLocal)) ||
                    (local.getAddress() instanceof Inet6Address ? 6 : 4) != session.admission.claims().family() ||
                    !"udp".equals(pair.localTransport()) || !"udp".equals(pair.remoteTransport())) throw invalid();
            session.selectedLocal = local; session.selectedRemote = remote;
        } catch (UnknownHostException malformed) { throw invalid(); }
    }
    private void stop(Session session, String reason) {
        if (session.closing) return;
        session.wantsClose = true; session.reason = reason;
        if (!reason.equals("pong_sent")) session.complete = false;
        session.admission.close();
        if (!session.settled) return; // The listener owns any in-progress prepare until completion settles.
        session.closing = true;
        if (session.peer == null) { finished(session, null); return; }
        try {
            session.peer.closeAsync().whenComplete((ignored, failure) -> finished(session, failure));
        } catch (RuntimeException failure) { finished(session, failure); }
    }
    private synchronized void finished(Session session, Throwable failure) {
        if (failure != null) { closeFailure = failure; if (session.peer != null) uncleaned.add(session.peer); }
        sessions.remove(session.admission.claims().attemptIdHex(), session);
        // Forged STUN never allocated a peer, so cannot consume an otherwise usable permit.
        if (!session.created) used.remove(session.admission.claims().attemptIdHex());
        long now = observe();
        boolean success = failure == null && session.complete && !session.channels.protocolFailed() && authorized(session, now);
        if (session.created) {
            DiagnosticExchange exchange = session.exchange;
            Claims claims = session.admission.claims();
            Result result = new Result(session.admission.context(), session.key.keyId(), claims.attemptIdHex(), claims.offerDigestHex(), claims.clientFingerprintHex(), claims.expiresAt(), DiagnosticHostPolicy.Endpoint.from(claims), success,
                failure != null ? "native_cleanup_failed" : session.complete && !success ? "observation_invalidated" : session.reason,
                session.selectedLocal, session.selectedRemote, exchange == null ? 0 : exchange.sentFrames(), exchange == null ? 0 : exchange.sentBytes(),
                session.channels.receivedFrames(), session.channels.receivedBytes(), now, failure == null);
            if (!results.offer(result)) droppedResults++;
        }
        completeTermination();
    }
    private void prune(long now) { used.entrySet().removeIf(entry -> entry.getValue() <= now && !sessions.containsKey(entry.getKey())); }
    private long observe() {
        long wall = clock.wallMillis().getAsLong(), nanos = clock.nanoTime().getAsLong();
        if (clockFailed || wall < 0 || wall > SAFE || anchored && nanos - lastNanos < 0) { clockFailed = true; return SAFE; }
        if (!anchored) { anchored = true; anchorMillis = wall; anchorNanos = nanos; }
        lastNanos = nanos;
        long elapsed = nanos - anchorNanos;
        if (elapsed < 0 || anchorMillis > SAFE - elapsed / 1_000_000L) { clockFailed = true; return SAFE; }
        long effective = anchorMillis + elapsed / 1_000_000L;
        if (wall > effective) { anchorMillis = wall; anchorNanos = nanos; effective = wall; }
        return effective;
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true;
        for (Session session : new ArrayList<>(sessions.values())) stop(session, "host_closed");
        completeTermination();
    }
    private void completeTermination() {
        if (closed && sessions.isEmpty()) { if (closeFailure == null) termination.complete(null); else termination.completeExceptionally(closeFailure); }
    }
    private static String remoteDescription(VerifiedDiagnosticAdmission admission, InetSocketAddress remote) {
        String fingerprint = HexFormat.ofDelimiter(":").withUpperCase().formatHex(unhex(admission.claims().clientFingerprintHex(), 32));
        return "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:actpass\r\na=ice-ufrag:" + admission.remoteUfrag() +
            "\r\na=ice-pwd:" + admission.claims().clientIcePwd() + "\r\na=fingerprint:sha-256 " + fingerprint +
            "\r\na=sctp-port:5000\r\na=max-message-size:262144\r\na=candidate:1 1 UDP 2130706431 " + remote.getAddress().getHostAddress() + " " + remote.getPort() + " typ host\r\na=end-of-candidates\r\n";
    }
}
