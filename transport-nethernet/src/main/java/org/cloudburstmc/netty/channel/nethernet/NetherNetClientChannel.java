package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcRtt;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherClientChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling;
import io.github.sendablemetatype.webrtc.CreateSessionDescriptionObserver;
import io.github.sendablemetatype.webrtc.PeerConnectionFactory;
import io.github.sendablemetatype.webrtc.PeerConnectionObserver;
import io.github.sendablemetatype.webrtc.RTCBundlePolicy;
import io.github.sendablemetatype.webrtc.RTCConfiguration;
import io.github.sendablemetatype.webrtc.RTCDataChannel;
import io.github.sendablemetatype.webrtc.RTCDataChannelBuffer;
import io.github.sendablemetatype.webrtc.RTCDataChannelInit;
import io.github.sendablemetatype.webrtc.RTCDataChannelObserver;
import io.github.sendablemetatype.webrtc.RTCDataChannelState;
import io.github.sendablemetatype.webrtc.RTCIceCandidate;
import io.github.sendablemetatype.webrtc.RTCIceServer;
import io.github.sendablemetatype.webrtc.RTCOfferOptions;
import io.github.sendablemetatype.webrtc.RTCPeerConnection;
import io.github.sendablemetatype.webrtc.RTCPeerConnectionState;
import io.github.sendablemetatype.webrtc.RTCSdpType;
import io.github.sendablemetatype.webrtc.RTCSessionDescription;
import io.github.sendablemetatype.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ConnectionPendingException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private PeerConnectionFactory factory;
    private final boolean ownsFactory;
    private final NetherNetClientSignaling signaling;
    private final Object attemptLock = new Object();

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

    // Monotonic attempt marker, bumped on every handshake retry and close. Async engine
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
     * Creates a client that allocates its PeerConnectionFactory when WebRTC starts and disposes it on close.
     *
     * @param signaling The NetherNetClientSignaling instance for signaling.
     */
    public NetherNetClientChannel(NetherNetClientSignaling signaling) {
        this(null, signaling, true);
    }

    /**
     * Creates a NetherNetClientChannel.
     *
     * @param factory   The caller-owned PeerConnectionFactory, which this channel will not dispose.
     * @param signaling The NetherNetClientSignaling instance for signaling.
     */
    public NetherNetClientChannel(PeerConnectionFactory factory, NetherNetClientSignaling signaling) {
        this(factory, signaling, false);
    }

    private NetherNetClientChannel(PeerConnectionFactory factory, NetherNetClientSignaling signaling, boolean ownsFactory) {
        super(null, null, null);
        this.factory = factory;
        this.ownsFactory = ownsFactory;
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
    protected void requestRttSample(DoubleConsumer callback) {
        WebRtcRtt.requestRtt(this.peerConnection, callback);
    }

    @Override
    protected void doClose() throws Exception {
        if (!isOpen()) {
            return;
        }
        synchronized (attemptLock) {
            attemptGeneration++;
            handshakeComplete = false;
            super.doClose();
        }
        cancelHandshakeTimeout();
        try {
            closeAttemptTransport();
        } finally {
            try {
                if (signaling != null) {
                    try {
                        signaling.removeSignalHandler(this.connectionId);
                    } finally {
                        signaling.close();
                    }
                }
            } finally {
                if (connectPromise != null) {
                    connectPromise.tryFailure(new ClosedChannelException());
                }
                if (ownsFactory && factory != null) {
                    PeerConnectionFactory owned = factory;
                    factory = null;
                    owned.dispose();
                }
            }
        }
    }

    private void closeAttemptTransport() {
        RTCDataChannel reliable = this.reliableChannel;
        RTCDataChannel unreliable = this.unreliableChannel;
        RTCPeerConnection pc = this.peerConnection;
        this.reliableChannel = null;
        this.unreliableChannel = null;
        this.peerConnection = null;
        try {
            if (reliable != null) {
                try {
                    reliable.unregisterObserver();
                } finally {
                    reliable.close();
                }
            }
        } finally {
            try {
                if (unreliable != null) {
                    try {
                        unreliable.unregisterObserver();
                    } finally {
                        unreliable.close();
                    }
                }
            } finally {
                if (pc != null) {
                    pc.close();
                }
            }
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
            if (handshakeComplete) {
                promise.tryFailure(new AlreadyConnectedException());
                return;
            }
            if (connectPromise != null) {
                promise.tryFailure(new ConnectionPendingException());
                return;
            }

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

            NetherNetClientChannel.this.connectPromise = promise;
            eventLoop().execute(() -> startHandshake());
        }
    }

    private void startHandshake() {
        if (!isOpen() || handshakeComplete || connectPromise == null || connectPromise.isDone()) return;
        final int gen = attemptGeneration;

        log.debug("Starting Handshake with Connection ID: {}", Long.toUnsignedString(this.connectionId));

        cancelHandshakeTimeout();

        int handshakeTimeout = this.config().getOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS);
        handshakeTimeoutTask = eventLoop().schedule(() -> {
            if (isCurrentHandshake(gen)) {
                resetAndRetryHandshake();
            }
        }, handshakeTimeout, TimeUnit.MILLISECONDS);

        try {
            signaling.setNotFoundHandler(reason -> executeForAttempt(gen, () ->
                    failHandshake(gen, "Target Network ID " + this.targetNetworkId + " not found or offline", null)));
            signaling.setSignalHandler(this.connectionId, signal -> {
                if (isCurrentAttempt(gen)) {
                    handleSignal(signal);
                }
            });
            signaling.connect(remoteAddress).whenComplete((iceServers, failure) -> executeForAttempt(gen, () -> {
                if (!isCurrentHandshake(gen)) return;
                if (failure != null) {
                    failHandshake(gen, "Signaling connection failed", failure);
                    return;
                }
                try {
                    if (peerConnection == null) {
                        initWebRTC(iceServers);
                        createAndSendOffer();
                    }
                } catch (Exception | LinkageError e) {
                    failHandshake(gen, "Failed to start WebRTC handshake", e);
                }
            }));
        } catch (Exception e) {
            failHandshake(gen, "Signaling connection failed", e);
        }
    }

    private boolean isCurrentAttempt(int generation) {
        return generation == attemptGeneration && isOpen();
    }

    private boolean isCurrentHandshake(int generation) {
        return isCurrentAttempt(generation) && !handshakeComplete && connectPromise != null && !connectPromise.isDone();
    }

    private void executeForAttempt(int generation, Runnable callback) {
        if (!isCurrentAttempt(generation)) return;
        try {
            eventLoop().execute(() -> {
                if (isCurrentAttempt(generation)) {
                    callback.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutdown can race a native or signaling callback after the channel closes.
            if (isCurrentAttempt(generation) && connectPromise != null) {
                connectPromise.tryFailure(e);
            }
        }
    }

    private void failHandshake(int generation, String message, Throwable cause) {
        if (!isCurrentHandshake(generation)) return;
        ConnectException failure = new ConnectException(message);
        if (cause != null) {
            failure.initCause(cause);
        }
        connectPromise.tryFailure(failure);
        cancelHandshakeTimeout();
        close();
    }

    private void cancelHandshakeTimeout() {
        ScheduledFuture<?> timeout = handshakeTimeoutTask;
        handshakeTimeoutTask = null;
        if (timeout != null) {
            timeout.cancel(false);
        }
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
        synchronized (attemptLock) {
            attemptGeneration++;
            discardPendingInbound();
        }
        try {
            closeAttemptTransport();
            signaling.removeSignalHandler(this.connectionId);
            this.cycleConnectionId();
            remoteDescriptionSet = false;
            pendingRemoteCandidates = new java.util.ArrayList<>();
            startHandshake();
        } catch (Exception e) {
            failHandshake(attemptGeneration, "Failed to retry WebRTC handshake", e);
        }
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

        if (factory == null && ownsFactory) {
            factory = new PeerConnectionFactory();
        }
        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                executeForAttempt(gen, () -> {
                    try {
                        signaling.sendSignal(targetNetworkId,
                                NetherNetConstants.buildSignalCandidateAdd(attemptConnectionId, candidate.sdp));
                    } catch (Exception e) {
                        log.debug("Failed to send ICE candidate", e);
                        resetAndRetryHandshake();
                    }
                });
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                executeForAttempt(gen, () -> {
                    if (state == RTCPeerConnectionState.FAILED) {
                        if (!handshakeComplete) {
                            log.debug("PeerConnection failed during handshake; retrying");
                            resetAndRetryHandshake();
                        }
                    } else {
                        log.trace("PeerConnection state changed to {}", state);
                    }
                });
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
                executeForAttempt(gen, () -> pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        executeForAttempt(gen, () -> {
                            try {
                                signaling.sendSignal(targetNetworkId,
                                        NetherNetConstants.buildSignalConnectRequest(attemptConnectionId, description.sdp));
                            } catch (Exception e) {
                                log.debug("Failed to send connect request", e);
                                resetAndRetryHandshake();
                            }
                        });
                    }
                    @Override public void onFailure(String error) { /* Retry handled by timeout */ }
                }));
            }
            @Override public void onFailure(String error) { /* Retry handled by timeout */ }
        });
    }

    private void handleSignal(String signal) {
        final int generation = attemptGeneration;
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

        executeForAttempt(generation, () -> {
            // Re-validate on the event loop: a retry may have cycled the
            // connection id between the check above (signaling thread) and
            // this task running. Inside the task the id, generation, and
            // peer connection mutate together, so passing this check means
            // everything read below belongs to the current attempt.
            if (signalId != this.connectionId) {
                log.debug("Ignored stale signal for ID {} (attempt retried)", idStr);
                return;
            }
            if (!isOpen() || handshakeComplete) return;
            if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE.equals(type)) {
                try {
                    setMaxOutboundMessageSize(NetherNetConstants.parseMaxMessageSize(data));
                } catch (IllegalArgumentException e) {
                    failHandshake(generation, "Invalid remote max-message-size", e);
                    return;
                }
            }
            if (peerConnection == null) return;

            switch (type) {
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE -> {
                    final int gen = attemptGeneration;
                    final RTCPeerConnection pc = peerConnection;
                    pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, data), new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {
                            // Apply candidates that arrived before the answer
                            // finished applying, in arrival order.
                            executeForAttempt(gen, () -> {
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
        RTCDataChannel reliable = pc.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, dataChannelInit(true));
        this.reliableChannel = reliable;
        reliable.registerObserver(createReliableObserver(reliable::getState, gen));
        RTCDataChannel unreliable = pc.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, dataChannelInit(false));
        this.unreliableChannel = unreliable;
        unreliable.registerObserver(createUnreliableObserver(gen));
    }

    static RTCDataChannelInit dataChannelInit(boolean reliable) {
        RTCDataChannelInit init = new RTCDataChannelInit();
        init.ordered = reliable;
        init.protocol = "";
        if (!reliable) {
            init.maxRetransmits = 0;
        }
        return init;
    }

    RTCDataChannelObserver createReliableObserver(Supplier<RTCDataChannelState> state, int generation) {
        return createDataChannelObserver(state, generation, true);
    }

    RTCDataChannelObserver createUnreliableObserver(int generation) {
        return createDataChannelObserver(null, generation, false);
    }

    private RTCDataChannelObserver createDataChannelObserver(Supplier<RTCDataChannelState> state,
                                                           int generation, boolean reliable) {
        return new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                if (!reliable) {
                    return;
                }
                executeForAttempt(generation, () -> {
                    RTCDataChannelState observed = state.get();
                    if (observed == RTCDataChannelState.OPEN && isCurrentHandshake(generation)) {
                        handshakeComplete = true;
                        cancelHandshakeTimeout();
                        markTransportOpen();
                        if (connectPromise != null) {
                            connectPromise.trySuccess();
                        }
                        fireChannelActiveIfReady();
                    } else if (observed == RTCDataChannelState.CLOSED) {
                        if (handshakeComplete) {
                            markTransportClosed();
                            close();
                        } else {
                            resetAndRetryHandshake();
                        }
                    }
                });
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                // The loop already serializes attempts, and overflow may close the native transport inline.
                if (eventLoop().inEventLoop()) {
                    if (isCurrentAttempt(generation)) {
                        deliverInbound(buffer.data, reliable);
                    }
                    return;
                }
                synchronized (attemptLock) {
                    if (isCurrentAttempt(generation)) {
                        deliverInbound(buffer.data, reliable);
                    }
                }
            }

            @Override
            public void onBufferedAmountChange(long previousAmount) {
                if (!reliable) {
                    return;
                }
                // Despite the legacy parameter name, webrtc-java passes
                // libwebrtc's sent_data_size here: the number of buffered
                // bytes that were just written to the wire. Without this
                // report the base class write gate would pause forever once
                // the high water mark is crossed.
                synchronized (attemptLock) {
                    if (isCurrentAttempt(generation)) {
                        onEngineBytesSent(previousAmount);
                    }
                }
            }
        };
    }

    @Override
    protected void sendFramed(io.netty.buffer.ByteBuf framed) {
        RTCDataChannel reliable = this.reliableChannel;
        if (reliable == null) {
            throw new IllegalStateException("Reliable data channel is unavailable");
        }
        reliable.sendAsync(new RTCDataChannelBuffer(toNioBuffer(framed), true));
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
