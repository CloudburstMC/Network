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

import java.io.IOException;
import org.cloudburstmc.netty.channel.nethernet.config.NetherConnectionFailure;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherServerThrottle;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import org.jspecify.annotations.Nullable;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.SignalHandler;
import org.cloudburstmc.netty.util.nethernet.IceProxyProtocol;
import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final DefaultNetherServerChannelConfig config;
    private final NetherNetServerSignaling signaling;

    private InetSocketAddress localAddress;
    /** Null unless PROXY protocol relays are read on the ICE port. */
    private volatile @Nullable IceProxyProtocol proxyProtocol;
    /** Whether a signaling's TURN servers were left out behind the relays, which is said once. */
    private volatile boolean turnLeftOut;
    private volatile boolean open = true;
    /**
     * Open connections, each holding a native peer, closed with this channel.
     */
    private final Set<NetherNetChildChannel> children = ConcurrentHashMap.newKeySet();

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

        // Loaded here rather than under the first join, so a missing native fails the bind with
        // its own cause instead of leaving every join to time out
        try {
            NetherNetNative.initialize();
        } catch (LinkageError e) {
            throw new IOException("The libdatachannel native library is not available", e);
        }

        this.signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp, clientAddress, player) -> {
            acceptConnection(connectionId, offerSdp, remoteNetworkId, clientAddress, player);
        });
        this.signaling.setMetrics(serverMetrics());

        // Before signaling serves anything, so no answer names this host past the relays
        this.proxyProtocol = bindProxyProtocol();
        // Only a fixed port is one every connection shares, so only that one is worth telling
        InetSocketAddress ice = iceAddress();
        try {
            this.signaling.setMediaRelayed(this.proxyProtocol != null);
            this.signaling.bind(localAddress, ice != null && ice.getPort() > 0 ? ice : null, eventLoop());
        } catch (Exception e) {
            closeProxyProtocol();
            throw e;
        }
    }

    private @Nullable IceProxyProtocol bindProxyProtocol() throws IOException {
        IpRangeSet relays = this.config.getOption(NetherChannelOption.NETHER_SERVER_PROXY_PROTOCOL);
        if (relays.isEmpty()) {
            return null;
        }
        InetSocketAddress ice = iceAddress();
        if (ice == null || ice.getPort() <= 0) {
            throw new IOException("PROXY protocol relays need a fixed ICE port");
        }
        // libjuice sends nothing but STUN lookups past the relays, so each join would wait out its TURN allocations
        if (this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG).iceServers().stream()
                .anyMatch(NetherNetChannel::isTurn)) {
            throw new IOException("TURN servers in NETHER_PEER_CONNECTION_CONFIG cannot work behind the "
                    + "NETHER_SERVER_PROXY_PROTOCOL relays, which pass nothing but STUN lookups");
        }
        return IceProxyProtocol.set(iceBindAddress(ice), ice.getPort(), relays);
    }

    /**
     * Where ICE binds: {@link NetherChannelOption#NETHER_SERVER_ICE_ADDRESS} when set, else the bound
     * address unless the signaling holds that UDP port itself.
     *
     * @return The address, or null when each connection takes an ephemeral port
     */
    private @Nullable InetSocketAddress iceAddress() {
        InetSocketAddress ice = this.config.getOption(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS);
        if (ice != null) {
            return ice;
        }
        return localAddress == null || !signaling.allowsIceOnLocalPort() ? null : localAddress;
    }

    /** The address ICE binds to, null for a wildcard, which is left unset so ICE gathers on every interface. */
    private static @Nullable InetAddress iceBindAddress(InetSocketAddress ice) {
        InetAddress host = ice.getAddress();
        return host == null || host.isAnyLocalAddress() ? null : host;
    }

    /**
     * Pins ICE to {@link #iceAddress}, so the transport uses one predictable port rather than an
     * ephemeral one per connection.
     *
     * @param config The configuration to derive from.
     * @return The configuration with the ICE address applied.
     */
    private PeerConnectionConfiguration bindIce(PeerConnectionConfiguration config) {
        InetSocketAddress ice = iceAddress();
        if (ice == null) {
            return config;
        }

        InetAddress host = iceBindAddress(ice);
        if (host != null) {
            config = config.withBindAddress(host);
        }

        int port = ice.getPort();
        if (port <= 0) {
            return config;
        }

        // Direct, so a pending listener sharing the port, such as NXS in hybrid mode, never holds these peers' checks
        return config
                .withEnableIceUdpMux(true)
                .withIceUdpMuxDirect(true)
                .withPortRangeBegin(port)
                .withPortRangeEnd(port);
    }

    /**
     * Called by the config when the server metrics option changes, which is usually long after
     * the bind. Not for hosts, which set the option.
     */
    public void serverMetricsChanged(NetherServerMetrics metrics) {
        if (this.signaling != null) {
            this.signaling.setMetrics(metrics);
        }
    }

    private NetherServerMetrics serverMetrics() {
        return this.config.getOption(NetherChannelOption.NETHER_SERVER_METRICS);
    }

    void acceptConnection(String connectionId, String offerSdp, String remoteNetworkId) {
        acceptConnection(connectionId, offerSdp, remoteNetworkId, null, null);
    }

    /**
     * Where a join arrives from the signaling. A host hands offers in through the signaling's own
     * entry, such as {@code NetherNetHTTPServerSignaling.acceptOffer}, which answers them.
     *
     * @param signaled Where the peer signaled from, or null if it is not known, see
     *                 {@link NetherNetChannel#signaledAddress()}
     */
    void acceptConnection(String connectionId, String offerSdp, String remoteNetworkId,
                          @Nullable SourcedAddress signaled, @Nullable PlayerInfo player) {
        if (!this.open) {
            refuse(connectionId);
            return;
        }
        InetSocketAddress clientAddress = signaled == null ? null : signaled.address();
        NetherServerThrottle throttle = this.config.getOption(NetherChannelOption.NETHER_SERVER_THROTTLE);
        InetSocketAddress throttled = throttle == null ? null : knownAddress(clientAddress);
        if (throttled != null && !throttle.accept(throttled)) {
            log.debug("Refused join {} from {}: throttled", connectionId, throttled);
            NetherServerMetrics serverMetrics = serverMetrics();
            if (serverMetrics != null) {
                serverMetrics.joinThrottled(throttled);
            }
            refuse(connectionId);
            return;
        }

        PeerConnectionConfiguration configured =
                this.config.getOption(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG);
        List<URI> iceServers = NetherNetChannel.withIceServers(configured, this.signaling.getIceServers());
        // A peer's TURN allocations never pass the relays and would hold its gathering up
        if (this.proxyProtocol != null && iceServers.removeIf(NetherNetChannel::isTurn) && !this.turnLeftOut) {
            this.turnLeftOut = true;
            log.info("Leaving out TURN servers, as nothing but STUN lookups passes the PROXY protocol relays "
                    + "on the ICE port");
        }
        PeerConnectionConfiguration rtcConfig = bindIce(configured)
                .withDisableAutoNegotiation(true)
                .withIceServers(iceServers);
        IdentityKeyVerifier identityVerifier = player == null ? null
                : TransportIdentityBinding.forPlayer(player);
        PeerConnection pc = PeerConnection.createPeer(rtcConfig);
        NetherNetChildChannel child = new NetherNetChildChannel(this, pc, signaled, localAddress);
        child.iceRelays = this.proxyProtocol;
        child.preferProxiedAddress = this.config.getOption(NetherChannelOption.NETHER_SERVER_PREFER_PROXIED_ADDRESS);
        child.attr(NetherNetChildChannel.CONNECTION_ID).set(connectionId);
        if (player != null) {
            child.attr(NetherNetChildChannel.PLAYER_INFO).set(player);
            TransportIdentityBinding.install(child, identityVerifier);
        }
        this.children.add(child);
        child.closeFuture().addListener(future -> {
            this.children.remove(child);
            if (throttled != null) {
                throttle.closed(throttled);
            }
            signaling.removeSignalHandler(connectionId);
        });

        // Listen now rather than once the child is registered: the client trickles its candidates
        // straight after the offer, and any that arrive before a handler exists are dropped
        PendingSignals remoteSignals = new PendingSignals();
        RuntimeException setupFailure = listenEarly(connectionId, remoteSignals);

        // Negotiate only after registration: initializer failure, timeout and disconnect all
        // close through the child channel, which owns the peer and its data channels.
        child.pipeline().addLast(new ChannelInitializer<NetherNetChildChannel>() {
            @Override
            protected void initChannel(NetherNetChildChannel channel) throws Exception {
                if (setupFailure != null) {
                    throw setupFailure;
                }
                initializeConnection(channel, pc, connectionId, remoteNetworkId, offerSdp, clientAddress,
                        remoteSignals);
            }
        });
        NetherServerMetrics serverMetrics = serverMetrics();
        if (serverMetrics != null) {
            serverMetrics.connectionAccepted(remoteNetworkId, player != null);
        }
        pipeline().fireChannelRead(child);
        if (!this.open) {
            // Closed while this join was set up, after doClose went through the children
            child.close();
        }
    }

    /** The address to throttle on, or null when the signaling does not know it. */
    private static @Nullable InetSocketAddress knownAddress(@Nullable InetSocketAddress address) {
        if (address == null || address.getAddress() == null || address.getAddress().isAnyLocalAddress()) {
            return null;
        }
        return address;
    }

    /** Tells the signaling a join failed without creating anything for it. */
    private void refuse(String connectionId) {
        try {
            signaling.removeSignalHandler(connectionId);
        } catch (RuntimeException e) {
            log.debug("Failed to clean up the signaling of refused join {}", connectionId, e);
        }
    }

    /** Registers the join's handler, handing back any failure for the child to close on. */
    private @Nullable RuntimeException listenEarly(String connectionId, PendingSignals remoteSignals) {
        try {
            signaling.setSignalHandler(connectionId, remoteSignals);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void initializeConnection(NetherNetChildChannel child, PeerConnection pc, String connectionId,
                                      String remoteNetworkId, String offerSdp,
                                      @Nullable InetSocketAddress clientAddress,
                                      PendingSignals remoteSignals) throws Exception {
        int handshakeTimeoutSeconds =
                this.config.getOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS);
        RemoteCandidates candidates = this.proxyProtocol != null
                ? RemoteCandidates.relayed(pc, connectionId) : RemoteCandidates.server(pc, connectionId);
        child.remoteCandidates = candidates;
        ServerPeerConnectionObserver observer = new ServerPeerConnectionObserver(connectionId, remoteNetworkId,
                offerSdp, clientAddress, child, pc, candidates, handshakeTimeoutSeconds);
        observer.register(pc);
        SignalHandler handler = signal -> {
            NetherNetConstants.Signal parsed = NetherNetConstants.parseSignal(signal);
            if (parsed == null) {
                return;
            }
            String data = parsed.payload();

            switch (parsed.type()) {
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> candidates.trickle(data);
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.debug("Received CONNECT_ERROR for {}", connectionId);
                    if (!child.isActive()) {
                        child.connectionFailed(NetherConnectionFailure.CONNECT_ERROR);
                    }
                    child.close();
                }
            }
        };

        candidates.setDescription(offerSdp, SessionDescriptionType.OFFER);
        log.trace("Remote description set for {}", connectionId);
        // Candidates only apply once the offer is in, so this is the earliest they can go
        remoteSignals.deliverTo(handler);
        pc.setLocalDescription("answer");

        // Anything without trickle answers once from onGatheringStateChange instead.
        if (signaling.usesTrickleIce()) {
            log.trace("Sending Answer SDP for {}", connectionId);
            signaling.sendSignal(remoteNetworkId, NetherNetConstants.buildSignalConnectResponse(connectionId,
                    serverIdentity.withAssertion(pc.localDescription())));
            // Gathering starts inside setLocalDescription, so some candidates are already waiting
            observer.localCandidates.deliverTo(signal -> signaling.sendSignal(remoteNetworkId, signal));
        }
    }

    /**
     * Holds signals until whoever handles them is ready, then hands them over in order. Signals
     * and the handler come from different threads, so both go through the lock.
     */
    static final class PendingSignals implements SignalHandler {
        /** Far more than one join sends before it is ready, so only a flood reaches it. */
        static final int MAX_PENDING = 64;

        private final List<String> pending = new ArrayList<>();
        private @Nullable SignalHandler target;

        @Override
        public synchronized void onSignal(String signal) {
            if (this.target != null) {
                this.target.onSignal(signal);
            } else if (this.pending.size() < MAX_PENDING) {
                this.pending.add(signal);
            } else {
                log.debug("Dropping a signal, {} are already pending", MAX_PENDING);
            }
        }

        /** Replays what is pending, then passes everything after straight through. */
        synchronized void deliverTo(SignalHandler handler) {
            this.target = handler;
            this.pending.forEach(handler::onSignal);
            this.pending.clear();
        }
    }

    /**
     * The warning for a join that never carried traffic, which is what a host sees when a player
     * cannot connect. The player's address goes in a debug line instead, as hosts may keep
     * addresses out of their logs.
     *
     * @param player        The player's name, null when the signaling does not know it
     * @param progress      How far the join got
     * @param icePort       The UDP port ICE uses, 0 when each connection takes its own
     * @param relayed       Whether players only reach that port through PROXY protocol relays
     * @return The warning
     */
    static String joinFailedMessage(@Nullable String player, JoinProgress progress, int icePort, boolean relayed) {
        // Self asserted under TokenTrust.ANY, so it must not be able to forge log lines
        String name = player == null ? "" : player.replaceAll("\\p{Cntrl}", "").strip();
        String who = name.isEmpty() ? "A player" : name.length() > 32 ? name.substring(0, 32) : name;
        if (progress == JoinProgress.PEER_CONNECTED) {
            return who + " reached this server over NetherNet, but the connection did not finish setting up.";
        }
        if (progress == JoinProgress.ICE_CONNECTED) {
            // This side's checks came back, yet a client goes on only once its own checks do
            return who + " could not connect over NetherNet: their device answered this server, but the connection "
                    + "stalled after that. A firewall or a hosting provider's DDoS filter may be dropping other "
                    + "packets their device sends " + (relayed ? "through the PROXY protocol relays."
                    : icePort > 0 ? "to UDP port " + icePort + "." : "to this server over UDP.");
        }
        if (relayed) {
            return who + " could not connect over NetherNet: nothing arrived through the PROXY protocol relays this "
                    + "server advertises. Each relay has to forward to UDP port " + icePort + " and be listed.";
        }
        return who + " could not connect over NetherNet: no network path to this server was found. Their device "
                + "needs to reach " + (icePort > 0 ? "UDP port " + icePort + " on one of the addresses this server"
                : "this server over UDP on one of the addresses it") + " advertises.";
    }

    /** How far a join got before it failed. */
    enum JoinProgress {
        /** ICE found no path. */
        NONE,
        /** This side's ICE checks came back, but the peer connection never came up. */
        ICE_CONNECTED,
        /** The peer connection came up, but the channel never went active. */
        PEER_CONNECTED
    }

    /**
     * Observer to handle Data Channel creation from the client.
     */
    private class ServerPeerConnectionObserver {
        private final String connectionId;
        private final String remoteNetworkId;
        private final NetherNetChildChannel child;

        private final int handshakeTimeoutSeconds;
        private final ScheduledFuture<?> handshakeTimeout;
        /** Whether this side's ICE checks came back, which tells a missing path from a later failure. */
        private volatile boolean iceConnected;
        /** Whether the peer connection came up, which ICE alone does not mean. */
        private volatile boolean peerConnected;
        private final AtomicBoolean failureLogged = new AtomicBoolean();

        private final PeerConnection peerConnection;
        private final RemoteCandidates remoteCandidates;
        private volatile boolean fullSdpSent = false;
        private final PendingSignals localCandidates = new PendingSignals();

        private final String offerSdp;
        private final InetSocketAddress clientAddress;

        public ServerPeerConnectionObserver(String connectionId, String remoteNetworkId, String offerSdp,
                                            @Nullable InetSocketAddress clientAddress,
                                            NetherNetChildChannel child, PeerConnection peerConnection,
                                            RemoteCandidates remoteCandidates, int handshakeTimeoutSeconds) {
            this.child = child;
            this.peerConnection = peerConnection;
            this.remoteCandidates = remoteCandidates;
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
            this.offerSdp = offerSdp;
            this.clientAddress = clientAddress;
            this.handshakeTimeoutSeconds = handshakeTimeoutSeconds;
            // Runs on the child's loop, so never before this constructor and the setup around it finish
            this.handshakeTimeout = child.eventLoop().schedule(this::handshakeTimedOut, handshakeTimeoutSeconds,
                    TimeUnit.SECONDS);
            child.closeFuture().addListener(future -> this.handshakeTimeout.cancel(false));
        }

        private void handshakeTimedOut() {
            if (!child.isActive() && child.isOpen()) {
                child.connectionFailed(NetherConnectionFailure.HANDSHAKE_TIMEOUT);
                child.close();
                joinFailed("timed out after " + handshakeTimeoutSeconds + "s");
            }
        }

        /** Warns that a player could not connect, once per join. */
        private void joinFailed(String reason) {
            if (!failureLogged.compareAndSet(false, true)) {
                return;
            }
            PlayerInfo player = child.attr(NetherNetChildChannel.PLAYER_INFO).get();
            InetSocketAddress ice = iceAddress();
            JoinProgress progress = peerConnected ? JoinProgress.PEER_CONNECTED
                    : iceConnected ? JoinProgress.ICE_CONNECTED : JoinProgress.NONE;
            log.warn(joinFailedMessage(player == null ? null : player.displayName(), progress,
                    ice == null ? 0 : ice.getPort(), proxyProtocol != null));
            log.debug("Join {} from {} failed: {}{}", connectionId, clientAddress, reason,
                    peerConnected ? ", after the peer connected" : iceConnected ? ", after ICE connected" : "");
        }

        /**
         * Checks the address the peer signaled from, once it is clear neither side gathered
         * anything the other can reach.
         *
         * @param local The description this side gathered
         */
        private void inferPeerCandidates(String local) {
            if (!config.getOption(NetherChannelOption.NETHER_INFER_PEER_CANDIDATES)
                    || proxyProtocol != null || SdpUtil.hasRoutableHostCandidate(local)) {
                return;
            }
            for (String candidate : SdpUtil.inferredPeerCandidates(this.offerSdp, this.clientAddress)) {
                log.debug("Inferred candidate for {}: {}", connectionId, candidate);
                remoteCandidates.add(candidate);
            }
        }

        public void register(PeerConnection pc) {
            pc.onDataChannel.register((peer, dataChannel) -> onDataChannel(dataChannel));
            pc.onLocalCandidate.register((peer, candidate, mediaId) -> onLocalCandidate(candidate));
            pc.onStateChange.register((peer, state) -> onConnectionChange(state));
            pc.onIceStateChange.register((peer, state) -> {
                if (state == IceState.RTC_ICE_CONNECTED || state == IceState.RTC_ICE_COMPLETED) {
                    iceConnected = true;
                }
            });
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

            // Held until the answer is out, as a client cannot apply a candidate before it
            localCandidates.onSignal(NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate));
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
                peerConnected = true;
            }
            if (state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) {
                if (child.isOpen()) {
                    if (state == PeerState.RTC_FAILED && !child.isActive()) {
                        joinFailed("the peer connection failed");
                    }
                    log.debug("Closing connection {} due to state change: {}", this.connectionId,
                            state);
                    child.closeFromTransport();
                }
                handshakeTimeout.cancel(false);
            }
        }

        private void onDataChannel(DataChannel dataChannel) {
            log.debug("Received Data Channel: {}", dataChannel.label());
            if (child.acceptDataChannel(dataChannel)) {
                handshakeTimeout.cancel(false);
                log.debug("Data Channels established for {}", this.connectionId);
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
        for (NetherNetChildChannel child : this.children) {
            child.close();
        }
        closeProxyProtocol();
    }

    private void closeProxyProtocol() {
        IceProxyProtocol relays = this.proxyProtocol;
        if (relays != null) {
            this.proxyProtocol = null;
            try {
                relays.close();
            } catch (RuntimeException e) {
                log.warn("Could not clear PROXY protocol relays", e);
            }
        }
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
