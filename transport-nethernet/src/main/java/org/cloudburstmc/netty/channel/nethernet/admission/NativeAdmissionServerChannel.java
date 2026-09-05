package org.cloudburstmc.netty.channel.nethernet.admission;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import io.netty.channel.*;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed-UDP native host. The only source of client context is authenticated raw STUN. */
public final class NativeAdmissionServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NativeAdmissionServerChannel.class);
    public record Event(String ticketId, String stage, String reason, long occurredAt, long validationToCreationNanos) {}
    private static final class Session {
        final AdmissionGate.Reservation reservation;
        final AdmittedNetherNetChildChannel child;
        final long creationNanos;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        volatile boolean failed;
        boolean reported;
        Session(AdmissionGate.Reservation reservation, AdmittedNetherNetChildChannel child) { this.reservation = reservation; this.child = child; creationNanos = System.nanoTime(); }
    }
    private final DefaultNetherServerChannelConfig config = new DefaultNetherServerChannelConfig(this);
    private final NativeHostIdentity identity;
    private final boolean allowWildcardBind;
    private final AdmissionGate gate;
    private final int maxNativePeers;
    private final AtomicReference<Throwable> nativeCloseFailure = new AtomicReference<>();
    private final AtomicInteger liveNativePeers = new AtomicInteger();
    private final Set<CompletableFuture<Void>> nativeClosures = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<AdmissionGate.Reservation> pending;
    private final Map<AdmissionGate.Reservation, Session> sessions = new HashMap<>();
    private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(256);
    private final AtomicLong droppedEvents = new AtomicLong(), creations = new AtomicLong();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile boolean open = true;
    private volatile InetSocketAddress address;
    private volatile RawUdpMuxListener mux;
    private ScheduledFuture<?> tick;

    public NativeAdmissionServerChannel(NativeHostIdentity identity, AdmissionValidator validator, AdmissionGate.Limits limits) {
        this(identity, validator, limits, false);
    }
    /** Wildcard binding is safe only when the caller publishes a separately validated concrete candidate. */
    public NativeAdmissionServerChannel(NativeHostIdentity identity, AdmissionValidator validator, AdmissionGate.Limits limits, boolean allowWildcardBind) {
        this.identity = Objects.requireNonNull(identity); gate = new AdmissionGate(limits, validator); pending = new ArrayBlockingQueue<>(limits.pending()); maxNativePeers = limits.sessions();
        this.allowWildcardBind = allowWildcardBind;
    }
    @Override protected void doBind(SocketAddress socketAddress) throws Exception {
        if (!(socketAddress instanceof InetSocketAddress a) || a.isUnresolved() || a.getPort() == 0 || (!allowWildcardBind && a.getAddress().isAnyLocalAddress()))
            throw new IllegalArgumentException("Resolved explicit interface address and fixed UDP port required");
        RawUdpMuxListener listener = new RawUdpMuxListener(a.getAddress(), a.getPort(), (packet, host, port) -> {
            byte[] ip = NetUtil.createByteArrayFromIpAddressString(host);
            if (ip == null) return false;
            try {
                return gate.ingress(packet, new InetSocketAddress(InetAddress.getByAddress(ip), port), System.currentTimeMillis(), System.nanoTime(), reservation -> {
                    if (!pending.offer(reservation)) throw new RejectedExecutionException("Admission queue full");
                });
            } catch (UnknownHostException invalid) { return false; }
        });
        address = a; mux = listener;
        tick = eventLoop().scheduleWithFixedDelay(this::pump, 0, 5, TimeUnit.MILLISECONDS);
    }
    private void pump() {
        if (!isOpen()) return;
        try {
            if (mux.failure() != null || nativeCloseFailure.get() != null) { close(); return; }
            var warning = gate.pollPendingLimitWarning(System.nanoTime());
            if (warning != null) log.warn("Pending admission limit reached: pending={}, limit={}, rejectedSinceLastWarning={}",
                warning.pending(), warning.limit(), warning.rejected());
            for (AdmissionGate.Reservation r : gate.sweep(System.currentTimeMillis(), System.nanoTime())) finish(r, "timeout");
            // Limit creation work per tick, independent of packet rate and native callback rate.
            for (int i = 0; i < 4 && nativeCloseFailure.get() == null && liveNativePeers.get() < maxNativePeers; i++) { var r = pending.poll(); if (r == null) break; create(r); }
            for (Session session : new ArrayList<>(sessions.values())) {
                if (session.failed || !session.child.isOpen()) { finish(session.reservation, "closed"); continue; }
                if (!session.reported && session.child.isActive()) {
                    session.reported = true; gate.connected(session.reservation);
                    emit(session.reservation, "ticket.data_channels_open", "both_channels_open", session.creationNanos);
                }
            }
        } catch (Exception failure) { pipeline().fireExceptionCaught(failure); close(); }
    }
    private void create(AdmissionGate.Reservation reservation) {
        VerifiedAdmission a = gate.admission(reservation);
        if (a == null || a.expiresAt() <= System.currentTimeMillis()) { gate.finish(reservation); return; }
        PeerConnection peer = null;
        AdmittedNetherNetChildChannel child = null;
        Session allocated = null;
        try {
            creations.incrementAndGet();
            peer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(address.getAddress())
                .withEnableIceUdpMux(true).withPortRangeBegin((short)address.getPort()).withPortRangeEnd((short)address.getPort())
                .withMaxMessageSize(NetherNetFrameDecoder.MESSAGE_LIMIT), Runnable::run, identity.certificate(), identity.privateKey());
            child = new AdmittedNetherNetChildChannel(this, peer, reservation.tuple(), address);
            child.attr(AdmissionPrincipal.KEY).set(new AdmissionPrincipal(a.tokenId(), a.networkId(), a.callerContextHash(), a.keyId()));
            Session session = new Session(reservation, child);
            allocated = session; liveNativePeers.incrementAndGet(); nativeClosures.add(session.closed);
            session.closed.whenComplete((ignored, failure) -> {
                if (failure != null) { nativeCloseFailure.compareAndSet(null, failure); gate.drain(); }
                else liveNativePeers.decrementAndGet();
                nativeClosures.remove(session.closed);
            });
            // Netty closeFuture signals channel closure even when doClose failed.
            child.nativeTermination().whenComplete((ignored, failure) -> { if (failure == null) session.closed.complete(null); else session.closed.completeExceptionally(failure); });
            peer.onStateChange.register((p, state) -> { if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) session.failed = true; });
            peer.onDataChannel.register((p, dc) -> {
                if (session.failed) return;
                try { session.child.acceptDataChannel(dc); }
                catch (Exception invalidChannel) { session.failed = true; }
            });
            peer.setRemoteDescription(a.remoteDescription(), SessionDescriptionType.OFFER);
            peer.setLocalDescription("answer", a.localUfrag(), a.localPassword());
            // Refuse identity files replaced between profile publication and allocation.
            String local = peer.localDescription();
            if (!local.contains("a=fingerprint:" + identity.fingerprint() + "\r\n") || !local.contains("a=ice-ufrag:" + a.localUfrag() + "\r\n"))
                throw new IllegalStateException("Native identity does not match published profile");
            sessions.put(reservation, session);
            pipeline().fireChannelRead(child); pipeline().fireChannelReadComplete();
            byte[] initialPacket = gate.ready(reservation);
            if (initialPacket == null) { finish(reservation, "cancelled"); return; }
            mux.replay(initialPacket, reservation.tuple().getAddress(), reservation.tuple().getPort());
            emit(reservation, "ticket.ice_seen", "token_and_stun_validated", session.creationNanos);
        } catch (Exception failure) {
            gate.finish(reservation); sessions.remove(reservation);
            if (allocated != null) closeChild(allocated);
            else if (peer != null) {
                try { if (!peer.closeAndAwait(java.time.Duration.ofSeconds(5))) throw new IllegalStateException("Unregistered native cleanup timeout"); }
                catch (Exception failedClose) { nativeCloseFailure.compareAndSet(null, failedClose); gate.drain(); peer.close(); }
            }
            emit(reservation, "ticket.failed", "native_creation_failed", System.nanoTime());
        }
    }
    private void finish(AdmissionGate.Reservation r, String reason) {
        gate.finish(r); Session session = sessions.remove(r);
        if (session != null) { closeChild(session); if (!session.reported) emit(r, "ticket.failed", reason, session.creationNanos); }
    }
    private static void closeChild(Session session) {
        try { session.child.close(); }
        catch (IllegalStateException unregistered) {
            // Negotiation can fail before the child is handed to ServerBootstrap.
            try { session.child.closeUnregistered(); session.closed.complete(null); }
            catch (Exception failedClose) { session.closed.completeExceptionally(failedClose); }
        }
    }
    private void emit(AdmissionGate.Reservation r, String stage, String reason, long createdAt) {
        if (!events.offer(new Event(r.tokenId(), stage, reason, System.currentTimeMillis(), Math.max(0, createdAt - r.acceptedNanos())))) droppedEvents.incrementAndGet();
    }
    public List<Event> pollEvents() { List<Event> result = new ArrayList<>(256); events.drainTo(result); return result; }
    public AdmissionGate.Stats admissionStats() { return gate.stats(); }
    public int liveNativePeers() { return liveNativePeers.get(); }
    public long creationAttempts() { return creations.get(); }
    public long droppedEvents() { return droppedEvents.get(); }
    public long[] nativeStats() { RawUdpMuxListener listener = mux; if (listener == null) throw new IllegalStateException("Endpoint not bound"); return listener.stats(); }
    public NativeHostIdentity identity() { return identity; }
    public CompletionStage<Void> termination() { return termination; }
    public void drainAdmissions() { gate.drain(); }
    @Override protected void doClose() {
        open = false; gate.close(); pending.clear();
        if (tick != null) tick.cancel(false);
        for (Session session : sessions.values()) closeChild(session);
        sessions.clear();
        RawUdpMuxListener listener = mux; mux = null;
        if (listener != null) listener.close(); // any still-closing peer is fail-closed in the native gate
        CompletableFuture.allOf(nativeClosures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            events.clear();
            Throwable failure = error == null ? nativeCloseFailure.get() : error;
            if (failure == null) termination.complete(null); else termination.completeExceptionally(failure);
        });
    }
    @Override protected void doBeginRead() {}
    @Override protected boolean isCompatible(EventLoop loop) { return true; }
    @Override protected SocketAddress localAddress0() { return address; }
    @Override public ChannelConfig config() { return config; }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isActive() { return open && mux != null; }
    @Override public ChannelMetadata metadata() { return new ChannelMetadata(false, 16); }
}
