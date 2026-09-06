package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.PortAllocatorConfig;
import dev.kastle.webrtc.RTCAnswerOptions;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCDataChannelBuffer;
import dev.kastle.webrtc.RTCDataChannelObserver;
import dev.kastle.webrtc.RTCDataChannelState;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceGatheringState;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * The one class where libwebrtc lives. Implements the backend seam against
 * webrtc-java: factory pooling (each native PeerConnectionFactory runs one
 * network, worker, and signaling thread shared by all its peer connections,
 * so a pool spreads DTLS and SCTP load), non blocking sends, and the ICE
 * selected candidate pair bridge for real remote addresses.
 *
 * accept() performs blocking proxy calls into the native signaling thread and
 * must not run on a thread whose responsiveness matters (in particular never
 * on a signaling socket's I/O thread).
 */
public class LibWebRtcServerBackend implements WebRtcServerBackend {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(LibWebRtcServerBackend.class);

    private final List<PeerConnectionFactory> factories;
    private volatile PortAllocatorConfig portAllocatorConfig;
    private final boolean explicitPortAllocatorConfig;
    private final AtomicInteger nextFactory = new AtomicInteger();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

    // Serializes close() against in-flight accept() calls: an accept holds
    // the read lock across session registration, peer connection creation,
    // and start, so close (the write lock) can never dispose the factories
    // or close the session set mid accept. Also makes close idempotent.
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private boolean closed; // guarded by lifecycleLock

    public LibWebRtcServerBackend(List<PeerConnectionFactory> factories) {
        this(factories, null);
    }

    /**
     * @param factories           the PeerConnectionFactory pool, at least one;
     *                            the backend takes ownership and disposes each
     *                            on close
     * @param portAllocatorConfig port allocator settings applied to every peer
     *                            connection, or null for engine defaults
     */
    public LibWebRtcServerBackend(List<PeerConnectionFactory> factories, PortAllocatorConfig portAllocatorConfig) {
        if (factories.isEmpty()) {
            throw new IllegalArgumentException("factories must not be empty");
        }
        this.factories = List.copyOf(factories);
        this.portAllocatorConfig = portAllocatorConfig;
        this.explicitPortAllocatorConfig = portAllocatorConfig != null;
    }

    /**
     * Applies channel configured port allocator settings for subsequent
     * accepts unless this backend was constructed with an explicit
     * configuration, which wins. Called by the server channel at bind time so
     * the NETHER_PORT_ALLOCATOR_CONFIG channel option keeps working with the
     * convenience constructors.
     */
    public void applyDefaultPortAllocatorConfig(PortAllocatorConfig config) {
        if (!explicitPortAllocatorConfig && config != null) {
            this.portAllocatorConfig = config;
        }
    }

    @Override
    public WebRtcSession accept(String offerSdp, List<IceServerInfo> iceServers, WebRtcSessionListener listener, boolean fullIceAnswer) {
        lifecycleLock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Backend is closed");
            }
            return acceptLocked(offerSdp, iceServers, listener, fullIceAnswer);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private WebRtcSession acceptLocked(String offerSdp, List<IceServerInfo> iceServers, WebRtcSessionListener listener, boolean fullIceAnswer) {
        RTCConfiguration rtcConfig = new RTCConfiguration();
        if (portAllocatorConfig != null) {
            rtcConfig.portAllocatorConfig = portAllocatorConfig;
        }
        rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

        if (iceServers != null && !iceServers.isEmpty()) {
            log.trace("Injecting {} ICE servers into peer connection", iceServers.size());
            for (IceServerInfo info : iceServers) {
                RTCIceServer iceServer = new RTCIceServer();
                iceServer.urls = info.urls();
                iceServer.username = info.username();
                iceServer.password = info.password();
                rtcConfig.iceServers.add(iceServer);
            }
        }

        Session session = new Session(listener, sessions::remove, fullIceAnswer);
        sessions.add(session);
        try {
            PeerConnectionFactory factory = factories.get(Math.floorMod(nextFactory.getAndIncrement(), factories.size()));
            RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, session.observer);
            session.start(pc, offerSdp);
        } catch (RuntimeException e) {
            // A session whose engine setup failed must not stay tracked until
            // backend shutdown; closing it also releases whatever half of the
            // native state came to be.
            session.close();
            throw e;
        }
        return session;
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closeLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * Removes a=identity attributes before an SDP reaches the engine, as the
     * NetherNet spec instructs: the assertion is signaling layer metadata
     * (validated there, not here), and stripping it removes any dependence
     * on the engine's tolerance for it. The unstripped offer stays available
     * to the layers above for validation.
     */
    static String stripIdentityAttributes(String sdp) {
        return sdp.replaceAll("(?m)^a=identity:[^\\r\\n]*\\r?\\n?", "");
    }

    private void closeLocked() {
        // Close every live session before disposing the factories they run
        // on: disposing a factory with live peer connections is a native
        // level error (undropped references, engine threads stopped under
        // live connections).
        for (Session session : sessions) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("Error closing session during backend close: {}", e.getMessage());
            }
        }
        sessions.clear();
        for (PeerConnectionFactory factory : factories) {
            try {
                factory.dispose();
            } catch (Exception e) {
                log.warn("Failed to dispose PeerConnectionFactory: {}", e.getMessage());
            }
        }
    }

    interface PeerOperations {
        void setRemoteDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer);
        void createAnswer(CreateSessionDescriptionObserver observer);
        void setLocalDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer);
        RTCSessionDescription localDescription();
        void close();
    }

    private record NativePeerOperations(RTCPeerConnection peer) implements PeerOperations {
        @Override
        public void setRemoteDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer) {
            peer.setRemoteDescription(description, observer);
        }

        @Override
        public void createAnswer(CreateSessionDescriptionObserver observer) {
            peer.createAnswer(new RTCAnswerOptions(), observer);
        }

        @Override
        public void setLocalDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer) {
            peer.setLocalDescription(description, observer);
        }

        @Override
        public RTCSessionDescription localDescription() {
            return peer.getLocalDescription();
        }

        @Override
        public void close() {
            peer.close();
        }
    }

    static final class Session implements WebRtcSession {
        private final WebRtcSessionListener listener;
        private final Consumer<Session> onClosed;
        private final boolean fullIceAnswer;

        private volatile RTCPeerConnection pc;
        private volatile PeerOperations peerOperations;
        private final DataChannelSlots<RTCDataChannel> dataChannels = new DataChannelSlots<>(Session::closeRejectedChannel);

        // Guarded by this: single fire of open/close transitions.
        private boolean openFired;
        private volatile boolean closedFlag;

        // Full ICE answer state, guarded by this. The answer is reported only
        // once BOTH the local description has applied and candidate gathering
        // has completed; the two events arrive on the engine signaling thread
        // but the flags make the order irrelevant.
        private boolean localDescriptionSet;
        private boolean gatheringComplete;
        private boolean fullAnswerDelivered;

        // Guarded by this. The synchronous native addIceCandidate rejects
        // candidates applied while the peer connection has no remote
        // description yet, so candidates arriving before SetRemoteDescription
        // completes are buffered and drained from its success callback.
        private boolean remoteDescriptionSet;
        private List<String> pendingCandidates = new ArrayList<>();

        Session(WebRtcSessionListener listener, Consumer<Session> onClosed, boolean fullIceAnswer) {
            this.listener = listener;
            this.onClosed = onClosed;
            this.fullIceAnswer = fullIceAnswer;
        }

        // Callbacks arrive on native engine threads.
        final PeerConnectionObserver observer = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // In full ICE mode candidates ride inside the answer; there is
                // no trickle channel to signal them on.
                if (!fullIceAnswer && !closedFlag) {
                    listener.onLocalCandidate(candidate.sdp);
                }
            }

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
                if (fullIceAnswer && state == RTCIceGatheringState.COMPLETE) {
                    synchronized (Session.this) {
                        gatheringComplete = true;
                    }
                    maybeDeliverFullAnswer();
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                log.debug("Peer connection state changed: {}", state);
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                    closeInternal(true);
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel dataChannel) {
                if (closedFlag) {
                    closeRejectedChannel(dataChannel);
                    return;
                }
                DataChannelSlots.Parameters parameters = new DataChannelSlots.Parameters(
                        dataChannel.getLabel(), dataChannel.isOrdered(), dataChannel.isReliable(),
                        dataChannel.isNegotiated(), dataChannel.getMaxPacketLifeTime(), dataChannel.getMaxRetransmits());
                if (dataChannels.admit(dataChannel, parameters)) {
                    log.debug("Received data channel: {}", parameters.label());
                    dataChannel.registerObserver(createDataChannelObserver(
                            parameters.reliable(), dataChannel::getState));
                    checkChannels();
                } else {
                    log.debug("Ignored duplicate or invalid data channel: {}", parameters.label());
                }
            }

            @Override
            public void onSelectedCandidatePairChanged(String remoteAddress, int remotePort, String candidateType) {
                try {
                    listener.onRemoteAddress(new InetSocketAddress(remoteAddress, remotePort), candidateType);
                } catch (Exception e) {
                    log.debug("Failed to report remote address: {}", e.getMessage());
                }
            }
        };

        private void start(RTCPeerConnection pc, String offerSdp) {
            start(pc, new NativePeerOperations(pc), offerSdp);
        }

        void start(PeerOperations operations, String offerSdp) {
            start(null, operations, offerSdp);
        }

        private void start(RTCPeerConnection pc, PeerOperations operations, String offerSdp) {
            boolean closed;
            synchronized (this) {
                closed = closedFlag;
                if (!closed) {
                    this.pc = pc;
                    this.peerOperations = operations;
                }
            }
            if (closed) {
                operations.close();
                return;
            }
            runNegotiation("SetRemoteDescription", () -> operations.setRemoteDescription(
                    new RTCSessionDescription(RTCSdpType.OFFER, stripIdentityAttributes(offerSdp)), new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    // A session closed during the handshake (timeout, connect
                    // error, backend close) must not keep negotiating or reach
                    // the listener with a late answer.
                    if (isClosed()) {
                        return;
                    }
                    // The remote description exists from here on; apply
                    // whatever candidates were buffered while it was pending.
                    List<String> drained;
                    synchronized (Session.this) {
                        if (closedFlag) {
                            return;
                        }
                        remoteDescriptionSet = true;
                        drained = pendingCandidates;
                        pendingCandidates = null;
                    }
                    if (drained != null) {
                        for (String candidate : drained) {
                            applyCandidate(candidate);
                        }
                    }
                    runNegotiation("CreateAnswer", () -> operations.createAnswer(new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            if (isClosed()) {
                                return;
                            }
                            runNegotiation("SetLocalDescription", () -> operations.setLocalDescription(description, new SetSessionDescriptionObserver() {
                                @Override
                                public void onSuccess() {
                                    if (isClosed()) {
                                        return;
                                    }
                                    if (fullIceAnswer) {
                                        synchronized (Session.this) {
                                            localDescriptionSet = true;
                                        }
                                        maybeDeliverFullAnswer();
                                    } else {
                                        listener.onAnswerReady(description.sdp);
                                    }
                                }

                                @Override
                                public void onFailure(String error) {
                                    negotiationFailed("SetLocalDescription", error);
                                }
                            }));
                        }

                        @Override
                        public void onFailure(String error) {
                            negotiationFailed("CreateAnswer", error);
                        }
                    }));
                }

                @Override
                public void onFailure(String error) {
                    negotiationFailed("SetRemoteDescription", error);
                }
            }));
        }

        private void runNegotiation(String operation, Runnable action) {
            if (closedFlag) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException cause) {
                negotiationFailed(operation, cause.toString());
            }
        }

        private void negotiationFailed(String operation, String error) {
            if (closeInternal(false)) {
                log.error("{} failed: {}", operation, error);
                listener.onNegotiationFailed(operation + " failed");
            }
        }

        private synchronized boolean isClosed() {
            return closedFlag;
        }

        /**
         * Reports the full ICE answer once the local description is applied
         * AND candidate gathering has completed, whichever happens last. The
         * local description is re-read from the engine at this point because
         * it has accumulated every gathered candidate since it was set.
         */
        private void maybeDeliverFullAnswer() {
            synchronized (this) {
                if (!localDescriptionSet || !gatheringComplete || fullAnswerDelivered || closedFlag) {
                    return;
                }
                fullAnswerDelivered = true;
            }
            PeerOperations operations = this.peerOperations;
            if (operations == null) {
                return;
            }
            RTCSessionDescription local;
            try {
                local = operations.localDescription();
            } catch (RuntimeException cause) {
                negotiationFailed("GetLocalDescription", cause.toString());
                return;
            }
            if (local == null || local.sdp == null) {
                negotiationFailed("GetLocalDescription", "Full ICE answer unavailable after gathering completed");
                return;
            }
            listener.onAnswerReady(markEndOfCandidates(local.sdp));
        }

        /**
         * Appends a=end-of-candidates to a full ICE answer. The engine's local
         * description accumulates candidates but never the terminator, which
         * is only emitted through the trickle path; the spec's example answer
         * carries it, so add it once gathering is known complete.
         */
        private static String markEndOfCandidates(String sdp) {
            if (sdp.contains("a=end-of-candidates")) {
                return sdp;
            }
            return sdp.endsWith("\n") ? sdp + "a=end-of-candidates\r\n" : sdp + "\r\na=end-of-candidates\r\n";
        }

        /**
         * Activation requires both channels. Observers are attached as each
         * arrives so early data can wait in the channel's activation queue.
         */
        private void checkChannels() {
            RTCDataChannel r = this.dataChannels.reliable();
            RTCDataChannel u = this.dataChannels.unreliable();
            if (r == null || u == null || closedFlag) {
                return;
            }

            if (r.getState() == RTCDataChannelState.OPEN) {
                fireOpenOnce();
            }
        }

        RTCDataChannelObserver createDataChannelObserver(boolean reliable, Supplier<RTCDataChannelState> state) {
            return new RTCDataChannelObserver() {
                @Override
                public void onStateChange() {
                    if (!reliable || closedFlag) {
                        return;
                    }
                    RTCDataChannelState observed = state.get();
                    if (observed == RTCDataChannelState.OPEN) {
                        checkChannels();
                    } else if (observed == RTCDataChannelState.CLOSED) {
                        closeInternal(true);
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    if (!closedFlag) {
                        if (reliable) {
                            listener.onMessage(buffer.data);
                        } else {
                            listener.onUnreliableMessage(buffer.data);
                        }
                    }
                }

                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // Despite the legacy parameter name, webrtc-java passes
                    // libwebrtc's sent_data_size here: the number of buffered
                    // bytes that were just written to the wire.
                    if (reliable && !closedFlag) {
                        listener.onBytesSent(previousAmount);
                    }
                }
            };
        }

        private void fireOpenOnce() {
            synchronized (this) {
                if (openFired || closedFlag) {
                    return;
                }
                openFired = true;
            }
            listener.onTransportOpen();
        }

        @Override
        public void send(ByteBuffer data) {
            RTCDataChannel r = this.dataChannels.reliable();
            if (r == null || closedFlag) {
                log.debug("Dropping send on unopened or closed session");
                return;
            }
            try {
                r.sendAsync(new RTCDataChannelBuffer(data, true));
            } catch (Exception e) {
                // The closed check above races teardown; a session closed
                // between it and the native call is the same condition and
                // honors the same contract: the send is dropped, never
                // thrown into the caller's write path.
                log.debug("Dropping send on session closed mid write: {}", e.toString());
            }
        }

        @Override
        public void requestRtt(DoubleConsumer callback) {
            WebRtcRtt.requestRtt(closedFlag ? null : this.pc, callback);
        }

        @Override
        public void addRemoteCandidate(String candidateSdp) {
            synchronized (this) {
                if (closedFlag) {
                    return;
                }
                if (!remoteDescriptionSet) {
                    pendingCandidates.add(candidateSdp);
                    return;
                }
            }
            applyCandidate(candidateSdp);
        }

        private void applyCandidate(String candidateSdp) {
            RTCPeerConnection pc = this.pc;
            if (pc == null) {
                return;
            }
            try {
                pc.addIceCandidate(new RTCIceCandidate("0", 0, candidateSdp));
            } catch (Exception e) {
                log.debug("Failed to apply ICE candidate (connection likely closed): {}", e.toString());
            }
        }

        @Override
        public void close() {
            closeInternal(false);
        }

        private boolean closeInternal(boolean notify) {
            synchronized (this) {
                if (closedFlag) {
                    return false;
                }
                closedFlag = true;
                dataChannels.stopAccepting();
                pendingCandidates = null;
            }
            // One guard per resource: a throwing close must not skip the
            // closes behind it, or the skipped resources leak until the
            // backend disposes its factories.
            RTCDataChannel r = this.dataChannels.reliable();
            if (r != null) {
                try {
                    r.unregisterObserver();
                } catch (Exception e) {
                    log.debug("Error unregistering data channel observer: {}", e.getMessage());
                }
                try {
                    r.close();
                } catch (Exception e) {
                    log.debug("Error closing reliable channel: {}", e.getMessage());
                }
            }
            RTCDataChannel u = this.dataChannels.unreliable();
            if (u != null) {
                try {
                    u.unregisterObserver();
                } catch (Exception e) {
                    log.debug("Error unregistering unreliable channel observer: {}", e.getMessage());
                }
                try {
                    u.close();
                } catch (Exception e) {
                    log.debug("Error closing unreliable channel: {}", e.getMessage());
                }
            }
            PeerOperations operations = this.peerOperations;
            if (operations != null) {
                try {
                    operations.close();
                } catch (Exception e) {
                    log.debug("Error closing peer connection: {}", e.getMessage());
                }
            }
            onClosed.accept(this);
            if (notify) {
                listener.onTransportClosed();
            }
            return true;
        }

        private static void closeRejectedChannel(RTCDataChannel channel) {
            try {
                channel.close();
            } catch (Exception e) {
                log.debug("Error closing rejected data channel: {}", e.getMessage());
            }
        }
    }
}
