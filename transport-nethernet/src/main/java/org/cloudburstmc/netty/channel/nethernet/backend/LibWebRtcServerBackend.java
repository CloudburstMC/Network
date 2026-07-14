package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
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
        PeerConnectionFactory factory = factories.get(Math.floorMod(nextFactory.getAndIncrement(), factories.size()));
        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, session.observer);
        session.start(pc, offerSdp);
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

    private static final class Session implements WebRtcSession {
        private final WebRtcSessionListener listener;
        private final Consumer<Session> onClosed;
        private final boolean fullIceAnswer;

        private volatile RTCPeerConnection pc;
        private volatile RTCDataChannel reliable;
        private volatile RTCDataChannel unreliable;

        // Guarded by this: single fire of open/close transitions.
        private boolean observerRegistered;
        private boolean openFired;
        private boolean closedFlag;

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

        private Session(WebRtcSessionListener listener, Consumer<Session> onClosed, boolean fullIceAnswer) {
            this.listener = listener;
            this.onClosed = onClosed;
            this.fullIceAnswer = fullIceAnswer;
        }

        // Callbacks arrive on native engine threads.
        private final PeerConnectionObserver observer = new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // In full ICE mode candidates ride inside the answer; there is
                // no trickle channel to signal them on.
                if (!fullIceAnswer) {
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
                String label = dataChannel.getLabel();
                log.debug("Received data channel: {}", label);
                if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label)) {
                    reliable = dataChannel;
                } else if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label)) {
                    unreliable = dataChannel;
                }
                checkChannels();
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
            synchronized (this) {
                if (closedFlag) {
                    // Session was closed before negotiation began; do not
                    // leave the freshly created peer connection behind.
                    pc.close();
                    return;
                }
                this.pc = pc;
            }
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, stripIdentityAttributes(offerSdp)), new SetSessionDescriptionObserver() {
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
                        remoteDescriptionSet = true;
                        drained = pendingCandidates;
                        pendingCandidates = null;
                    }
                    if (drained != null) {
                        for (String candidate : drained) {
                            applyCandidate(candidate);
                        }
                    }
                    pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            if (isClosed()) {
                                return;
                            }
                            pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
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
                                    log.error("SetLocalDescription failed: {}", error);
                                }
                            });
                        }

                        @Override
                        public void onFailure(String error) {
                            log.error("CreateAnswer failed: {}", error);
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    log.error("SetRemoteDescription failed: {}", error);
                }
            });
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
            RTCPeerConnection pc = this.pc;
            if (pc == null) {
                return;
            }
            RTCSessionDescription local = pc.getLocalDescription();
            if (local == null || local.sdp == null) {
                log.error("Full ICE answer unavailable after gathering completed");
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
         * Once both expected channels have arrived, watch the reliable one:
         * its messages are the session's inbound stream and its OPEN state is
         * the session's open state. The unreliable channel is deliberately
         * unobserved (reliable only transport).
         */
        private void checkChannels() {
            RTCDataChannel r = this.reliable;
            if (r == null || this.unreliable == null) {
                return;
            }
            synchronized (this) {
                if (observerRegistered || closedFlag) {
                    return;
                }
                observerRegistered = true;
            }

            r.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = r.getState();
                    if (state == RTCDataChannelState.OPEN) {
                        fireOpenOnce();
                    } else if (state == RTCDataChannelState.CLOSED) {
                        closeInternal(true);
                    }
                }

                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    listener.onMessage(buffer.data);
                }

                @Override
                public void onBufferedAmountChange(long previousAmount) {
                    // Despite the legacy parameter name, webrtc-java passes
                    // libwebrtc's sent_data_size here: the number of buffered
                    // bytes that were just written to the wire.
                    listener.onBytesSent(previousAmount);
                }
            });

            if (r.getState() == RTCDataChannelState.OPEN) {
                fireOpenOnce();
            }
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
            RTCDataChannel r = this.reliable;
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
        public void requestRtt(java.util.function.DoubleConsumer callback) {
            RTCPeerConnection pc = this.pc;
            if (pc == null || closedFlag) {
                callback.accept(-1);
                return;
            }
            try {
                pc.getStats(report -> callback.accept(WebRtcRtt.extractRttMillis(report)));
            } catch (Exception e) {
                // Races teardown like send; a stats request on a closing
                // connection just reports no measurement.
                callback.accept(-1);
            }
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

        private void closeInternal(boolean notify) {
            synchronized (this) {
                if (closedFlag) {
                    return;
                }
                closedFlag = true;
            }
            try {
                RTCDataChannel r = this.reliable;
                if (r != null) {
                    r.unregisterObserver();
                    r.close();
                }
                RTCDataChannel u = this.unreliable;
                if (u != null) {
                    u.close();
                }
                RTCPeerConnection pc = this.pc;
                if (pc != null) {
                    pc.close();
                }
            } catch (Exception e) {
                log.debug("Error during session teardown: {}", e.getMessage());
            }
            onClosed.accept(this);
            if (notify) {
                listener.onTransportClosed();
            }
        }
    }
}
