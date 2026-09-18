/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import tel.schich.libdatachannel.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** One caller-authorized diagnostic attempt; no workload authentication, retry scheduler or game protocol. */
public final class NativeDiagnosticProbeAttempt implements AutoCloseable {
    /** Trusted local job input, NOT signed-job or target-ownership evidence. Expiry/attempt must never be reissued. */
    public record Job(Context context, String attemptIdHex, DiagnosticHostPolicy.Endpoint target,
                      String hostFingerprintHex, long expiresAt) {
        public Job {
            Objects.requireNonNull(context); Objects.requireNonNull(target); unhex(attemptIdHex,16); unhex(hostFingerprintHex,32);
            integer(expiresAt,1000,0xffffffffL * 1000); if (expiresAt % 1000 != 0) throw invalid();
        }
    }
    /** Owned exact fully gathered offer. Sensitive ICE credentials/assertion must not be logged or persisted. */
    public record Request(Context context, Claims claims, String ufrag, byte[] offer, DiagnosticAssertionCodec.Assertion assertion) {
        public Request { Objects.requireNonNull(context); Objects.requireNonNull(claims); DiagnosticAdmissionCodec.ufrag(ufrag);
            if (offer.length < 1 || offer.length > DiagnosticAnswerCodec.MAX_SDP_BYTES) throw invalid(); offer = offer.clone(); Objects.requireNonNull(assertion); }
        @Override public byte[] offer() { return offer.clone(); }
        @Override public String toString() { return "NativeDiagnosticProbeRequest[redacted]"; }
    }
    /** Must return promptly, with a bounded HTTP body; may not perform blocking IO on the calling worker. */
    @FunctionalInterface public interface Signaling { CompletionStage<String> exchange(Request request); }
    public enum Reason { COMPLETE, CANCELLED, EXPIRED, WITHDRAWN, GATHERING, SIGNALING, ANSWER, TRANSPORT, PROTOCOL, SELECTED_PATH, CLEANUP }
    /** Success requires the original ping to be echoed by the pinned host. */
    public record Result(Job job, boolean success, Reason reason, boolean answerVerified, boolean transportEstablished,
                         boolean pingVerified, boolean cleanupComplete,
                         String offerDigestHex, String clientFingerprintHex, InetSocketAddress selectedLocal,
                         InetSocketAddress selectedRemote, int sentFrames, int sentBytes,
                         int receivedFrames, int receivedBytes, long completedAt) { }
    private record Incoming(int channel, byte[] bytes) { }
    private static final class Failed extends RuntimeException {
        final Reason reason; Failed(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    // Exhausting the handshake budget need not expire the caller's still-valid job authority.
    private static final class HandshakeTimeout extends RuntimeException { }
    private final Job job;
    private final InetSocketAddress bind, target, stunServer;
    private final boolean loopbackTest;
    private final Supplier<DiagnosticAnswerCodec.Catalog> catalogReader;
    private final BooleanSupplier authorized;
    private final Clock clock;
    private final AtomicBoolean closeObserved = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean(), failed = new AtomicBoolean(), connected = new AtomicBoolean(), gathered = new AtomicBoolean(), protocolFailed = new AtomicBoolean();
    private final AtomicInteger receivedFrames = new AtomicInteger(), receivedBytes = new AtomicInteger();
    private final ArrayBlockingQueue<Incoming> incoming = new ArrayBlockingQueue<>(MAX_FRAMES);
    private final DataChannel[] channels = new DataChannel[2];
    private final AtomicReference<PeerConnection> peer = new AtomicReference<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private DiagnosticAnswerCodec.Catalog catalog;
    private InetSocketAddress gatheredLocal;
    private long anchorWall, anchorNanos, previousNanos, deadlineNanos, handshakeDeadlineNanos, currentNanos, currentWall;
    private int sentFrames, sentBytes;

    public NativeDiagnosticProbeAttempt(Job job, InetSocketAddress bind, Supplier<DiagnosticAnswerCodec.Catalog> catalog,
                                        BooleanSupplier authorized) {
        this(job, bind, catalog, authorized, Clock.system(), false, null);
    }
    /** Optional explicitly configured numeric discovery server, used only for the proactive profile. */
    public NativeDiagnosticProbeAttempt(Job job, InetSocketAddress bind, Supplier<DiagnosticAnswerCodec.Catalog> catalog,
                                        BooleanSupplier authorized, InetSocketAddress stunServer) {
        this(job,bind,catalog,authorized,Clock.system(),false,stunServer);
    }
    /** Local native tests only: the exception permits loopback, never private/unknown targets or DNS. */
    NativeDiagnosticProbeAttempt(Job job, InetSocketAddress bind, Supplier<DiagnosticAnswerCodec.Catalog> catalog,
                                BooleanSupplier authorized, Clock clock, boolean loopbackTest) {
        this(job,bind,catalog,authorized,clock,loopbackTest,null);
    }
    NativeDiagnosticProbeAttempt(Job job, InetSocketAddress bind, Supplier<DiagnosticAnswerCodec.Catalog> catalog,
                                BooleanSupplier authorized, Clock clock, boolean loopbackTest, InetSocketAddress stunServer) {
        this.job = Objects.requireNonNull(job); this.catalogReader = Objects.requireNonNull(catalog);
        this.authorized = Objects.requireNonNull(authorized); this.clock = Objects.requireNonNull(clock); this.loopbackTest = loopbackTest;
        if (stunServer != null && (!job.target.assisted() || stunServer.isUnresolved() || stunServer.getPort() < 1
                || family(stunServer.getAddress()) != job.target.family() || !(EndpointAddress.scope(stunServer.getAddress()) == EndpointAddress.Scope.PUBLIC
                || loopbackTest && EndpointAddress.scope(stunServer.getAddress()) == EndpointAddress.Scope.LOOPBACK))) throw invalid();
        this.stunServer = stunServer;
        try {
            byte[] address = unhex(job.target.addressHex(),16);
            this.target = job.target.assisted() ? null : new InetSocketAddress(InetAddress.getByAddress(job.target.family() == 4 ? Arrays.copyOfRange(address,12,16) : address), job.target.port());
            if (target != null) {
                EndpointAddress.Scope scope = EndpointAddress.scope(target.getAddress());
                if (scope != EndpointAddress.Scope.PUBLIC && !(loopbackTest && scope == EndpointAddress.Scope.LOOPBACK)) throw invalid();
            }
            if (bind.isUnresolved() || bind.getPort() < 1 || bind.getAddress().isAnyLocalAddress() || bind.getAddress().isMulticastAddress()
                    || family(bind.getAddress()) != job.target.family() || bind.getAddress() instanceof Inet6Address v6 && v6.getScopeId() != 0) throw invalid();
            this.bind = new InetSocketAddress(InetAddress.getByAddress(bind.getAddress().getAddress()), bind.getPort());
        } catch (UnknownHostException e) { throw invalid(); }
    }
    public CompletionStage<Void> termination() { return termination.minimalCompletionStage(); }
    /** Runs on one caller-owned bounded worker, never a native callback/event-loop thread. One invocation only. */
    public Result run(Signaling signaling) {
        Objects.requireNonNull(signaling);
        if (!started.compareAndSet(false,true)) throw new IllegalStateException("Diagnostic attempt already used");
        Reason reason = Reason.GATHERING; DiagnosticExchange exchange = null;
        InetSocketAddress selectedLocal = null, selectedRemote = null;
        boolean answerVerified = false, transportEstablished = false, complete = false, cleanup = false;
        String offerHash = null, fingerprint = null;
        CompletableFuture<String> pending = null; StunUdpMuxMonitor monitor = null; boolean discoveryClean = true;
        try {
            anchorWall = clock.wallMillis().getAsLong(); anchorNanos = clock.nanoTime().getAsLong();
            previousNanos = currentNanos = anchorNanos; currentWall = anchorWall;
            integer(anchorWall,0,SAFE);
            long remaining = job.expiresAt - anchorWall;
            if (remaining < 1 || remaining > MAX_ATTEMPT_MILLIS) throw new Failed(Reason.EXPIRED);
            deadlineNanos = anchorNanos + remaining * 1_000_000L;
            handshakeDeadlineNanos = anchorNanos + Math.min(remaining,MAX_HANDSHAKE_MILLIS) * 1_000_000L;
            check(); catalog = Objects.requireNonNull(catalogReader.get()); check();
            var configuration = PeerConnectionConfiguration.DEFAULT.withIceServers(List.of()).withEnableIceTcp(false)
                .withBindAddress(bind.getAddress()).withPortRangeBegin(bind.getPort()).withPortRangeEnd(bind.getPort())
                .withEnableIceUdpMux(true).withDisableAutoNegotiation(true).withMtu(1248).withMaxMessageSize(MAX_MESSAGE_SIZE);
            PeerConnection nativePeer = PeerConnection.createPeer(configuration, Runnable::run);
            peer.set(nativePeer); check();
            nativePeer.onStateChange.register((p,state) -> {
                if (state == PeerState.RTC_CONNECTED) connected.set(true);
                if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) failed.set(true);
            });
            nativePeer.onGatheringStateChange.register((p,state) -> { if (state == GatheringState.RTC_GATHERING_COMPLETE) gathered.set(true); });
            nativePeer.onDataChannel.register((p,channel) -> protocolFailed.set(true)); // Exactly our two locally created channels.
            installChannels(nativePeer);
            String ufrag = random(18), password = random(18);
            checkHandshake(); nativePeer.setLocalDescription("offer",ufrag,password);
            await(gathered::get, true);
            String offerText = nativePeer.localDescription();
            if (offerText.length() > DiagnosticAnswerCodec.MAX_SDP_BYTES) throw new Failed(Reason.GATHERING);
            if (job.target.assisted()) {
                InetSocketAddress candidate = bind;
                if (stunServer != null) {
                    monitor = new StunUdpMuxMonitor(bind.getAddress(),bind.getPort(),stunServer.getAddress().getHostAddress(),stunServer.getPort());
                    StunUdpMuxMonitor discovery = monitor;
                    await(() -> discovery.binding(0).map(b -> b.state() == StunBinding.State.SUCCEEDED).orElse(false),true);
                    var mapping = monitor.binding(0).orElseThrow();
                    candidate = numeric(mapping.mappedAddress(),mapping.mappedPort());
                }
                var scope = EndpointAddress.scope(candidate.getAddress());
                if (family(candidate.getAddress()) != job.target.family() || (scope != EndpointAddress.Scope.PUBLIC && !(loopbackTest && scope == EndpointAddress.Scope.LOOPBACK)))
                    throw new Failed(Reason.GATHERING);
                gatheredLocal = candidate;
                String original = field(offerText,"a=candidate:");
                String replacement = "1 1 UDP 2130706431 " + candidate.getAddress().getHostAddress() + " " + candidate.getPort() + " typ " + (monitor == null ? "host" : "srflx");
                offerText = offerText.replace("a=candidate:"+original, "a=candidate:"+replacement);
            }
            byte[] offer = utf8(offerText); offerHash = hex(digest(offer));
            String fp = field(offerText,"a=fingerprint:");
            if (!fp.startsWith("sha-256 ")) throw new Failed(Reason.GATHERING);
            fingerprint = fp.substring(8).replace(":", "").toLowerCase(Locale.ROOT);
            var claims = new Claims(job.expiresAt,fingerprint,password,job.attemptIdHex,offerHash,job.target.candidateRevision(),
                job.target.family(),job.target.addressHex(),job.target.port(),job.target.assisted() ? ASSISTED_PROFILE : PROFILE);
            DiagnosticAssertionCodec.validateOffer(offer,claims,ufrag);
            String[] candidate = field(offerText,"a=candidate:").split(" ");
            if (!job.target.assisted() && !numeric(candidate[4],Integer.parseInt(candidate[5])).equals(bind)) throw new Failed(Reason.GATHERING);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp384r1"));
            var assertion = DiagnosticAssertionCodec.sign(job.context,claims,ufrag,generator.generateKeyPair());
            checkHandshake(); reason = Reason.SIGNALING;
            pending = Objects.requireNonNull(signaling.exchange(new Request(job.context,claims,ufrag,offer,assertion))).toCompletableFuture();
            await(pending::isDone, true); String wire = pending.join(); pending = null; check(); reason = Reason.ANSWER;
            if (wire == null || wire.length() > DiagnosticAnswerCodec.MAX_WIRE_BYTES) throw new Failed(Reason.ANSWER);
            var expected = new DiagnosticAnswerCodec.Expected(job.context,claims,ufrag,job.hostFingerprintHex);
            try (var verified = DiagnosticAnswerCodec.verify(expected,wire,this::currentCatalog,
                    new DiagnosticAnswerCodec.Options(clock,cancelled::get))) {
                if (verified == null) throw new Failed(Reason.ANSWER);
                byte[] answer = verified.takeSdp();
                try {
                    checkHandshake();
                    String sdp = new String(answer,StandardCharsets.UTF_8);
                    if (job.target.assisted()) {
                        String[] answerCandidate = field(sdp,"a=candidate:").split(" ");
                        InetSocketAddress endpoint = numeric(answerCandidate[4],Integer.parseInt(answerCandidate[5]));
                        var scope = EndpointAddress.scope(endpoint.getAddress());
                        if (family(endpoint.getAddress()) != job.target.family()
                                || (scope != EndpointAddress.Scope.PUBLIC && !(loopbackTest && scope == EndpointAddress.Scope.LOOPBACK)))
                            throw new Failed(Reason.ANSWER);
                    }
                    checkHandshake(); nativePeer.setRemoteDescription(sdp,SessionDescriptionType.ANSWER);
                }
                finally { Arrays.fill(answer,(byte)0); }
            }
            answerVerified = true; reason = Reason.TRANSPORT;
            await(() -> connected.get() && channels[0].isOpen() && channels[1].isOpen(), true);
            for (int i = 0; i < 2; i++) validateChannel(channels[i],i);
            transportEstablished = true;
            CandidatePair establishedPair = selectedPair(nativePeer); // No PING is sent to an invalid selected destination.
            selectedLocal = numeric(establishedPair.local().getHostString(),establishedPair.local().getPort());
            selectedRemote = numeric(establishedPair.remote().getHostString(),establishedPair.remote().getPort());
            reason = Reason.PROTOCOL;
            checkHandshake(); exchange = new DiagnosticExchange(job.attemptIdHex,false,this::send); exchange.start();
            while (!exchange.complete()) { drain(exchange); if (!exchange.complete()) pause(false); }
            check(); reason = Reason.SELECTED_PATH;
            CandidatePair pair = selectedPair(nativePeer);
            selectedLocal = numeric(pair.local().getHostString(),pair.local().getPort());
            selectedRemote = numeric(pair.remote().getHostString(),pair.remote().getPort());
            check(); complete = exchange.complete(); reason = Reason.COMPLETE;
        } catch (HandshakeTimeout timeout) { reason = answerVerified && !transportEstablished ? Reason.TRANSPORT : Reason.EXPIRED; }
        catch (Failed failure) { reason = failure.reason; }
        catch (InterruptedException interrupted) { cancelled.set(true); reason = Reason.CANCELLED; Thread.currentThread().interrupt(); }
        catch (GeneralSecurityException | RuntimeException failure) { /* Reason identifies the failing bounded stage; never include secret payloads. */ }
        finally {
            if (pending != null) pending.cancel(false);
            if (monitor != null) { try { monitor.close(); } catch (RuntimeException failure) { discoveryClean = false; reason = Reason.CLEANUP; } }
            PeerConnection value = peer.get();
            if (value == null) { cleanup = true; termination.complete(null); }
            else {
                boolean interrupted = Thread.interrupted();
                try {
                    closePeer(value).toCompletableFuture().get(5,TimeUnit.SECONDS); cleanup = true; peer.compareAndSet(value,null);
                } catch (InterruptedException stop) { interrupted = true; reason = Reason.CLEANUP; }
                catch (ExecutionException | TimeoutException | RuntimeException failure) { reason = Reason.CLEANUP; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            incoming.clear(); Arrays.fill(channels,null);
        }
        cleanup = cleanup && discoveryClean;
        if (complete && cleanup) { try { check(); } catch (RuntimeException withdrawn) { complete = false; reason = withdrawn instanceof Failed failure ? failure.reason : Reason.WITHDRAWN; } }
        boolean success = complete && cleanup && !protocolFailed.get();
        if (!success && reason == Reason.COMPLETE) reason = Reason.PROTOCOL;
        return new Result(job,success,reason,answerVerified,transportEstablished,exchange != null && exchange.complete(),cleanup,
            offerHash,fingerprint,selectedLocal,selectedRemote,sentFrames,sentBytes,receivedFrames.get(),receivedBytes.get(),
            currentWall);
    }
    private CandidatePair selectedPair(PeerConnection nativePeer) {
        CandidatePair pair = nativePeer.selectedCandidatePair();
        InetSocketAddress selectedLocal = numeric(pair.local().getHostString(),pair.local().getPort());
        InetSocketAddress selectedRemote = numeric(pair.remote().getHostString(),pair.remote().getPort());
        IceCandidate localCandidate = IceCandidate.parse(pair.localCandidate());
        var localScope = EndpointAddress.scope(selectedLocal.getAddress());
        // A NAT may expose a different mapping to the peer than to the discovery server.
        boolean assistedLocal = job.target.assisted() && (selectedLocal.equals(gatheredLocal)
                || localCandidate.type() == IceCandidate.Type.PEER_REFLEXIVE
                && (localScope == EndpointAddress.Scope.PUBLIC || loopbackTest && localScope == EndpointAddress.Scope.LOOPBACK));
        if (!(selectedLocal.equals(bind) || assistedLocal) || (target != null ? !selectedRemote.equals(target)
                : EndpointAddress.scope(selectedRemote.getAddress()) != EndpointAddress.Scope.PUBLIC && !(loopbackTest && EndpointAddress.scope(selectedRemote.getAddress()) == EndpointAddress.Scope.LOOPBACK)) || family(selectedLocal.getAddress()) != job.target.family()
                || family(selectedRemote.getAddress()) != job.target.family()
                || localCandidate.transport() != IceCandidate.Transport.UDP
                || IceCandidate.parse(pair.remoteCandidate()).transport() != IceCandidate.Transport.UDP) throw new Failed(Reason.SELECTED_PATH);
        return pair;
    }
    private void installChannels(PeerConnection value) {
        for (int i = 0; i < 2; i++) {
            int index = i;
            channels[i] = value.createDataChannel(i == 0 ? "ReliableDataChannel" : "UnreliableDataChannel", DataChannelInitSettings.DEFAULT
                .withReliability(DataChannelReliability.DEFAULT.withUnordered(i == 1).withUnreliable(i == 1).withMaxRetransmits(0)));
            channels[i].onClosed.register(channel -> failed.set(true)); channels[i].onError.register((channel,error) -> failed.set(true));
            channels[i].onMessage.register(new DataChannelCallback.Message() {
                public void onText(DataChannel channel,String text) { protocolFailed.set(true); }
                public void onBinary(DataChannel channel,ByteBuffer bytes) {
                    int size = bytes.remaining();
                    if (cancelled.get() || protocolFailed.get() || size != DiagnosticExchange.FRAME_BYTES || receivedFrames.incrementAndGet() > MAX_FRAMES
                            || receivedBytes.addAndGet(size) > MAX_APPLICATION_SEND_BYTES) { protocolFailed.set(true); return; }
                    byte[] owned = new byte[size]; bytes.get(owned);
                    if (!incoming.offer(new Incoming(index,owned))) protocolFailed.set(true);
                }
            });
        }
    }
    private void validateChannel(DataChannel dc,int index) {
        DataChannelReliability r = dc.reliability();
        if (!dc.label().equals(index == 0 ? "ReliableDataChannel" : "UnreliableDataChannel") || !dc.protocol().isEmpty()
                || r.isUnordered() != (index == 1) || r.isUnreliable() != (index == 1) || r.maxRetransmits() != 0 || !r.maxPacketLifeTime().isZero()) throw new Failed(Reason.PROTOCOL);
    }
    private void send(int channel,byte[] bytes) {
        check(); if (protocolFailed.get() || bytes.length > MAX_FRAME_BYTES || ++sentFrames > MAX_FRAMES || (sentBytes += bytes.length) > MAX_APPLICATION_SEND_BYTES) throw new Failed(Reason.PROTOCOL);
        channels[channel].sendMessage(ByteBuffer.allocateDirect(bytes.length).put(bytes).flip());
    }
    private void drain(DiagnosticExchange exchange) {
        check(); if (protocolFailed.get()) throw new Failed(Reason.PROTOCOL);
        Incoming message; while ((message = incoming.poll()) != null) exchange.receive(message.channel,message.bytes);
    }
    private void await(BooleanSupplier condition,boolean handshake) throws InterruptedException {
        while (!condition.getAsBoolean()) pause(handshake);
        if (handshake) checkHandshake(); else check();
    }
    private void checkHandshake() { check(); if (currentNanos - handshakeDeadlineNanos >= 0) throw new HandshakeTimeout(); }
    private void pause(boolean handshake) throws InterruptedException {
        check(); if (protocolFailed.get()) throw new Failed(Reason.PROTOCOL);
        if (failed.get()) throw new Failed(Reason.TRANSPORT);
        if (handshake && currentNanos - handshakeDeadlineNanos >= 0) throw new HandshakeTimeout();
        Thread.sleep(5);
    }
    private DiagnosticAnswerCodec.Catalog currentCatalog() { check(); return catalog; }
    private void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Failed(Reason.CANCELLED);
        long nanos = clock.nanoTime().getAsLong(), wall = clock.wallMillis().getAsLong(); integer(wall,0,SAFE);
        if (nanos - previousNanos < 0) throw new Failed(Reason.EXPIRED); previousNanos = currentNanos = nanos;
        long progressed = anchorWall + (nanos - anchorNanos) / 1_000_000L;
        currentWall = Math.max(wall,progressed); anchorWall = currentWall; anchorNanos = nanos;
        if (nanos - deadlineNanos >= 0 || currentWall >= job.expiresAt) throw new Failed(Reason.EXPIRED);
        if (!authorized.getAsBoolean()) throw new Failed(Reason.WITHDRAWN);
        if (catalog != null && (!catalog.equals(catalogReader.get()) || !catalog.providerOrigin().equals(job.context.providerOrigin())
                || currentWall < catalog.notBefore() || job.expiresAt > catalog.expiresAt())) throw new Failed(Reason.WITHDRAWN);
    }
    private static InetSocketAddress numeric(String value,int port) { try { return new InetSocketAddress(EndpointAddress.parse(value),port); } catch (UnknownHostException e) { throw invalid(); } }
    private static int family(InetAddress address) { return address instanceof Inet6Address ? 6 : 4; }
    private static String random(int bytes) { byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value); return base64(value); }
    private static String field(String text,String prefix) { List<String> values = text.lines().filter(line -> line.startsWith(prefix)).toList(); if (values.size() != 1) throw invalid(); return values.get(0).substring(prefix.length()); }
    private CompletionStage<Void> closePeer(PeerConnection value) {
        CompletionStage<Void> closing;
        try { closing = value.closeAsync(); }
        catch (RuntimeException failure) { termination.completeExceptionally(failure); return CompletableFuture.<Void>failedFuture(failure).minimalCompletionStage(); }
        if (closeObserved.compareAndSet(false,true)) closing.whenComplete((ignored,failure) -> {
            if (failure == null) termination.complete(null); else termination.completeExceptionally(failure);
        });
        return closing;
    }
    /** Native termination is observable even if the trusted signaling callback blocks before returning its stage. */
    @Override public void close() {
        cancelled.set(true); PeerConnection value = peer.get();
        if (value != null) closePeer(value); else if (!started.get()) termination.complete(null);
    }
}
