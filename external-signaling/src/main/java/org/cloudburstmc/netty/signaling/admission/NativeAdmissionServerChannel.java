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

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import java.security.MessageDigest;
import java.util.Arrays;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointConnectivityController;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.cloudburstmc.netty.signaling.diagnostic.NativeDiagnosticHostGate;
import io.netty.channel.*;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.*;

import java.net.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed-UDP host. Java decides admission once; native code owns transport packets.
 */
public final class NativeAdmissionServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NativeAdmissionServerChannel.class);

    public record Event(String ticketId, String stage, String reason, long occurredAt, long validationToCreationNanos,
                        InetSocketAddress remoteEndpoint) {
    }

    private static final class Session {
        final AdmissionGate.Reservation reservation;
        final AdmittedNetherNetChildChannel child;
        final PeerConnection peer;
        final long creationNanos = System.nanoTime();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        volatile boolean failed;
        boolean reported;
        boolean closing;

        Session(AdmissionGate.Reservation reservation, AdmittedNetherNetChildChannel child, PeerConnection peer) {
            this.reservation = reservation;
            this.child = child;
            this.peer = peer;
        }
    }

    private final DefaultNetherServerChannelConfig config = new DefaultNetherServerChannelConfig(this);
    private final NativeHostIdentity identity;
    private final boolean allowWildcardBind;
    private final AdmissionGate gate;
    private final AdmissionGate.Limits limits;
    private final AtomicReference<Throwable> nativeCloseFailure = new AtomicReference<>();
    private final AtomicInteger liveNativePeers = new AtomicInteger();
    private final Set<CompletableFuture<Void>> nativeClosures = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> admissions = ConcurrentHashMap.newKeySet();
    private final Map<AdmissionGate.Reservation, Session> sessions = new HashMap<>();
    private record AssistedPeer(PeerConnection peer, AdmissionGate.Reservation reservation, long deadlineNanos, Runnable requireCurrent) { }
    private final Map<String, AssistedPeer> assisted = new HashMap<>();
    private record AssistedAnswer(AssistedJoin join, String description, long deadlineNanos, Runnable requireCurrent,
                                  Runnable cancelPeer, java.util.function.Consumer<InetSocketAddress> capture,
                                  StunUdpMuxMonitor monitor, int family, CompletableFuture<String> result) { }
    private final List<AssistedAnswer> gatheringAnswers = new ArrayList<>();
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(256);
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong creations = new AtomicLong();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile boolean open = true;
    private volatile InetSocketAddress address;
    private volatile IceUdpMuxListener mux;
    private EndpointConnectivityController connectivity;
    private volatile NativeDiagnosticHostGate diagnostics;
    private ScheduledFuture<?> maintenance;

    public NativeAdmissionServerChannel(NativeHostIdentity identity, AdmissionValidator validator,
                                        AdmissionGate.Limits limits) {
        this(identity, validator, limits, false);
    }

    /**
     * Wildcard binding requires a separately validated concrete advertised candidate.
     */
    public NativeAdmissionServerChannel(NativeHostIdentity identity, AdmissionValidator validator,
                                        AdmissionGate.Limits limits, boolean allowWildcardBind) {
        this.identity = Objects.requireNonNull(identity);
        this.limits = Objects.requireNonNull(limits);
        gate = new AdmissionGate(limits, validator);
        this.allowWildcardBind = allowWildcardBind;
    }

    @Override
    protected void doBind(SocketAddress socketAddress) throws Exception {
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress) || inetSocketAddress.isUnresolved() || inetSocketAddress.getPort() == 0 || (
                !allowWildcardBind && inetSocketAddress.getAddress().isAnyLocalAddress())) {
            throw new IllegalArgumentException("Resolved explicit interface address and fixed UDP port required");
        }

        address = inetSocketAddress;
        mux = new IceUdpMuxListener(inetSocketAddress.getAddress(), inetSocketAddress.getPort(), Math.min(limits.pending(), 4096),
                Duration.ofMillis(Math.min(limits.handshakeMillis(), 30_000)), eventLoop(), this::admit);
        maintenance = eventLoop().scheduleWithFixedDelay(this::maintain, 100, 100, TimeUnit.MILLISECONDS);
    }

    private CompletionStage<IceUdpMuxListener.Acceptance> admit(IceUdpMuxListener.Request request) throws Exception {
        if (!isOpen() || nativeCloseFailure.get() != null) {
            return CompletableFuture.completedFuture(null);
        }

        AssistedPeer known = assisted.get(request.localUfrag());
        if (known != null) {
            AdmissionContext admission = gate.admission(known.reservation());
            try {
                known.requireCurrent().run();
                if (admission == null || System.nanoTime() >= known.deadlineNanos()
                        || System.currentTimeMillis() >= admission.expiresAt()
                        || !admission.remoteUfrag().equals(request.remoteUfrag())) return CompletableFuture.completedFuture(null);
                return CompletableFuture.completedFuture(IceUdpMuxListener.Acceptance.reuse(known.peer(), Instant.ofEpochMilli(admission.expiresAt())));
            } catch (RuntimeException stale) { return CompletableFuture.completedFuture(null); }
        }
        NativeDiagnosticHostGate diagnostic = diagnostics;
        if (diagnostic != null) {
            if (request.localUfrag().startsWith("NXD1")) {
                var prepared = diagnostic.reuse(request);
                if (prepared != null) return CompletableFuture.completedFuture(prepared);
            }
            var players = gate.stats();
            var checks = diagnostic.stats();
            if (players.sessions() + checks.active() >= limits.sessions() || players.pending() + checks.pending() >= limits.pending()) {
                return CompletableFuture.completedFuture(null);
            }
        }
        // This purpose is quarantined even when disabled or malformed. It never falls through to a player validator.
        if (request.localUfrag().startsWith("NXD1")) {
            return CompletableFuture.completedFuture(diagnostic == null ? null : diagnostic.admit(request));
        }

        byte[] ip = NetUtil.createByteArrayFromIpAddressString(request.remoteAddress());
        if (ip == null) {
            return CompletableFuture.completedFuture(null);
        }

        AdmissionRequest metadata = new AdmissionRequest(request.localUfrag(), request.remoteUfrag(),
                new InetSocketAddress(InetAddress.getByAddress(ip), request.remotePort()));
        AdmissionGate.Reservation reservation = gate.reserve(metadata, System.currentTimeMillis(), System.nanoTime());
        if (reservation == null) {
            return CompletableFuture.completedFuture(null);
        }

        AdmissionContext a = gate.admission(reservation);
        if (a == null) {
            gate.finish(reservation);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> settled = new CompletableFuture<>();
        admissions.add(settled);
        request.completion().whenComplete((peer, failure) -> {
            try {
                eventLoop().execute(() -> {
                    if (failure != null) {
                        gate.invalidNativeRequest();
                        Session session = sessions.get(reservation);
                        if (session == null) {
                            gate.finish(reservation);
                        } else {
                            finish(reservation, "native_acceptance_failed");
                        }
                    }

                    settled.complete(null);
                    admissions.remove(settled);
                });
            } catch (RejectedExecutionException stopped) {
                nativeCloseFailure.compareAndSet(null, stopped);
                gate.drain();
                settled.completeExceptionally(stopped);
            }
        });
        return CompletableFuture.completedFuture(new IceUdpMuxListener.Acceptance(
                PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                        .withMaxMessageSize(NetherNetFrameDecoder.MESSAGE_LIMIT),
                a.remoteDescription(), a.localPassword(), identity.certificate(), identity.privateKey(), null,
                Runnable::run, peer -> initialize(reservation, a, peer), Instant.ofEpochMilli(a.expiresAt())));
    }

    /** Same owned listener, quarantined diagnostic policy; no player reservation or child. */
    public CompletionStage<String> assistDiagnostic(AssistedJoin join, Runnable requireCurrent) {
        return assistDiagnostic(join, requireCurrent, Map.of(), Map.of(address.getAddress() instanceof Inet6Address ? 6 : 4, address));
    }
    public CompletionStage<String> assistDiagnostic(AssistedJoin join, Runnable requireCurrent,
                                                    Map<Integer, InetSocketAddress> stunServers, Map<Integer, InetSocketAddress> publicCandidates) {
        var servers = Map.copyOf(stunServers); var candidates = Map.copyOf(publicCandidates);
        CompletableFuture<String> result = new CompletableFuture<>();
        eventLoop().execute(() -> {
            try {
                requireCurrent.run();
                NativeDiagnosticHostGate gate = diagnostics;
                if (!isServing() || gate == null) throw new IllegalStateException("Diagnostic assistance unavailable");
                var players = this.gate.stats(); var checks = gate.stats();
                if (players.sessions()+checks.active() >= limits.sessions() || players.pending()+checks.pending() >= limits.pending())
                    throw new IllegalStateException("Diagnostic capacity unavailable");
                String description = gate.assist(join,requireCurrent);
                gatherAssistedAnswer(join, description, () -> { requireCurrent.run(); gate.requireAssistedCurrent(join); },
                        () -> gate.cancelAssisted(join), candidate -> gate.assistedAnswerCandidate(join,candidate), servers, candidates, result);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.minimalCompletionStage();
    }

    /** Precreate a peer and start outbound ICE, without waiting for any incoming player packet. */
    CompletionStage<String> assist(AssistedJoin join, Runnable requireCurrent) {
        return assist(join, requireCurrent, Map.of(), Map.of(address.getAddress() instanceof Inet6Address ? 6 : 4, address));
    }
    CompletionStage<String> assist(AssistedJoin join, Runnable requireCurrent,
                                   Map<Integer, InetSocketAddress> stunServers, Map<Integer, InetSocketAddress> publicCandidates) {
        var servers = Map.copyOf(stunServers); var candidates = Map.copyOf(publicCandidates);
        long remaining = join.expiresAt() - System.currentTimeMillis();
        if (remaining <= 0 || remaining > 30_000) return CompletableFuture.failedFuture(new IllegalArgumentException("Assisted deadline"));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining);
        CompletableFuture<String> result = new CompletableFuture<>();
        eventLoop().execute(() -> {
            AdmissionGate.Reservation reservation = null;
            PeerConnection peer = null;
            try {
                requireCurrent.run();
                if (join.diagnostic()) throw new IllegalArgumentException("Diagnostic purpose requires diagnostic gate");
                if (!isServing() || System.nanoTime() >= deadline || !identity.fingerprint().equalsIgnoreCase(join.hostFingerprint())
                        || assisted.size() >= 32 || assisted.containsKey(join.localUfrag())) throw new IllegalStateException("Assisted admission unavailable");
                var offer = AssistedJoin.parseOffer(join.offer());
                byte[] cpk = AssistedJoin.canonicalCpk(join.cpk());
                String identityHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cpk));
                IdentityKeyVerifier verifier = new IdentityKeyVerifier() {
                    protected boolean usable() {
                        try { requireCurrent.run(); return System.nanoTime() < deadline && System.currentTimeMillis() < join.expiresAt(); }
                        catch (RuntimeException stale) { return false; }
                    }
                    protected boolean matches(byte[] actual) { return MessageDigest.isEqual(cpk, actual); }
                    protected void release() { Arrays.fill(cpk, (byte)0); }
                };
                AdmissionContext admission = new AdmissionContext() {
                    public String tokenId() { return join.id(); }
                    public String localUfrag() { return join.localUfrag(); }
                    public String localPassword() { return join.localPassword(); }
                    public String remoteUfrag() { return offer.ufrag(); }
                    public String remoteDescription() { return join.offer(); }
                    public long expiresAt() { return join.expiresAt(); }
                    public String networkId() { return join.networkId(); }
                    public String identityBindingHex() { return identityHash; }
                    public String keyId() { return join.keyId(); }
                    public IdentityKeyVerifier identityVerifier() { return verifier; }
                };
                reservation = gate.reserveAuthenticated(admission, offer.candidates().get(0), System.currentTimeMillis(), System.nanoTime());
                if (reservation == null) throw new IllegalStateException("Assisted capacity or duplicate join");
                peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(address.getAddress())
                        .withEnableIceUdpMux(true).withPortRangeBegin(address.getPort()).withPortRangeEnd(address.getPort())
                        .withDisableAutoNegotiation(true).withMaxMessageSize(NetherNetFrameDecoder.MESSAGE_LIMIT), Runnable::run,
                        new tel.schich.libdatachannel.DtlsIdentity(identity.certificate(), identity.privateKey()));
                assisted.put(join.localUfrag(), new AssistedPeer(peer, reservation, deadline, requireCurrent));
                peer.setRemoteDescription(join.offer(), tel.schich.libdatachannel.SessionDescriptionType.OFFER);
                peer.setLocalDescription("answer", join.localUfrag(), join.localPassword());
                initialize(reservation, admission, peer);
                requireCurrent.run();
                if (System.nanoTime() >= deadline || System.currentTimeMillis() >= join.expiresAt()) throw new IllegalStateException("Assisted deadline");
                var ownedReservation = reservation;
                gatherAssistedAnswer(join, peer.localDescription(), () -> {
                    requireCurrent.run();
                    if (System.nanoTime() >= deadline || System.currentTimeMillis() >= join.expiresAt()
                            || !sessions.containsKey(ownedReservation) || gate.admission(ownedReservation) == null)
                        throw new IllegalStateException("Assisted peer retired");
                }, () -> finish(ownedReservation, "assisted_answer_failed"), candidate -> { }, servers, candidates, result);
            } catch (Throwable failure) {
                AssistedPeer owned = assisted.get(join.localUfrag());
                if (owned != null && owned.peer() == peer) assisted.remove(join.localUfrag());
                if (reservation != null) {
                    if (sessions.containsKey(reservation)) finish(reservation, "assisted_failed");
                    else {
                        if (peer != null && !peer.closeAndAwait(Duration.ofSeconds(5))) nativeCloseFailure.compareAndSet(null, failure);
                        gate.finish(reservation);
                    }
                }
                result.completeExceptionally(failure);
            }
        });
        return result.minimalCompletionStage();
    }

    /** One fresh answer candidate, gathered on the gameplay mux only for this owned join. */
    private void gatherAssistedAnswer(AssistedJoin join, String description, Runnable requireCurrent, Runnable cancelPeer,
                                     java.util.function.Consumer<InetSocketAddress> capture,
                                     Map<Integer, InetSocketAddress> servers, Map<Integer, InetSocketAddress> candidates,
                                     CompletableFuture<String> result) {
        StunUdpMuxMonitor monitor = null;
        try {
            requireCurrent.run();
            long started = System.nanoTime(), remaining = join.expiresAt() - System.currentTimeMillis();
            if (remaining <= 0 || gatheringAnswers.size() >= 36 || servers.size() > 2 || candidates.size() > 2)
                throw new IllegalStateException("Assisted gathering unavailable");
            var families = new LinkedHashSet<Integer>();
            AssistedJoin.parseOffer(join.offer()).candidates().forEach(c -> families.add(c.getAddress() instanceof Inet6Address ? 6 : 4));
            for (int family : families) {
                InetSocketAddress candidate = candidates.get(family);
                if (candidate != null) {
                    requireAnswerEndpoint(candidate, family);
                    String answer = assistedAnswer(description, candidate, "host");
                    requireCurrent.run();
                    if (System.currentTimeMillis() >= join.expiresAt()) throw new IllegalStateException("Assisted gathering expired");
                    capture.accept(candidate);
                    result.complete(answer);
                    return;
                }
            }
            int family = families.stream().filter(servers::containsKey).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No eligible assisted discovery endpoint"));
            InetSocketAddress server = servers.get(family);
            requireAnswerEndpoint(server, family);
            monitor = new StunUdpMuxMonitor(address.getAddress(), address.getPort(), server.getAddress().getHostAddress(), server.getPort());
            gatheringAnswers.add(new AssistedAnswer(join, description,
                    started + TimeUnit.MILLISECONDS.toNanos(Math.min(remaining, 15000)),
                    requireCurrent, cancelPeer, capture, monitor, family, result));
        } catch (Throwable failure) {
            failAssistedAnswer(monitor, cancelPeer, result, failure);
        }
    }
    private void closeAssistedMonitor(StunUdpMuxMonitor monitor) {
        if (monitor == null) return;
        try { monitor.close(); }
        catch (RuntimeException failure) { nativeCloseFailure.compareAndSet(null, failure); throw failure; }
    }
    private void failAssistedAnswer(StunUdpMuxMonitor monitor, Runnable cancelPeer, CompletableFuture<String> result, Throwable failure) {
        try { closeAssistedMonitor(monitor); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
        try { cancelPeer.run(); } catch (Throwable cleanup) { nativeCloseFailure.compareAndSet(null, cleanup); if (cleanup != failure) failure.addSuppressed(cleanup); }
        result.completeExceptionally(failure);
    }
    private void requireAnswerEndpoint(InetSocketAddress endpoint, int family) {
        if (endpoint.isUnresolved() || endpoint.getPort() < 1 || (endpoint.getAddress() instanceof Inet6Address ? 6 : 4) != family
                || (EndpointAddress.scope(endpoint.getAddress()) != EndpointAddress.Scope.PUBLIC
                    && !(address.getAddress().isLoopbackAddress() && endpoint.getAddress().isLoopbackAddress())))
            throw new IllegalArgumentException("Assisted discovery requires an eligible numeric endpoint");
    }
    private static String assistedAnswer(String description, InetSocketAddress candidate, String type) {
        return String.join("\r\n", description.lines().filter(line -> !line.startsWith("a=candidate:")
                && !line.startsWith("a=remote-candidates:") && !line.equals("a=end-of-candidates")).toList())
                + "\r\na=candidate:1 1 UDP 2130706431 " + candidate.getAddress().getHostAddress() + " " + candidate.getPort()
                + " typ " + type + "\r\na=end-of-candidates\r\n";
    }
    private void finishAssistedAnswers() {
        for (AssistedAnswer pending : new ArrayList<>(gatheringAnswers)) {
            try {
                pending.requireCurrent().run();
                if (!isOpen() || pending.result().isCancelled() || System.nanoTime() >= pending.deadlineNanos()
                        || System.currentTimeMillis() >= pending.join().expiresAt()) throw new IllegalStateException("Assisted gathering expired");
                var observation = pending.monitor().binding(0);
                if (observation.isEmpty() || observation.get().state() != StunBinding.State.SUCCEEDED) continue;
                var mapping = observation.get();
                InetSocketAddress candidate = new InetSocketAddress(EndpointAddress.parse(mapping.mappedAddress()), mapping.mappedPort());
                requireAnswerEndpoint(candidate, pending.family());
                String answer = assistedAnswer(pending.description(), candidate, "srflx");
                closeAssistedMonitor(pending.monitor());
                gatheringAnswers.remove(pending);
                pending.requireCurrent().run();
                if (System.nanoTime() >= pending.deadlineNanos() || System.currentTimeMillis() >= pending.join().expiresAt())
                    throw new IllegalStateException("Assisted gathering expired");
                pending.capture().accept(candidate);
                pending.result().complete(answer);
            } catch (Throwable failure) {
                gatheringAnswers.remove(pending);
                failAssistedAnswer(pending.monitor(), pending.cancelPeer(), pending.result(), failure);
            }
        }
    }

    private void initialize(AdmissionGate.Reservation reservation, AdmissionContext a, PeerConnection peer) {
        var child = new AdmittedNetherNetChildChannel(this, peer, reservation.tuple(), address);
        var session = new Session(reservation, child, peer);
        creations.incrementAndGet();
        liveNativePeers.incrementAndGet();
        sessions.put(reservation, session);
        nativeClosures.add(session.closed);
        session.closed.whenComplete((ignored, failure) -> {
            if (failure != null) {
                nativeCloseFailure.compareAndSet(null, failure);
                gate.drain();
            } else {
                liveNativePeers.decrementAndGet();
                gate.finish(reservation);
            }
            nativeClosures.remove(session.closed);
        });
        child.nativeTermination().whenComplete((ignored, failure) -> {
            if (failure == null) {
                session.closed.complete(null);
            } else {
                session.closed.completeExceptionally(failure);
            }
        });

        // Keep ownership before checks that may fail, so partial setup is included in teardown.
        if (!isOpen() || gate.admission(reservation) == null || a.expiresAt() <= System.currentTimeMillis()) {
            throw new IllegalStateException("Admission expired or cancelled");
        }

        String local = peer.localDescription();
        if (!local.contains("a=fingerprint:" + identity.fingerprint() + "\r\n") || !local.contains(
                "a=ice-ufrag:" + a.localUfrag() + "\r\n")) {
            throw new IllegalStateException("Native identity does not match published profile");
        }

        TransportIdentityBinding.install(child, a.identityVerifier());
        child.attr(AdmissionPrincipal.KEY)
                .set(new AdmissionPrincipal(a.tokenId(), a.networkId(), a.identityBindingHex(), a.keyId()));
        peer.onStateChange.register((p, state) -> {
            if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) {
                session.failed = true;
            }
        });
        peer.onDataChannel.register((p, dc) -> {
            if (session.failed) {
                return;
            }
            try {
                child.acceptDataChannel(dc);
            } catch (Exception invalidChannel) {
                session.failed = true;
            }
        });

        if (!gate.ready(reservation)) {
            throw new IllegalStateException("Admission cancelled");
        }

        pipeline().fireChannelRead(child);
        pipeline().fireChannelReadComplete();
        if (a instanceof VerifiedAdmission) emit(reservation, "ticket.ice_seen", "token_and_stun_validated", session.creationNanos);
    }

    /**
     * Periodic expiry and session reporting; connection creation is driven by admission completion.
     */
    private void maintain() {
        if (!isOpen()) {
            return;
        }

        try {
            IceUdpMuxListener listener = mux;
            if (listener != null && listener.failure() != null) {
                nativeCloseFailure.compareAndSet(null, listener.failure());
            }

            if (nativeCloseFailure.get() != null) {
                close();
                return;
            }
            if (diagnostics != null) {
                diagnostics.tick();
                if (diagnostics.failure() != null) { nativeCloseFailure.compareAndSet(null, diagnostics.failure()); close(); return; }
            }
            finishAssistedAnswers();

            var warning = gate.pollPendingLimitWarning(System.nanoTime());
            if (warning != null) {
                log.warn("Pending admission limit reached: pending={}, limit={}, rejectedSinceLastWarning={}",
                        warning.pending(), warning.limit(), warning.rejected());
            }

            for (AdmissionGate.Reservation r : gate.sweep(System.currentTimeMillis(), System.nanoTime())) {
                finish(r, "timeout");
            }

            for (Session session : new ArrayList<>(sessions.values())) {
                if (session.failed || !session.child.isOpen()) {
                    finish(session.reservation, "closed");
                    continue;
                }

                if (!session.reported && session.child.isActive()) {
                    if (assisted.values().removeIf(p -> p.reservation() == session.reservation))
                        emit(session.reservation, "ticket.ice_seen", "assisted_ice_dtls_established", session.creationNanos);
                    session.reported = true;
                    gate.connected(session.reservation);
                    emit(session.reservation, "ticket.data_channels_open", "both_channels_open", session.creationNanos,
                            observedRemoteEndpoint(session.peer));
                }
            }
        } catch (Exception failure) {
            pipeline().fireExceptionCaught(failure);
            close();
        }
    }

    private void finish(AdmissionGate.Reservation r, String reason) {
        assisted.values().removeIf(peer -> peer.reservation() == r);
        Session session = sessions.remove(r);
        // An outstanding native prepare owns its reservation until request.completion settles.
        if (session != null) {
            closeChild(session);
            if (!session.reported) {
                emit(r, "ticket.failed", reason, session.creationNanos);
            }
        }
    }

    private static void closeChild(Session session) {
        if (session.closing) {
            return;
        }

        session.closing = true;

        try {
            session.child.close();
        } catch (IllegalStateException unregistered) {
            try {
                session.child.closeUnregistered();
                session.closed.complete(null);
            } catch (Exception failedClose) {
                session.closed.completeExceptionally(failedClose);
            }
        }
    }

    private void emit(AdmissionGate.Reservation r, String stage, String reason, long createdAt) {
        emit(r, stage, reason, createdAt, null);
    }

    private void emit(AdmissionGate.Reservation r, String stage, String reason, long createdAt,
                      InetSocketAddress remoteEndpoint) {
        if (!events.offer(new Event(r.tokenId(), stage, reason, System.currentTimeMillis(),
                Math.max(0, createdAt - r.acceptedNanos()), remoteEndpoint))) {
            droppedEvents.incrementAndGet();
        }
    }

    /** Snapshot the selected transport endpoint, never the admission's initial SDP candidate. */
    private static InetSocketAddress observedRemoteEndpoint(PeerConnection peer) {
        try {
            var remote = peer.remoteAddress();
            if (remote.getPort() < 1 || remote.getPort() > 65535) return null;
            return new InetSocketAddress(org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(remote.getHostString()),
                    remote.getPort());
        } catch (RuntimeException | java.net.UnknownHostException unavailable) {
            // Peer closure can race this optional observation; telemetry must not fail admission.
            return null;
        }
    }

    public List<Event> pollEvents() {
        return pollEvents(256);
    }

    public List<Event> pollEvents(int maximum) {
        if (maximum < 0 || maximum > 256) throw new IllegalArgumentException("Invalid outcome poll bound");
        List<Event> result = new ArrayList<>(maximum);
        events.drainTo(result, maximum);
        return result;
    }

    public AdmissionGate.Stats admissionStats() {
        return gate.stats();
    }

    public int liveNativePeers() {
        NativeDiagnosticHostGate diagnostic = diagnostics;
        return liveNativePeers.get() + (diagnostic == null ? 0 : diagnostic.stats().liveNativePeers());
    }

    public long creationAttempts() {
        return creations.get();
    }

    public long droppedEvents() {
        return droppedEvents.get();
    }

    public long[] nativeStats() {
        IceUdpMuxListener listener = mux;
        if (listener == null) {
            throw new IllegalStateException("Endpoint not bound");
        }
        return listener.stats();
    }

    /**
     * Explicit opt-in after binding. Observations never mutate the provider profile or admission
     * incarnation. The controller polls on demand; native code independently maintains STUN.
     * Closing this channel closes its monitors before releasing the gameplay listener.
     */
    public CompletionStage<EndpointConnectivityController> enableConnectivity(EndpointSelection selection,
            Map<EndpointSelection.Family, InetSocketAddress> numericStunServers, Duration maxObservationAge) {
        // Own caller collections before crossing the event-loop boundary.
        Map<EndpointSelection.Family, InetSocketAddress> servers = Map.copyOf(numericStunServers);
        CompletableFuture<EndpointConnectivityController> result = new CompletableFuture<>();
        try {
            eventLoop().execute(() -> {
                try {
                    if (!isActive() || connectivity != null || !selection.bind().equals(address)) {
                        throw new IllegalStateException("Connectivity requires the same active mux and one controller");
                    }
                    IceUdpMuxListener listener = mux;
                    connectivity = new EndpointConnectivityController(selection, servers, maxObservationAge, server -> {
                        StunUdpMuxMonitor monitor = listener.monitorStun(server.getAddress().getHostAddress(), server.getPort());
                        return new EndpointConnectivityController.Monitor() {
                            @Override public Optional<EndpointConnectivityController.Sample> read() {
                                return monitor.binding(0).map(binding -> {
                                    try {
                                        var mapped = binding.mappedPort() == 0 ? null : new InetSocketAddress(
                                                EndpointAddress.parse(binding.mappedAddress()), binding.mappedPort());
                                        return new EndpointConnectivityController.Sample(new InetSocketAddress(
                                                EndpointAddress.parse(binding.serverAddress()), binding.serverPort()), mapped,
                                                EndpointConnectivityController.TransactionState.valueOf(binding.state().name()),
                                                binding.successfulResponses(), binding.failedTransactions(), binding.mappingRevision(),
                                                binding.lastSuccessAge());
                                    } catch (UnknownHostException invalid) {
                                        throw new IllegalStateException("Native STUN observation is not numeric", invalid);
                                    }
                                });
                            }
                            @Override public void close() { monitor.close(); }
                        };
                    });
                    result.complete(connectivity);
                } catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException unavailable) { result.completeExceptionally(unavailable); }
        return result;
    }

    public NativeHostIdentity identity() {
        return identity;
    }

    /** Explicit opt-in only. No provider field or externally advertised capability is changed. */
    public CompletionStage<NativeDiagnosticHostGate> enableDiagnostics(DiagnosticHostPolicy policy) {
        CompletableFuture<NativeDiagnosticHostGate> result = new CompletableFuture<>();
        try {
            eventLoop().execute(() -> {
                try {
                    if (!isActive() || diagnostics != null) throw new IllegalStateException("Diagnostic gate requires one active host listener");
                    diagnostics = new NativeDiagnosticHostGate(identity, address, policy);
                    result.complete(diagnostics);
                } catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result.minimalCompletionStage();
    }

    /** Local configuration of the same gate retains replay and result history across replacement/withdrawal. */
    NativeDiagnosticHostGate installDiagnostics(DiagnosticHostPolicy policy, NativeDiagnosticHostGate expected) {
        if (!eventLoop().inEventLoop() || !isActive() || diagnostics != expected)
            throw new IllegalStateException("Diagnostic gate owner changed");
        if (diagnostics == null) diagnostics = new NativeDiagnosticHostGate(identity, address, policy);
        else diagnostics.replacePolicy(policy);
        return diagnostics;
    }

    public CompletionStage<Void> termination() {
        return termination;
    }

    public void drainAdmissions() {
        gate.drain();
    }

    public boolean isServing() {
        return isActive() && gate.isServing();
    }

    @Override
    protected void doClose() {
        open = false;
        gate.close();
        if (maintenance != null) {
            maintenance.cancel(false);
        }
        finishAssistedAnswers(); // isOpen=false closes every per-attempt monitor before mux retirement.
        if (diagnostics != null) diagnostics.close();

        if (connectivity != null) {
            try { connectivity.close(); }
            catch (RuntimeException failure) { nativeCloseFailure.compareAndSet(null, failure); }
        }

        IceUdpMuxListener listener = mux;
        mux = null;
        if (listener != null) {
            listener.close();
        }

        for (Session session : sessions.values()) {
            closeChild(session);
        }

        sessions.clear();
        assisted.clear();

        List<CompletableFuture<Void>> outstanding = new ArrayList<>(nativeClosures);
        if (diagnostics != null) outstanding.add(diagnostics.termination().toCompletableFuture());
        outstanding.addAll(admissions);
        CompletableFuture.allOf(outstanding.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            events.clear();
            Throwable failure = error == null ? nativeCloseFailure.get() : error;
            if (failure == null) {
                termination.complete(null);
            } else {
                termination.completeExceptionally(failure);
            }
        });
    }

    @Override
    protected void doBeginRead() {
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return address;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open && mux != null;
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(false, 16);
    }
}
