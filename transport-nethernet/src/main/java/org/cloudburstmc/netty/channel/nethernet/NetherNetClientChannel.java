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
package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherClientChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherConnectionFailure;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jose4j.lang.JoseException;
import tel.schich.libdatachannel.CandidatePair;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelInitSettings;
import tel.schich.libdatachannel.DataChannelReliability;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.LibDataChannel;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetClientChannel extends NetherNetChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetClientChannel.class);

    private final NetherNetClientSignaling signaling;

    private volatile String connectionId; // The token this attempt is known by on the signaling
    private volatile String targetNetworkId; // Peer ID (String, for Realms)

    private volatile boolean handshakeComplete = false;

    private ChannelPromise connectPromise;

    private volatile ScheduledFuture<?> handshakeTimeoutTask;

    private int retryCount = 0;
    /** Whether this attempt's complete offer went out, for signaling that takes it in one piece. */
    private boolean descriptionSent;

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

    private void setTargetNetworkId(String id) {
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
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            NetherNetClientChannel.this.connectPromise = promise;

            if (remote instanceof NetherNetAddress) {
                String targetId = ((NetherNetAddress) remote).getNetworkId();
                NetherNetClientChannel.this.setTargetNetworkId(targetId);
                NetherNetClientChannel.this.remoteAddress = remote;
            } else if (remote instanceof InetSocketAddress) {
                NetherNetClientChannel.this.remoteAddress = (InetSocketAddress) remote;
                NetherNetClientChannel.this.setTargetNetworkId(NetherNetConstants.DISCOVER_TARGET);
            } else {
                promise.setFailure(new IllegalArgumentException("Unsupported address: " + remote.getClass()));
                return;
            }

            eventLoop().execute(() -> startHandshake());
        }
    }

    private void startHandshake() {
        if (!isOpen() || handshakeComplete) {
            return;
        }

        log.debug("Starting Handshake with Connection ID: {}", this.connectionId);

        if (handshakeTimeoutTask != null) {
            handshakeTimeoutTask.cancel(false);
        }

        // Signaling reports from its own threads, so the channel is only touched on the loop
        signaling.setFailureHandler(reason -> eventLoop().execute(() -> {
            String target = remoteAddress instanceof NetherNetAddress
                    ? "network " + this.targetNetworkId : String.valueOf(remoteAddress);
            failConnect(new ConnectException("Signaling to " + target + " failed: " + reason));
        }));

        int handshakeTimeout = this.config().getOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS);
        handshakeTimeoutTask = eventLoop().schedule(() -> {
            resetAndRetryHandshake();
        }, handshakeTimeout, TimeUnit.MILLISECONDS);

        signaling.setSignalHandler(this.connectionId, this::handleSignal);

        // Loaded here rather than under the first peer, so a missing native fails the connect
        // with its own cause instead of a handshake that never starts
        try {
            LibDataChannel.initialize();
        } catch (LinkageError e) {
            failConnect(connectException("The libdatachannel native library is not available", e));
            return;
        }

        TokenTrust serverTrust = this.config.getOption(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST);
        if (serverTrust != null && retryCount == 0) {
            // Off the loop, since a trust that fetches keys would otherwise do so on it
            CompletableFuture.runAsync(serverTrust::prepare);
        }

        signaling.connect(remoteAddress).thenAcceptAsync(iceServers -> {
            if (handshakeComplete) {
                return;
            }
            try {
                // If this is a retry, peerConnection might be null, so we recreate it
                if (peerConnection == null) {
                    initWebRTC(iceServers);
                    createAndSendOffer();
                }
            } catch (Exception e) {
                failConnect(connectException("Failed to start WebRTC handshake", e));
            }
        }, eventLoop()).exceptionally(e -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            failConnect(connectException("Signaling connection failed", cause));
            return null;
        });
    }

    /** Fails the connect and closes the channel. Nothing happens once the connect is decided. */
    private void failConnect(ConnectException cause) {
        if (connectPromise != null && !connectPromise.isDone()) {
            connectPromise.tryFailure(cause);
        }
        close();
    }

    private static ConnectException connectException(String message, Throwable cause) {
        ConnectException exception = new ConnectException(message + ": " + cause.getMessage());
        exception.initCause(cause);
        return exception;
    }

    private void resetAndRetryHandshake() {
        if (!isOpen()) {
            return;
        }
        if (connectPromise != null && connectPromise.isDone() && !connectPromise.isSuccess()) {
            return;
        }
        if (handshakeComplete) {
            return;
        }

        // Fail once the attempts are spent. Signaling that sends the offer in one piece gets one:
        // the server would refuse a repeat as a duplicate join while the first is still pending
        int attempts = retryCount + 1;
        int maxAttempts = this.config().getOption(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS);
        if (attempts >= maxAttempts || !signaling.usesTrickleIce()) {
            connectionFailed(NetherConnectionFailure.HANDSHAKE_TIMEOUT);
            failConnect(new ConnectException("Connection timed out after " + attempts
                    + (attempts == 1 ? " attempt" : " attempts")));
            return;
        }

        NetherChannelMetrics metrics = config.getMetrics();
        if (metrics != null) {
            metrics.handshakeRetry();
        }

        retryCount++;
        // Otherwise the first attempt's PEER_FAILED swallows the HANDSHAKE_TIMEOUT that ends them.
        clearFailureReported();
        descriptionSent = false;
        closeWebRTC();

        signaling.removeSignalHandler(this.connectionId);
        this.cycleConnectionId();
        startHandshake();
    }

    private void initWebRTC(List<IceServerInfo> iceServers) {
        PeerConnectionConfiguration configured =
                this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG);
        PeerConnectionConfiguration rtcConfig = configured
                .withDisableAutoNegotiation(true)
                .withIceServers(withIceServers(configured, iceServers));

        peerConnection = PeerConnection.createPeer(rtcConfig);
        registerMetrics(peerConnection);

        // Registering is what arms the native callback, so it must happen before anything can fire it
        peerConnection.onLocalCandidate.register((peer, candidate, mediaId) -> {
            // Without trickle the candidates travel inside the description once gathering is done
            if (!signaling.usesTrickleIce()) {
                return;
            }
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

        peerConnection.onGatheringStateChange.register((peer, state) -> {
            if (state == GatheringState.RTC_GATHERING_COMPLETE) {
                onGatheringComplete(peer);
            }
        });

        peerConnection.onStateChange.register((peer, state) -> {
            if (state == PeerState.RTC_CONNECTED) {
                adoptSelectedPair(peer);
            } else if (state == PeerState.RTC_FAILED) {
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
        if (peerConnection == null) {
            return;
        }

        // Not null for autodetection, that path releases an unset string in JNI and crashes the JVM
        peerConnection.setLocalDescription("offer");

        // Anything without trickle sends the offer once from onGatheringComplete instead
        if (!signaling.usesTrickleIce()) {
            return;
        }
        try {
            String offer = signed(peerConnection.localDescription());
            signaling.sendSignal(targetNetworkId, NetherNetConstants.buildSignalConnectRequest(connectionId, offer));
        } catch (Exception e) {
            log.error("Failed to send Connect Request", e);
            eventLoop().execute(() -> resetAndRetryHandshake());
        }
    }

    /**
     * Sends the offer with every gathered candidate in it, for signaling that is one exchange
     * rather than a stream of candidates.
     */
    private void onGatheringComplete(PeerConnection peer) {
        if (signaling.usesTrickleIce()) {
            return;
        }

        String local;
        try {
            // Read from the peer that gathered, which a retry may already have replaced
            local = peer.localDescription();
        } catch (Exception e) {
            log.warn("Gathering complete for {} but the local description is unavailable: {}",
                    connectionId, e.toString());
            return;
        }

        eventLoop().execute(() -> {
            if (peer != peerConnection || descriptionSent || handshakeComplete || !isOpen()) {
                return;
            }
            descriptionSent = true;
            try {
                signaling.sendDescription(targetNetworkId, signed(local));
            } catch (Exception e) {
                log.error("Failed to send the offer for {}", connectionId, e);
                resetAndRetryHandshake();
            }
        });
    }

    /**
     * The selected pair is where the media flows, which is rarely the signaling endpoint. A
     * network id address stays, since it names the peer rather than a place.
     */
    private void adoptSelectedPair(PeerConnection peer) {
        if (!(remoteAddress instanceof InetSocketAddress)) {
            return;
        }
        try {
            CandidatePair pair = peer.selectedCandidatePair();
            this.localAddress = pair.local();
            this.remoteAddress = pair.remote();
        } catch (Exception e) {
            log.debug("Selected pair unavailable for {}: {}", connectionId, e.toString());
        }
    }

    /** The offer with this side's identity assertion when one is configured, unsigned otherwise. */
    private String signed(String offer) throws JoseException {
        OperatorIdentity identity = this.config.getOption(NetherChannelOption.NETHER_CLIENT_IDENTITY);
        return identity == null ? offer : identity.withAssertion(offer);
    }

    private void handleSignal(String signal) {
        NetherNetConstants.Signal parsed = NetherNetConstants.parseSignal(signal);
        if (parsed == null) {
            return;
        }

        // Verify this signal belongs to the current attempt
        if (!parsed.connectionId().equals(this.connectionId)) {
            log.debug("Ignored stale signal for ID {}", parsed.connectionId());
            return;
        }
        String data = parsed.payload();

        eventLoop().execute(() -> {
            if (peerConnection == null) {
                return;
            }
            if (!isOpen() || handshakeComplete) {
                return;
            }

            switch (parsed.type()) {
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE -> {
                    TokenTrust trust = this.config.getOption(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST);
                    if (trust != null) {
                        try {
                            IdentityUtils.validateSdp(data, trust);
                        } catch (Exception e) {
                            failConnect(connectException("The server's identity was refused", e));
                            return;
                        }
                    }
                    try {
                        peerConnection.setRemoteDescription(data, SessionDescriptionType.ANSWER);
                    } catch (Exception e) {
                        log.debug("Failed to apply answer for {}: {}", connectionId,
                                e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    try {
                        peerConnection.addRemoteCandidate(data);
                    } catch (Exception e) {
                        log.debug("Failed to apply ICE candidate for {}: {}", connectionId,
                                e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.error("Received SIGNAL_CONNECT_ERROR for {}.", this.connectionId);
                    failConnect(new ConnectException("Remote peer sent connect error."));
                }
                default -> {
                    log.debug("Received unknown signal type: {}", parsed.type());
                }
            }
        });
    }

    private void setupDataChannels() {
        DataChannelInitSettings reliableInit = DataChannelInitSettings.DEFAULT;

        DataChannelInitSettings unreliableInit = DataChannelInitSettings.DEFAULT
                .withReliability(new DataChannelReliability(true, true, 0L, 0));

        DataChannel reliable =
                peerConnection.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, reliableInit);
        DataChannel unreliable =
                peerConnection.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL, unreliableInit);

        reliable.onOpen.register(channel -> eventLoop().execute(() -> {
            if (handshakeComplete) {
                return;
            }

            log.debug("NetherNet Connection Established!");
            handshakeComplete = true;

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

    /** A uint64 as text, which is what the docs describe and what a retail peer expects to echo. */
    private String cycleConnectionId() {
        this.connectionId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        return this.connectionId;
    }
}
