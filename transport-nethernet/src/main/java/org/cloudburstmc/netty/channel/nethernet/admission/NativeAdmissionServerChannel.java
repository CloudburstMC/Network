package org.cloudburstmc.netty.channel.nethernet.admission;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
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
        boolean reported, closing;

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
    private final AtomicLong droppedEvents = new AtomicLong(), creations = new AtomicLong();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile boolean open = true;
    private volatile InetSocketAddress address;
    private volatile IceUdpMuxListener mux;
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
        if (!(socketAddress instanceof InetSocketAddress a) || a.isUnresolved() || a.getPort() == 0 || (
                !allowWildcardBind && a.getAddress().isAnyLocalAddress())) {
            throw new IllegalArgumentException("Resolved explicit interface address and fixed UDP port required");
        }
        address = a;
        mux = new IceUdpMuxListener(a.getAddress(), a.getPort(), Math.min(limits.pending(), 4096),
                Duration.ofMillis(Math.min(limits.handshakeMillis(), 30_000)), eventLoop(), this::admit);
        maintenance = eventLoop().scheduleWithFixedDelay(this::maintain, 100, 100, TimeUnit.MILLISECONDS);
    }

    private CompletionStage<IceUdpMuxListener.Acceptance> admit(IceUdpMuxListener.Request request) throws Exception {
        if (!isOpen() || nativeCloseFailure.get() != null) {
            return CompletableFuture.completedFuture(null);
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
        child.attr(AdmissionPrincipal.KEY)
                .set(new AdmissionPrincipal(a.tokenId(), a.networkId(), a.callerContextHash(), a.keyId()));
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
        return liveNativePeers.get();
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

    public NativeHostIdentity identity() {
        return identity;
    }

    public CompletionStage<Void> termination() {
        return termination;
    }

    public void drainAdmissions() {
        gate.drain();
    }

    @Override
    protected void doClose() {
        open = false;
        gate.close();
        if (maintenance != null) {
            maintenance.cancel(false);
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
