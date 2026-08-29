package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherClientChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelInitSettings;
import tel.schich.libdatachannel.DataChannelReliability;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private final NetherNetClientSignaling signaling;

    private volatile long connectionId; // Session ID (Long)
    private volatile String targetNetworkId; // Peer ID (String, for Realms)

    private volatile boolean handshakeComplete = false;

    private ChannelPromise connectPromise;

    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    private int retryCount = 0;

    /**
     * Creates a NetherNetClientChannel.
     *
     * @param signaling The NetherNetClientSignaling instance for signaling.
     */
    public NetherNetClientChannel(NetherNetClientSignaling signaling) {
        super(null, null, null);
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
        closeWebRTC();

        signaling.removeSignalHandler(this.connectionId);
        this.cycleConnectionId();
        startHandshake();
    }

    private void initWebRTC(List<NetherNetSignaling.IceServerInfo> iceServers) {
        PeerConnectionConfiguration rtcConfig = this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG)
            .withDisableAutoNegotiation(true)
            .withIceServers(iceServers.stream().map(IceServerInfo::toUris).flatMap(List::stream).toList());

        peerConnection = PeerConnection.createPeer(rtcConfig);

        // Registering is what arms the native callback, so it must happen before anything can fire it
        peerConnection.onLocalCandidate.register((peer, candidate, mediaId) -> {
            try {
                signaling.sendSignal(
                    targetNetworkId,
                    NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate)
                );
            } catch (Exception e) {
                log.error("Failed to send ICE candidate", e);
                eventLoop().execute(() -> resetAndRetryHandshake());
            }
        });

        peerConnection.onStateChange.register((peer, state) -> {
            if (state == PeerState.RTC_FAILED) {
                // Fast fail trigger: retry immediately instead of waiting for timeout
                log.warn("PeerConnection entered FAILED state, resetting and retrying handshake.");
                eventLoop().execute(() -> resetAndRetryHandshake());
            } else {
                log.trace("PeerConnection state changed to {}", state);
            }
        });

        setupDataChannels();
    }

    private void createAndSendOffer() {
        if (peerConnection == null) return;

        // Not null for autodetection, that path releases an unset string in JNI and crashes the JVM
        peerConnection.setLocalDescription("offer");
        try {
            signaling.sendSignal(
                targetNetworkId,
                NetherNetConstants.buildSignalConnectRequest(connectionId, peerConnection.localDescription())
            );
        } catch (Exception e) {
            log.error("Failed to send Connect Request", e);
            eventLoop().execute(() -> resetAndRetryHandshake());
        }
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
                    try {
                        peerConnection.setRemoteDescription(data, SessionDescriptionType.ANSWER);
                    } catch (Exception e) {
                        log.debug("Failed to apply answer for {}: {}", Long.toUnsignedString(connectionId), e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    try {
                        peerConnection.addRemoteCandidate(data);
                    } catch (Exception e) {
                        log.debug("Failed to apply ICE candidate for {}: {}", Long.toUnsignedString(connectionId), e.toString());
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

    private void setupDataChannels() {
        DataChannelInitSettings reliableInit = DataChannelInitSettings.DEFAULT;

        DataChannelInitSettings unreliableInit = DataChannelInitSettings.DEFAULT
            .withReliability(new DataChannelReliability(true, true, 0L, 0));

        DataChannel reliable = peerConnection.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
        DataChannel unreliable = peerConnection.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);

        reliable.onOpen.register(channel -> eventLoop().execute(() -> {
            if (handshakeComplete) return;

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
        }));
    }

    private long cycleConnectionId() {
        this.connectionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        return this.connectionId;
    }
}