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

import org.cloudburstmc.netty.channel.nethernet.config.NetherConnectionFailure;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.jspecify.annotations.Nullable;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.*;

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

    private OperatorIdentity serverIdentity;

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
                this.serverIdentity = OperatorIdentity.generate("self");
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
        this.signaling.setMetrics(serverMetrics());

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

        return config
                .withEnableIceUdpMux(true)
                .withPortRangeBegin(port)
                .withPortRangeEnd(port);
    }

    /** Called when the server metrics option changes, which is usually long after the bind. */
    public void serverMetricsChanged(NetherServerMetrics metrics) {
        if (this.signaling != null) {
            this.signaling.setMetrics(metrics);
        }
    }

    private NetherServerMetrics serverMetrics() {
        return this.config.getOption(NetherChannelOption.NETHER_SERVER_METRICS);
    }

    public void acceptConnection(String connectionId, String offerSdp, String remoteNetworkId) {
        acceptConnection(connectionId, offerSdp, remoteNetworkId, null, null);
    }

    public void acceptConnection(String connectionId, String offerSdp, String remoteNetworkId,
                                 @Nullable InetSocketAddress clientAddress) {
        acceptConnection(connectionId, offerSdp, remoteNetworkId, clientAddress, null);
    }

    /**
     * @param clientAddress The address the peer signaled from, or null if it is not known. ICE
     *                      replaces it with the negotiated pair once the connection is up, but
     *                      until then it is all the child channel has to report.
     */
    public void acceptConnection(String connectionId, String offerSdp, String remoteNetworkId,
                                 @Nullable InetSocketAddress clientAddress, @Nullable PlayerInfo player) {
        PeerConnectionConfiguration rtcConfig =
                bindIce(this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG))
                        .withDisableAutoNegotiation(true)
                        .withIceServers(
                                this.signaling.getIceServers().stream().map(IceServerInfo::toUris).flatMap(List::stream)
                                        .toList());
        IdentityKeyVerifier identityVerifier = player == null ? null
                : TransportIdentityBinding.forPlayer(player);
        PeerConnection pc = PeerConnection.createPeer(rtcConfig);
        NetherNetChildChannel child = new NetherNetChildChannel(this,
                pc, clientAddress == null ? new InetSocketAddress(0) : clientAddress, localAddress);
        child.attr(NetherNetChildChannel.CONNECTION_ID).set(connectionId);
        if (player != null) {
            child.attr(NetherNetChildChannel.PLAYER_INFO).set(player);
            TransportIdentityBinding.install(child, identityVerifier);
        }

        // Negotiate only after registration: initializer failure, timeout and disconnect all
        // close through the child channel, which owns the peer and its data channels.
        child.pipeline().addLast(new ChannelInitializer<NetherNetChildChannel>() {
            @Override
            protected void initChannel(NetherNetChildChannel channel) throws Exception {
                initializeConnection(channel, pc, connectionId, remoteNetworkId, offerSdp, clientAddress);
            }
        });
        NetherServerMetrics serverMetrics = serverMetrics();
        if (serverMetrics != null) {
            serverMetrics.connectionAccepted(remoteNetworkId, player != null);
        }
        pipeline().fireChannelRead(child);
    }

    private void initializeConnection(NetherNetChildChannel child, PeerConnection pc, String connectionId,
                                      String remoteNetworkId, String offerSdp,
                                      @Nullable InetSocketAddress clientAddress) throws Exception {
        int handshakeTimeoutSeconds =
                this.config.getOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS);
        ScheduledFuture<?> timeout = child.eventLoop().schedule(() -> {
            if (!child.isActive()) {
                child.connectionFailed(NetherConnectionFailure.HANDSHAKE_TIMEOUT);
                child.close();
                log.warn("Connection {} timed out during handshake ({}s)", connectionId,
                        handshakeTimeoutSeconds);
            }
        }, handshakeTimeoutSeconds, TimeUnit.SECONDS);
        child.closeFuture().addListener(future -> timeout.cancel(false));
        child.closeFuture().addListener(future -> signaling.removeSignalHandler(connectionId));

        ServerPeerConnectionObserver observer = new ServerPeerConnectionObserver(connectionId, remoteNetworkId,
                offerSdp, clientAddress, child, pc, timeout);
        observer.register(pc);
        signaling.setSignalHandler(connectionId, signal -> {
            NetherNetConstants.Signal parsed = NetherNetConstants.parseSignal(signal);
            if (parsed == null) {
                return;
            }
            String data = parsed.payload();

            switch (parsed.type()) {
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    log.trace("Applying Remote Candidate for {}: {}", connectionId, data);
                    try {
                        pc.addRemoteCandidate(data);
                    } catch (Exception e) {
                        log.debug("Failed to apply ICE candidate for {} (Connection likely closed): {}",
                                connectionId, e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.debug("Received CONNECT_ERROR for {}", connectionId);
                    if (!child.isActive()) {
                        child.connectionFailed(NetherConnectionFailure.CONNECT_ERROR);
                    }
                    child.close();
                }
            }
        });

        pc.setRemoteDescription(offerSdp, SessionDescriptionType.OFFER);
        log.trace("Remote description set for {}", connectionId);
        pc.setLocalDescription("answer");

        // Anything without trickle answers once from onGatheringStateChange instead.
        if (signaling.usesTrickleIce()) {
            log.trace("Sending Answer SDP for {}", connectionId);
            signaling.sendSignal(remoteNetworkId, NetherNetConstants.buildSignalConnectResponse(connectionId,
                    serverIdentity.withAssertion(pc.localDescription())));
        }
    }

    /**
     * Observer to handle Data Channel creation from the client.
     */
    private class ServerPeerConnectionObserver {
        private final String connectionId;
        private final String remoteNetworkId;
        private final NetherNetChildChannel child;

        private DataChannel reliable;
        private DataChannel unreliable;

        private final ScheduledFuture<?> handshakeTimeout;

        private final PeerConnection peerConnection;
        private volatile boolean fullSdpSent = false;

        private final String offerSdp;
        private final InetSocketAddress clientAddress;

        public ServerPeerConnectionObserver(String connectionId, String remoteNetworkId, String offerSdp,
                                            @Nullable InetSocketAddress clientAddress,
                                            NetherNetChildChannel child, PeerConnection peerConnection,
                                            ScheduledFuture<?> handshakeTimeout) {
            this.child = child;
            this.peerConnection = peerConnection;
            this.handshakeTimeout = handshakeTimeout;
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
            this.offerSdp = offerSdp;
            this.clientAddress = clientAddress;
        }

        /**
         * Checks the address the peer signaled from, once it is clear neither side gathered
         * anything the other can reach.
         *
         * @param local The description this side gathered
         */
        private void inferPeerCandidates(String local) {
            if (!config.getOption(NetherChannelOption.NETHER_INFER_PEER_CANDIDATES)
                    || SdpUtil.hasRoutableHostCandidate(local)) {
                return;
            }
            for (String candidate : SdpUtil.inferredPeerCandidates(this.offerSdp, this.clientAddress)) {
                log.debug("Inferred candidate for {}: {}", connectionId, candidate);
                try {
                    peerConnection.addRemoteCandidate(candidate);
                } catch (Exception e) {
                    log.debug("Failed to add inferred candidate for {}: {}",
                            connectionId, e.toString());
                }
            }
        }

        public void register(PeerConnection pc) {
            pc.onDataChannel.register((peer, dataChannel) -> onDataChannel(dataChannel));
            pc.onLocalCandidate.register((peer, candidate, mediaId) -> onLocalCandidate(candidate));
            pc.onStateChange.register((peer, state) -> onConnectionChange(state));
            pc.onGatheringStateChange.register((peer, state) -> onGatheringStateChange(state));
        }

        private void onLocalCandidate(String candidate) {
            if (log.isTraceEnabled()) {
                log.trace("Generated ICE Candidate for {}: {} (Type: {})",
                        this.connectionId, candidate, extractCandidateType(candidate));
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
            log.debug("Connection {} state changed: {}", this.connectionId, state);

            if (state == PeerState.RTC_CONNECTED) {
                // The selected candidate pair is the only place the peer's real address appears
                InetSocketAddress raw = this.peerConnection.remoteAddress();
                this.child.setRemoteAddress(new InetSocketAddress(raw.getHostString(), raw.getPort()));
            }
            if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) {
                if (child.isOpen()) {
                    log.debug("Closing connection {} due to state change: {}", this.connectionId,
                            state);
                    child.close();
                }
                handshakeTimeout.cancel(false);
            }
        }

        private void onDataChannel(DataChannel dataChannel) {
            String label = dataChannel.label();
            log.debug("Received Data Channel: {}", label);

            if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label) && reliable == null) {
                reliable = dataChannel;
            } else if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label) && unreliable == null) {
                unreliable = dataChannel;
            } else {
                dataChannel.close();
                return;
            }

            if (reliable != null && unreliable != null) {
                handshakeTimeout.cancel(false);
                log.debug("Data Channels established for {}", this.connectionId);
                child.activate(reliable, unreliable);
            }
        }

        private void onGatheringStateChange(GatheringState state) {
            if (state != GatheringState.RTC_GATHERING_COMPLETE) {
                return;
            }

            String local;
            try {
                local = peerConnection.localDescription();
            } catch (Exception e) {
                log.warn("Gathering complete for {} but the local description is unavailable: {}",
                        connectionId, e.toString());
                return;
            }

            inferPeerCandidates(local);

            if (fullSdpSent || signaling.usesTrickleIce()) {
                return;
            }
            fullSdpSent = true;

            log.trace("Sending full SDP (with gathered candidates) for {}", connectionId);
            try {
                signaling.sendDescription(remoteNetworkId, serverIdentity.withAssertion(local));
            } catch (Exception e) {
                log.error("Failed to sign the full SDP for {}", connectionId, e);
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
