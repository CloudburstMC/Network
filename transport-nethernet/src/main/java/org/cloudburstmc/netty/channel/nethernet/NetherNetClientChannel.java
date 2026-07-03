package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherClientChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCDataChannelBuffer;
import dev.kastle.webrtc.RTCDataChannelInit;
import dev.kastle.webrtc.RTCDataChannelObserver;
import dev.kastle.webrtc.RTCDataChannelState;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCOfferOptions;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private final PeerConnectionFactory factory;
    private final NetherNetClientSignaling signaling;

    // The client channel talks to libwebrtc directly (it predates the server
    // side backend seam); its data channel handling mirrors the seam's
    // semantics: raw framed messages into the pipeline, framing done by the
    // NetherNetFramingCodec in the pipeline.
    private volatile RTCPeerConnection peerConnection;
    private volatile RTCDataChannel reliableChannel;
    private volatile RTCDataChannel unreliableChannel;

    private volatile long connectionId; // Session ID (Long)
    private volatile String targetNetworkId; // Peer ID (String, for Realms)
    
    private volatile boolean handshakeComplete = false;

    private ChannelPromise connectPromise;

    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    private int retryCount = 0;

    // Monotonic attempt marker, bumped on every handshake retry. Async engine
    // callbacks belonging to a previous attempt (offer creation, description
    // observers, data channel state changes) capture their generation and
    // bail once a retry has moved past them, so a delayed stale callback can
    // no longer mutate the replacement attempt's state.
    private volatile int attemptGeneration;

    // Event loop confined. The synchronous native addIceCandidate rejects
    // candidates applied while the peer connection has no remote description
    // yet, so candidates arriving before the CONNECT_RESPONSE answer has been
    // applied are buffered and drained once it succeeds.
    private boolean remoteDescriptionSet;
    private java.util.List<String> pendingRemoteCandidates = new java.util.ArrayList<>();

    /**
     * Creates a NetherNetClientChannel with a new PeerConnectionFactory.
     * 
     * @param signaling The NetherNetClientSignaling instance for signaling.
     */
    public NetherNetClientChannel(NetherNetClientSignaling signaling) {
        this(new PeerConnectionFactory(), signaling);
    }

    /**
     * Creates a NetherNetClientChannel.
     * 
     * @param factory   The PeerConnectionFactory to use. Should be reused where possible.
     * @param signaling The NetherNetClientSignaling instance for signaling.
     */
    public NetherNetClientChannel(PeerConnectionFactory factory, NetherNetClientSignaling signaling) {
        super(null, null, null);
        this.factory = factory;
        this.signaling = signaling;
        this.connectionId = this.cycleConnectionId();
        this.config = new DefaultNetherClientChannelConfig(this);
    }

    public void setTargetNetworkId(String id) {
        this.targetNetworkId = id;
    }

    @Override
    public boolean isActive() {
        return super.isActive() && handshakeComplete;
    }

    @Override
    protected void doClose() throws Exception {
        super.doClose();
        RTCDataChannel reliable = this.reliableChannel;
        if (reliable != null) {
            reliable.unregisterObserver();
            reliable.close();
        }
        RTCDataChannel unreliable = this.unreliableChannel;
        if (unreliable != null) {
            unreliable.close();
        }
        RTCPeerConnection pc = this.peerConnection;
        if (pc != null) {
            pc.close();
        }
        if (handshakeTimeoutTask != null) {
            handshakeTimeoutTask.cancel(false);
        }
        if (signaling != null) {
            signaling.removeSignalHandler(this.connectionId);
            signaling.close();
        }
        if (connectPromise != null && !connectPromise.isDone()) {
            connectPromise.tryFailure(new ClosedChannelException());
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new NetherNetClientUnsafe();
    }

    private class NetherNetClientUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) return;
            NetherNetClientChannel.this.connectPromise = promise;

            if (remote instanceof NetherNetAddress) {
                String targetId = ((NetherNetAddress) remote).getNetworkId();
                NetherNetClientChannel.this.setTargetNetworkId(targetId);
                NetherNetClientChannel.this.remoteAddress = remote;
            } else if (remote instanceof InetSocketAddress) {
                NetherNetClientChannel.this.remoteAddress = (InetSocketAddress) remote;
                NetherNetClientChannel.this.setTargetNetworkId("0"); // "0" triggers auto-discovery in signaling
            } else {
                promise.setFailure(new IllegalArgumentException("Unsupported address: " + remote.getClass()));
                return;
            }

            eventLoop().execute(() -> startHandshake());
        }
    }

    private void startHandshake() {
        if (!isOpen() || handshakeComplete) return;

        log.debug("Starting Handshake with Connection ID: {}", Long.toUnsignedString(this.connectionId));

        if (handshakeTimeoutTask != null) handshakeTimeoutTask.cancel(false);

        signaling.setNotFoundHandler(reason -> {
            if (connectPromise != null && !connectPromise.isDone()) {
                connectPromise.tryFailure(new ConnectException("Target Network ID " + this.targetNetworkId + " not found or offline."));
            }
            close();
        });

        int handshakeTimeout = this.config().getOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS);
        handshakeTimeoutTask = eventLoop().schedule(() -> {
            resetAndRetryHandshake();
        }, handshakeTimeout, TimeUnit.MILLISECONDS);

        signaling.setSignalHandler(this.connectionId, this::handleSignal);

        signaling.connect(remoteAddress).thenAcceptAsync(iceServers -> {
            if (handshakeComplete) return; 
            try {
                // If this is a retry, peerConnection might be null, so we recreate it
                if (peerConnection == null) {
                    initWebRTC(iceServers);
                    createAndSendOffer();
                }
            } catch (Exception e) {
                ConnectException ce = new ConnectException("Failed to start WebRTC handshake: " + e.getMessage());
                ce.initCause(e);
                if (connectPromise != null && !connectPromise.isDone()) connectPromise.tryFailure(ce);
                if (handshakeTimeoutTask != null) handshakeTimeoutTask.cancel(false);
                close();
            }
        }, eventLoop()).exceptionally(e -> {
            ConnectException ce = new ConnectException("Signaling connection failed: " + e.getMessage());
            ce.initCause(e);
            if (connectPromise != null && !connectPromise.isDone()) connectPromise.tryFailure(ce);
            if (handshakeTimeoutTask != null) handshakeTimeoutTask.cancel(false);
            close();
            return null;
        });
    }

    private void resetAndRetryHandshake() {
        if (!isOpen()) return;
        if (connectPromise != null && connectPromise.isDone() && !connectPromise.isSuccess()) return;
        if (handshakeComplete) return;

        // fail exceptionally if max retries reached
        int maxRetries = this.config().getOption(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS);
        if (retryCount >= maxRetries) {
            if (connectPromise != null && !connectPromise.isDone()) {
                connectPromise.tryFailure(new ConnectException("Connection timed out after " + retryCount + " retries"));
            }
            close();
            return;
        }

        retryCount++;
        attemptGeneration++;

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        signaling.removeSignalHandler(this.connectionId);
        this.cycleConnectionId();
        remoteDescriptionSet = false;
        pendingRemoteCandidates = new java.util.ArrayList<>();
        startHandshake();
    }

    private void initWebRTC(List<NetherNetSignaling.IceServerInfo> iceServers) {
        RTCConfiguration rtcConfig = new RTCConfiguration();
        rtcConfig.portAllocatorConfig = this.config.getOption(NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG);
        rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

        if (iceServers != null) {
            for (NetherNetSignaling.IceServerInfo info : iceServers) {
                RTCIceServer iceServer = new RTCIceServer();
                iceServer.urls = info.urls();
                iceServer.username = info.username();
                iceServer.password = info.password();
                rtcConfig.iceServers.add(iceServer);
            }
        }

        final int gen = attemptGeneration;
        final long attemptConnectionId = this.connectionId;

        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                if (gen != attemptGeneration) {
                    return;
                }
                try {
                    signaling.sendSignal(
                        targetNetworkId,
                        NetherNetConstants.buildSignalCandidateAdd(attemptConnectionId, candidate.sdp)
                    );
                } catch (Exception e) {
                    log.error("Failed to send ICE candidate", e);
                    eventLoop().execute(() -> {
                        if (gen == attemptGeneration) {
                            resetAndRetryHandshake();
                        }
                    });
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED) {
                    // Fast fail trigger: retry immediately instead of waiting for timeout
                    log.warn("PeerConnection entered FAILED state, resetting and retrying handshake.");
                    eventLoop().execute(() -> {
                        if (gen == attemptGeneration) {
                            resetAndRetryHandshake();
                        }
                    });
                } else {
                    log.trace("PeerConnection state changed to {}", state);
                }
            }

            @Override public void onDataChannel(RTCDataChannel dataChannel) { }
        });
        this.peerConnection = pc;

        setupDataChannels(pc, gen);
    }

    private void createAndSendOffer() {
        final RTCPeerConnection pc = this.peerConnection;
        final int gen = attemptGeneration;
        final long attemptConnectionId = this.connectionId;
        if (pc == null) return;
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                if (gen != attemptGeneration) return;
                pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        if (gen != attemptGeneration) return;
                        try {
                            signaling.sendSignal(
                                targetNetworkId,
                                NetherNetConstants.buildSignalConnectRequest(attemptConnectionId, description.sdp)
                            );
                        } catch (Exception e) {
                            log.error("Failed to send Connect Request", e);
                            eventLoop().execute(() -> {
                                if (gen == attemptGeneration) {
                                    resetAndRetryHandshake();
                                }
                            });
                        }
                    }
                    @Override public void onFailure(String error) { /* Retry handled by timeout */ }
                });
            }
            @Override public void onFailure(String error) { /* Retry handled by timeout */ }
        });
    }

    private void handleSignal(String signal) {
        String[] parts = signal.split(" ", 3);
        if (parts.length < 2) return; // Allow length 2 for ERROR packets without payload
        String type = parts[0];
        String idStr = parts[1].trim();
        String data = parts.length > 2 ? parts[2] : "";

        // Verify this signal belongs to the current attempt
        final long signalId;
        try {
            signalId = Long.parseUnsignedLong(idStr);
            if (signalId != this.connectionId) {
                log.debug("Ignored stale signal for ID {}", idStr);
                return;
            }
        } catch (NumberFormatException e) {
            return;
        }

        eventLoop().execute(() -> {
            // Re-validate on the event loop: a retry may have cycled the
            // connection id between the check above (signaling thread) and
            // this task running. Inside the task the id, generation, and
            // peer connection mutate together, so passing this check means
            // everything read below belongs to the current attempt.
            if (signalId != this.connectionId) {
                log.debug("Ignored stale signal for ID {} (attempt retried)", idStr);
                return;
            }
            if (peerConnection == null) return;
            if (!isOpen() || handshakeComplete) return;

            switch (type) {
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE -> {
                    // Fragment outbound data no larger than the remote advertised
                    // it can receive (a=max-message-size in its answer).
                    setMaxOutboundMessageSize(NetherNetConstants.parseMaxMessageSize(data, NetherNetConstants.MAX_SCTP_MESSAGE_SIZE));
                    final int gen = attemptGeneration;
                    final RTCPeerConnection pc = peerConnection;
                    pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, data), new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {
                            // Apply candidates that arrived before the answer
                            // finished applying, in arrival order.
                            eventLoop().execute(() -> {
                                if (gen != attemptGeneration) return;
                                remoteDescriptionSet = true;
                                java.util.List<String> drained = pendingRemoteCandidates;
                                pendingRemoteCandidates = new java.util.ArrayList<>();
                                for (String candidate : drained) {
                                    applyRemoteCandidate(candidate);
                                }
                            });
                        }
                        @Override public void onFailure(String e) { /* Retry handled by timeout */ }
                    });
                }
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    if (remoteDescriptionSet) {
                        applyRemoteCandidate(data);
                    } else {
                        pendingRemoteCandidates.add(data);
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.error("Received SIGNAL_CONNECT_ERROR for {}.", Long.toUnsignedString(this.connectionId));
                    if (connectPromise != null && !connectPromise.isDone()) {
                        connectPromise.tryFailure(new ConnectException("Remote peer sent connect error."));
                    }
                    close();
                }
                default -> {
                    log.debug("Received unknown signal type: {}", type);
                }
            }
        });
    }

    private void setupDataChannels(RTCPeerConnection pc, int gen) {
        RTCDataChannelInit reliableInit = new RTCDataChannelInit();
        reliableInit.ordered = true;
        reliableInit.protocol = NetherNetConstants.RELIABLE_CHANNEL_LABEL;

        RTCDataChannelInit unreliableInit = new RTCDataChannelInit();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;

        RTCDataChannel reliable = pc.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
        RTCDataChannel unreliable = pc.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);

        reliable.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                if (reliable.getState() == RTCDataChannelState.OPEN) {
                    eventLoop().execute(() -> {
                        if (gen != attemptGeneration) {
                            return;
                        }
                        if (!handshakeComplete) {
                            log.debug("NetherNet Connection Established!");
                            handshakeComplete = true;
                            
                            // Cancel timeout now that we are done
                            if (handshakeTimeoutTask != null) {
                                handshakeTimeoutTask.cancel(false);
                            }
                            
                            setDataChannels(reliable, unreliable);
                            if (connectPromise != null && !connectPromise.isDone()) {
                                connectPromise.trySuccess();
                            }
                            fireChannelActiveIfReady();
                        }
                    });
                }
            }
            @Override public void onBufferedAmountChange(long previousAmount) {}
            @Override public void onMessage(RTCDataChannelBuffer buffer) {
                ReferenceCountUtil.release(buffer);
            }
        });
    }

    /**
     * Adopts the negotiated data channels: watches the reliable channel and
     * delivers its raw framed messages into the pipeline. Reliable only, as
     * on the server side; the unreliable channel is stored but never
     * observed.
     */
    private void setDataChannels(RTCDataChannel reliable, RTCDataChannel unreliable) {
        this.reliableChannel = reliable;
        this.unreliableChannel = unreliable;

        reliable.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                if (reliable.getState() == RTCDataChannelState.CLOSED) {
                    markTransportClosed();
                    close();
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                deliverInbound(buffer.data);
            }

            @Override
            public void onBufferedAmountChange(long previousAmount) {
                // Despite the legacy parameter name, webrtc-java passes
                // libwebrtc's sent_data_size here: the number of buffered
                // bytes that were just written to the wire. Without this
                // report the base class write gate would pause forever once
                // the high water mark is crossed.
                onEngineBytesSent(previousAmount);
            }
        });

        markTransportOpen();
    }

    @Override
    protected void sendFramed(io.netty.buffer.ByteBuf framed) {
        RTCDataChannel reliable = this.reliableChannel;
        if (reliable != null) {
            reliable.sendAsync(new RTCDataChannelBuffer(toNioBuffer(framed), true));
        }
    }

    private void applyRemoteCandidate(String candidateSdp) {
        RTCPeerConnection pc = this.peerConnection;
        if (pc == null) {
            return;
        }
        try {
            pc.addIceCandidate(new RTCIceCandidate("0", 0, candidateSdp));
        } catch (Exception e) {
            log.debug("Failed to apply ICE candidate (connection likely closed): {}", e.toString());
        }
    }

    private long cycleConnectionId() {
        this.connectionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return this.connectionId;
    }
}
