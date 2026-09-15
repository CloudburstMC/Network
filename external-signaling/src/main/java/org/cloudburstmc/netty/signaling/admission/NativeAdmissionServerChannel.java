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

    public record Event(String ticketId, String stage, String reason, long occurredAt, long validationToCreationNanos) {
    }

    private static final class Session {
        final AdmissionGate.Reservation reservation;
        final AdmittedNetherNetChildChannel child;
        final long creationNanos = System.nanoTime();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        volatile boolean failed;
        boolean reported;
        boolean closing;

        Session(AdmissionGate.Reservation reservation, AdmittedNetherNetChildChannel child) {
            this.reservation = reservation;
            this.child = child;
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
        this(identity, validator, limits, allowWildcardBind, true);
    }

    NativeAdmissionServerChannel(NativeHostIdentity identity, AdmissionValidator validator,
                                 AdmissionGate.Limits limits, boolean allowWildcardBind, boolean initiallyEnabled) {
        this.identity = Objects.requireNonNull(identity);
        this.limits = Objects.requireNonNull(limits);
        gate = new AdmissionGate(limits, validator, initiallyEnabled);
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

        NativeDiagnosticHostGate diagnostic = diagnostics;
        if (diagnostic != null) {
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

        VerifiedAdmission a = gate.admission(reservation);
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

    /**
     * Called by the listener on this channel's event loop, before the first request resumes.
     */
    private void initialize(AdmissionGate.Reservation reservation, VerifiedAdmission a, PeerConnection peer) {
        var child = new AdmittedNetherNetChildChannel(this, peer, reservation.tuple(), address);
        var session = new Session(reservation, child);
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
        emit(reservation, "ticket.ice_seen", "token_and_stun_validated", session.creationNanos);
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
                    session.reported = true;
                    gate.connected(session.reservation);
                    emit(session.reservation, "ticket.data_channels_open", "both_channels_open", session.creationNanos);
                }
            }
        } catch (Exception failure) {
            pipeline().fireExceptionCaught(failure);
            close();
        }
    }

    private void finish(AdmissionGate.Reservation r, String reason) {
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
        if (!events.offer(new Event(r.tokenId(), stage, reason, System.currentTimeMillis(),
                Math.max(0, createdAt - r.acceptedNanos())))) {
            droppedEvents.incrementAndGet();
        }
    }

    public List<Event> pollEvents() {
        List<Event> result = new ArrayList<>(256);
        events.drainTo(result);
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

    public CompletionStage<Void> termination() {
        return termination;
    }

    public void drainAdmissions() {
        gate.drain();
    }

    AdmissionGate.Staging stageAdmissions() { return gate.stage(); }

    void disableAdmissions() { gate.disable(); }

    boolean currentAdmissionUpdate(AdmissionGate.Staging update) {
        return isActive() && nativeCloseFailure.get() == null && gate.current(update);
    }

    boolean enableAdmissions(AdmissionGate.Staging update) {
        return currentAdmissionUpdate(update) && gate.enable(update);
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
