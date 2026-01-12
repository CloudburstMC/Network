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

    private volatile long connectionId; // Session ID (Long)
    private volatile String targetNetworkId; // Peer ID (String, for Realms)
    
    private volatile boolean handshakeComplete = false;

    private ChannelPromise connectPromise;

    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    private int retryCount = 0;

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

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        signaling.removeSignalHandler(this.connectionId);
        this.cycleConnectionId();
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

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                try {
                    signaling.sendSignal(
                        targetNetworkId, 
                        NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate.sdp)
                    );
                } catch (Exception e) {
                    log.error("Failed to send ICE candidate", e);
                    eventLoop().execute(() -> resetAndRetryHandshake());
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED) {
                    // Fast fail trigger: retry immediately instead of waiting for timeout
                    log.warn("PeerConnection entered FAILED state, resetting and retrying handshake.");
                    eventLoop().execute(() -> resetAndRetryHandshake());
                } else {
                    log.trace("PeerConnection state changed to {}", state);
                }
            }

            @Override public void onDataChannel(RTCDataChannel dataChannel) { }
        });

        setupDataChannels();
    }

    private void createAndSendOffer() {
        if (peerConnection == null) return;
        peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                if (peerConnection == null) return;
                peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        try {
                            signaling.sendSignal(
                                targetNetworkId, 
                                NetherNetConstants.buildSignalConnectRequest(connectionId, description.sdp)
                            );
                        } catch (Exception e) {
                            log.error("Failed to send Connect Request", e);
                            eventLoop().execute(() -> resetAndRetryHandshake());
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
        try {
            long signalId = Long.parseUnsignedLong(idStr);
            if (signalId != this.connectionId) {
                log.debug("Ignored stale signal for ID {}", idStr);
                return;
            }
        } catch (NumberFormatException e) {
            return;
        }

        eventLoop().execute(() -> {
            if (peerConnection == null) return;
            if (!isOpen() || handshakeComplete) return;

            switch (type) {
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE -> {
                    peerConnection.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, data), new SetSessionDescriptionObserver() {
                        @Override public void onSuccess() {}
                        @Override public void onFailure(String e) { /* Retry handled by timeout */ }
                    });
                }
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    peerConnection.addIceCandidate(new RTCIceCandidate("0", 0, data));
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

    private void setupDataChannels() {
        RTCDataChannelInit reliableInit = new RTCDataChannelInit();
        reliableInit.ordered = true;
        reliableInit.protocol = NetherNetConstants.RELIABLE_CHANNEL_LABEL;

        RTCDataChannelInit unreliableInit = new RTCDataChannelInit();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;

        RTCDataChannel reliable = peerConnection.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
        RTCDataChannel unreliable = peerConnection.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);

        reliable.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onStateChange() {
                if (reliable.getState() == RTCDataChannelState.OPEN) {
                    eventLoop().execute(() -> {
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
                            pipeline().fireChannelActive();
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

    private long cycleConnectionId() {
        this.connectionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return this.connectionId;
    }
}