package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.jspecify.annotations.Nullable;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jose4j.lang.JoseException;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final DefaultNetherServerChannelConfig config;
    private final NetherNetServerSignaling signaling;

    private InetSocketAddress localAddress;
    private volatile boolean open = true;

    private ServerIdentity serverIdentity;

    /**
     * Creates a NetherNetServerChannel.
     *
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(NetherNetServerSignaling signaling) {
        this.signaling = signaling;
        this.config = new DefaultNetherServerChannelConfig(this);

        // Prefer the signaling identity so answers are signed with a key clients can attribute to us
        this.serverIdentity = signaling.serverIdentity();
        if (this.serverIdentity == null) {
            try {
                this.serverIdentity = ServerIdentity.generate("self");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (!(localAddress instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }
        this.localAddress = (InetSocketAddress) localAddress;

        this.signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp, clientAddress, player) -> {
            acceptConnection(connectionId, offerSdp, remoteNetworkId, clientAddress, player);
        });

        this.signaling.bind(localAddress, eventLoop());
    }

    /**
     * Pins ICE to the bound address, so the transport uses one predictable port rather than an
     * ephemeral one per connection. Skipped when the signaling holds that UDP port itself.
     *
     * @param config The configuration to derive from.
     * @return The configuration with the bound address applied.
     */
    private PeerConnectionConfiguration bindIce(PeerConnectionConfiguration config) {
        if (localAddress == null || !signaling.allowsIceOnLocalPort()) {
            return config;
        }

        // A wildcard bind is left unset so ICE keeps gathering on every interface
        InetAddress host = localAddress.getAddress();
        if (host != null && !host.isAnyLocalAddress()) {
            config = config.withBindAddress(host);
        }

        int port = localAddress.getPort();
        if (port <= 0) {
            return config;
        }

        // Enable multiplexing and set the port
        return config
                .withEnableIceUdpMux(true)
                .withPortRangeBegin((short) port)
                .withPortRangeEnd((short) port);
    }

    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
        acceptConnection(connectionId, offerSdp, remoteNetworkId, null, null);
    }

    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId,
                                 @Nullable InetSocketAddress clientAddress) {
        acceptConnection(connectionId, offerSdp, remoteNetworkId, clientAddress, null);
    }

    /**
     * @param clientAddress The address the peer signalled from, or null if it is not known. ICE
     *                      replaces it with the negotiated pair once the connection is up, but
     *                      until then it is all the child channel has to report.
     */
    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId,
                                 @Nullable InetSocketAddress clientAddress, @Nullable PlayerInfo player) {
        PeerConnectionConfiguration rtcConfig =
                bindIce(this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG))
                        .withDisableAutoNegotiation(true)
                        .withIceServers(
                                this.signaling.getIceServers().stream().map(IceServerInfo::toUris).flatMap(List::stream)
                                        .toList());

        ServerPeerConnectionObserver observer = new ServerPeerConnectionObserver(connectionId, remoteNetworkId);
        PeerConnection pc = PeerConnection.createPeer(rtcConfig);
        observer.setPeerConnection(pc);

        NetherNetChildChannel child = new NetherNetChildChannel(this,
                pc, clientAddress == null ? new InetSocketAddress(0) : clientAddress, localAddress);
        child.attr(NetherNetChildChannel.CONNECTION_ID).set(connectionId);
        if (player != null) {
            child.attr(NetherNetChildChannel.PLAYER_INFO).set(player);
        }
        observer.setChildChannel(child);

        child.closeFuture().addListener(future -> signaling.removeSignalHandler(connectionId));

        int handshakeTimeoutSeconds =
                this.config.getOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS);
        ScheduledFuture<?> timeoutTask = eventLoop().schedule(() -> {
            if (!child.isActive()) {
                log.warn("Connection {} timed out during handshake ({}s)", Long.toUnsignedString(connectionId),
                        handshakeTimeoutSeconds);
                child.close();
                pc.close();
            }
        }, handshakeTimeoutSeconds, TimeUnit.SECONDS);
        observer.setHandshakeTimeout(timeoutTask);

        observer.register(pc);

        // Register Signal Handler
        signaling.setSignalHandler(connectionId, (signal) -> {
            String[] parts = signal.split(" ", 3);
            if (parts.length < 3) {
                return;
            }
            String type = parts[0];
            String data = parts[2];

            switch (type) {
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    log.trace("Applying Remote Candidate for {}: {}", Long.toUnsignedString(connectionId), data);
                    try {
                        pc.addRemoteCandidate(data);
                    } catch (Exception e) {
                        log.debug("Failed to apply ICE candidate for {} (Connection likely closed): {}",
                                Long.toUnsignedString(connectionId), e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.debug("Received CONNECT_ERROR for {}", Long.toUnsignedString(connectionId));
                    child.close();
                }
            }
        });

        // Handle Offer
        try {
            pc.setRemoteDescription(offerSdp, SessionDescriptionType.OFFER);
            log.trace("Remote description set for {}", Long.toUnsignedString(connectionId));
            pc.setLocalDescription("answer");
        } catch (Exception e) {
            log.error("Failed to negotiate answer for {}", Long.toUnsignedString(connectionId), e);
            abandon(connectionId, timeoutTask, pc);
            return;
        }

        // Anything without trickle answers once from onGatheringStateChange instead
        if (signaling.usesTrickleIce()) {
            log.trace("Sending Answer SDP for {}", Long.toUnsignedString(connectionId));
            try {
                signaling.sendSignal(
                        remoteNetworkId,
                        NetherNetConstants.buildSignalConnectResponse(connectionId,
                                serverIdentity.augmentAnswer(pc.localDescription()))
                );
            } catch (JoseException e) {
                log.error("Failed to send Answer SDP for {}", Long.toUnsignedString(connectionId), e);
                abandon(connectionId, timeoutTask, pc);
                return;
            }
        }

        pipeline().fireChannelRead(child);
    }

    /**
     * Tears down a connection that failed before its child channel reached the pipeline. The child is
     * left alone as it was never registered with an event loop, so closing it would throw.
     *
     * @param connectionId The connection being abandoned.
     * @param timeoutTask  The handshake timeout to cancel.
     * @param pc           The peer connection to close.
     */
    private void abandon(long connectionId, ScheduledFuture<?> timeoutTask, PeerConnection pc) {
        timeoutTask.cancel(false);
        signaling.removeSignalHandler(connectionId);
        NetherNetChannel.deregisterAll(pc);
        pc.close();
    }

    /**
     * Observer to handle Data Channel creation from the client.
     */
    private class ServerPeerConnectionObserver {
        private final long connectionId;
        private final String remoteNetworkId;
        private NetherNetChildChannel child;

        private DataChannel reliable;
        private DataChannel unreliable;

        private ScheduledFuture<?> handshakeTimeout;

        private PeerConnection peerConnection;
        private volatile boolean fullSdpSent = false;

        public ServerPeerConnectionObserver(long connectionId, String remoteNetworkId) {
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
        }

        public void register(PeerConnection pc) {
            pc.onDataChannel.register((peer, dataChannel) -> onDataChannel(dataChannel));
            pc.onLocalCandidate.register((peer, candidate, mediaId) -> onLocalCandidate(candidate));
            pc.onStateChange.register((peer, state) -> onConnectionChange(state));
            pc.onGatheringStateChange.register((peer, state) -> onGatheringStateChange(state));
        }

        public void setHandshakeTimeout(ScheduledFuture<?> handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
        }

        public void setChildChannel(NetherNetChildChannel child) {
            this.child = child;
            checkDataChannels();
        }

        public void setPeerConnection(PeerConnection pc) {
            this.peerConnection = pc;
        }

        private void onLocalCandidate(String candidate) {
            if (log.isTraceEnabled()) {
                log.trace("Generated ICE Candidate for {}: {} (Type: {})",
                        Long.toUnsignedString(this.connectionId), candidate, extractCandidateType(candidate));
            }

            // Skip sending candidate if the signaling doesn't support trickle ICE
            if (!signaling.usesTrickleIce()) {
                return;
            }

            signaling.sendSignal(
                    remoteNetworkId,
                    NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate)
            );
        }

        private String extractCandidateType(String sdp) {
            if (sdp.contains(" typ host")) {
                return "host";
            }
            if (sdp.contains(" typ srflx")) {
                return "srflx";
            }
            if (sdp.contains(" typ relay")) {
                return "relay";
            }
            return "unknown";
        }

        private void onConnectionChange(PeerState state) {
            log.debug("Connection {} state changed: {}", Long.toUnsignedString(this.connectionId), state);
            if (state == PeerState.RTC_CONNECTED) {
                // Resolve the real client address from the selected ICE candidate pair and store it on the child channel.
                InetSocketAddress raw = this.peerConnection.remoteAddress();
                this.child.setRemoteAddress(new InetSocketAddress(raw.getHostString(), raw.getPort()));
            }
            if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) {
                if (child != null && child.isOpen()) {
                    log.debug("Closing connection {} due to state change: {}", Long.toUnsignedString(this.connectionId),
                            state);
                    child.close();
                }
                if (handshakeTimeout != null) {
                    handshakeTimeout.cancel(false);
                }
            }
        }

        private void onDataChannel(DataChannel dataChannel) {
            String label = dataChannel.label();
            log.debug("Received Data Channel: {}", label);

            if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label)) {
                this.reliable = dataChannel;
            } else if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label)) {
                this.unreliable = dataChannel;
            }

            checkDataChannels();
        }

        private void checkDataChannels() {
            if (child != null && reliable != null && unreliable != null) {
                if (handshakeTimeout != null) {
                    handshakeTimeout.cancel(false);
                }

                log.debug("Data Channels established for {}", Long.toUnsignedString(this.connectionId));
                child.setDataChannels(reliable, unreliable);

                if (child.pipeline() != null) {
                    child.pipeline().fireChannelActive();
                }
            }
        }

        private void onGatheringStateChange(GatheringState state) {
            if (state != GatheringState.RTC_GATHERING_COMPLETE || fullSdpSent || signaling.usesTrickleIce()) {
                return;
            }

            String local;
            try {
                local = peerConnection.localDescription();
            } catch (Exception e) {
                log.warn("Gathering complete for {} but the local description is unavailable: {}",
                        Long.toUnsignedString(connectionId), e.toString());
                return;
            }

            fullSdpSent = true;

            log.trace("Sending full SDP (with gathered candidates) for {}", Long.toUnsignedString(connectionId));
            try {
                signaling.sendFullSdp(remoteNetworkId, serverIdentity.augmentAnswer(local));
            } catch (Exception e) {
                log.error("Failed to sign the full SDP for {}", Long.toUnsignedString(connectionId), e);
            }
        }
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
        signaling.close();
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Server channel doesn't read data directly
    }

    @Override
    protected SocketAddress localAddress0() {
        return this.localAddress;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public boolean isActive() {
        return isOpen() && localAddress0() != null;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
