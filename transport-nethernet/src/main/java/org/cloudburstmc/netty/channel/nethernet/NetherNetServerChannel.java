package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;
import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.PeerConnectionFactory;
import dev.kastle.webrtc.PeerConnectionObserver;
import dev.kastle.webrtc.RTCAnswerOptions;
import dev.kastle.webrtc.RTCBundlePolicy;
import dev.kastle.webrtc.RTCConfiguration;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCIceCandidate;
import dev.kastle.webrtc.RTCIceServer;
import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final DefaultNetherServerChannelConfig config;
    private final List<PeerConnectionFactory> factories;
    private final NetherNetServerSignaling signaling;

    // Round robin cursor into factories. Only touched from this channel's
    // event loop (establishConnection), so no synchronization is needed.
    private int nextFactory;

    private InetSocketAddress localAddress;
    private volatile boolean open = true;

    /**
     * Creates a NetherNetServerChannel with a new PeerConnectionFactory.
     *
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(NetherNetServerSignaling signaling) {
        this(new PeerConnectionFactory(), signaling);
    }

    /**
     * Creates a NetherNetServerChannel.
     *
     * @param factory   The PeerConnectionFactory to use for creating peer connections. Should be reused where possible.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(PeerConnectionFactory factory, NetherNetServerSignaling signaling) {
        this(List.of(factory), signaling);
    }

    /**
     * Creates a NetherNetServerChannel backed by a pool of PeerConnectionFactory
     * instances. Each native factory runs one network, worker, and signaling
     * thread shared by all its peer connections; connections are assigned to
     * factories round robin, spreading the DTLS and SCTP load of many players
     * across the pool instead of serializing it on a single network thread.
     *
     * @param factories The PeerConnectionFactory pool, at least one. This
     *                  channel takes ownership and disposes each on close.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(List<PeerConnectionFactory> factories, NetherNetServerSignaling signaling) {
        if (factories.isEmpty()) {
            throw new IllegalArgumentException("factories must not be empty");
        }
        this.factories = List.copyOf(factories);
        this.signaling = signaling;
        this.config = new DefaultNetherServerChannelConfig(this);
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (!(localAddress instanceof InetSocketAddress)) throw new IllegalArgumentException("Unsupported address type");
        this.localAddress = (InetSocketAddress) localAddress;

        this.signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp) -> {
            acceptConnection(connectionId, offerSdp, remoteNetworkId);
        });

        this.signaling.bind(localAddress);
    }

    /**
     * Accepts an incoming connection offer. Runs on the signaling I/O thread:
     * only the signal handler registration happens here (a cheap map put, so
     * candidates arriving right behind the offer are never dropped), then all
     * WebRTC work hops onto this server channel's event loop. Native peer
     * connection calls block on the WebRTC signaling thread and must never
     * stall the signaling socket's thread, whose keepalives are what hold the
     * connection to the signaling service open.
     */
    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
        PendingConnection pending = new PendingConnection(connectionId);
        signaling.setSignalHandler(connectionId, signal -> eventLoop().execute(() -> pending.handleSignal(signal)));
        eventLoop().execute(() -> establishConnection(pending, connectionId, offerSdp, remoteNetworkId));
    }

    /**
     * Builds the peer connection for an accepted offer. Runs on this server
     * channel's event loop; incoming signals for the connection hop onto the
     * same loop, so everything here is single-threaded and ordered.
     */
    private void establishConnection(PendingConnection pending, long connectionId, String offerSdp, String remoteNetworkId) {
        try {
            RTCConfiguration rtcConfig = new RTCConfiguration();
            rtcConfig.portAllocatorConfig = this.config.getOption(NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG);
            rtcConfig.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE;

            // Inject ICE servers if the signaling implementation supports it
            List<IceServerInfo> iceServers = this.signaling.getIceServers();
            if (iceServers != null && !iceServers.isEmpty()) {
                log.trace("Injecting {} ICE Servers into PeerConnection for {}", iceServers.size(), Long.toUnsignedString(connectionId));
                for (IceServerInfo info : iceServers) {
                    RTCIceServer iceServer = new RTCIceServer();
                    iceServer.urls = info.urls();
                    iceServer.username = info.username();
                    iceServer.password = info.password();
                    rtcConfig.iceServers.add(iceServer);
                }
            }

            ServerPeerConnectionObserver observer = new ServerPeerConnectionObserver(connectionId, remoteNetworkId);
            PeerConnectionFactory factory = factories.get(nextFactory);
            nextFactory = (nextFactory + 1) % factories.size();
            RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, observer);

            NetherNetChildChannel child = new NetherNetChildChannel(this, pc, generatePlaceholderAddress(), localAddress);
            // Fragment outbound data no larger than the client advertised it can
            // receive (a=max-message-size in its offer), falling back to the
            // conservative default when the client does not advertise one.
            child.setMaxOutboundMessageSize(NetherNetConstants.parseMaxMessageSize(offerSdp, NetherNetConstants.MAX_SCTP_MESSAGE_SIZE));
            observer.setChildChannel(child);

            child.closeFuture().addListener(future -> signaling.removeSignalHandler(connectionId));

            int handshakeTimeoutSeconds = this.config.getOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS);
            ScheduledFuture<?> timeoutTask = eventLoop().schedule(() -> {
                if (!child.isActive()) {
                    log.warn("Connection {} timed out during handshake ({}s)", Long.toUnsignedString(connectionId), handshakeTimeoutSeconds);
                    child.close();
                    pc.close();
                }
            }, handshakeTimeoutSeconds, TimeUnit.SECONDS);
            observer.setHandshakeTimeout(timeoutTask);

            // Handle Offer
            pc.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, offerSdp), new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    log.trace("Remote description set for {}", Long.toUnsignedString(connectionId));
                    pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            pc.setLocalDescription(description, new SetSessionDescriptionObserver() {
                                @Override
                                public void onSuccess() {
                                    log.trace("Sending Answer SDP for {}", Long.toUnsignedString(connectionId));
                                    try {
                                        signaling.sendSignal(
                                            remoteNetworkId,
                                            NetherNetConstants.buildSignalConnectResponse(connectionId, description.sdp)
                                        );
                                    } catch (Exception e) {
                                        // Signaling dropped mid-handshake; the client cannot
                                        // receive the answer, so let the handshake timeout
                                        // reap this connection.
                                        log.warn("Failed to send answer for {} (signaling unavailable): {}",
                                            Long.toUnsignedString(connectionId), e.getMessage());
                                        return;
                                    }
                                    pipeline().fireChannelRead(child);
                                }
                                @Override public void onFailure(String error) { log.error("SetLocalDesc failed: {}", error); }
                            });
                        }
                        @Override public void onFailure(String error) { log.error("CreateAnswer failed: {}", error); }
                    });
                }
                @Override public void onFailure(String error) { log.error("SetRemoteDesc failed: {}", error); }
            });

            // Publish the peer connection to the signal handler and drain any
            // candidates that arrived while it was being created.
            pending.attach(pc, child);
        } catch (Exception e) {
            log.error("Failed to establish connection {}: {}", Long.toUnsignedString(connectionId), e.getMessage(), e);
            signaling.removeSignalHandler(connectionId);
        }
    }

    /**
     * Per-connection signal state. All methods run on this server channel's
     * event loop, so no synchronization is needed. Signals that arrive between
     * the offer and the peer connection becoming available are queued and
     * drained by attach, preserving arrival order.
     */
    private final class PendingConnection {
        private final long connectionId;
        private RTCPeerConnection pc;
        private NetherNetChildChannel child;
        private List<String> queued = new ArrayList<>();

        PendingConnection(long connectionId) {
            this.connectionId = connectionId;
        }

        void attach(RTCPeerConnection pc, NetherNetChildChannel child) {
            this.pc = pc;
            this.child = child;
            List<String> pendingSignals = this.queued;
            this.queued = null;
            for (String signal : pendingSignals) {
                apply(signal);
            }
        }

        void handleSignal(String signal) {
            if (pc == null) {
                if (queued != null) {
                    queued.add(signal);
                }
                return;
            }
            apply(signal);
        }

        private void apply(String signal) {
            String[] parts = signal.split(" ", 3);
            if (parts.length < 3) return;
            String type = parts[0];
            String data = parts[2];

            switch (type) {
                case NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD -> {
                    log.trace("Applying Remote Candidate for {}: {}", Long.toUnsignedString(connectionId), data);
                    try {
                        pc.addIceCandidate(new RTCIceCandidate("0", 0, data));
                    } catch (Exception e) {
                        log.debug("Failed to apply ICE candidate for {} (Connection likely closed): {}", Long.toUnsignedString(connectionId), e.toString());
                    }
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.debug("Received CONNECT_ERROR for {}", Long.toUnsignedString(connectionId));
                    child.close();
                }
            }
        }
    }

    /**
     * Observer to handle Data Channel creation from the client.
     */
    private class ServerPeerConnectionObserver implements PeerConnectionObserver {
        private final long connectionId;
        private final String remoteNetworkId;
        private NetherNetChildChannel child;

        private RTCDataChannel reliable;
        private RTCDataChannel unreliable;
        private boolean dataChannelsSet;

        private ScheduledFuture<?> handshakeTimeout;

        // Real remote address from ICE nomination. Buffered here when the
        // callback fires before the child channel is attached.
        private volatile InetSocketAddress pendingRemoteAddress;

        public ServerPeerConnectionObserver(long connectionId, String remoteNetworkId) {
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
        }

        public void setHandshakeTimeout(ScheduledFuture<?> handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
        }

        public void setChildChannel(NetherNetChildChannel child) {
            this.child = child;
            InetSocketAddress pending = this.pendingRemoteAddress;
            if (pending != null) {
                child.remoteAddress = pending;
            }
            checkDataChannels();
        }

        @Override
        public void onSelectedCandidatePairChanged(String remoteAddress, int remotePort, String candidateType) {
            // ICE nominated a pair. This fires before DTLS and the data
            // channels open, so the channel carries its real remote address
            // before it activates and anything downstream reads it. Until
            // then the channel holds its unique random placeholder, which
            // also covers the case of this callback never firing. For a
            // relayed connection the address is the TURN relay, which is the
            // peer actually connected to us and the intended value. A later
            // re-nomination simply overwrites.
            try {
                InetSocketAddress resolved = new InetSocketAddress(remoteAddress, remotePort);
                NetherNetChildChannel target = this.child;
                if (target != null) {
                    target.remoteAddress = resolved;
                } else {
                    this.pendingRemoteAddress = resolved;
                }
                log.debug("Resolved remote address for {}: {}:{} (type: {})",
                    Long.toUnsignedString(this.connectionId), remoteAddress, remotePort, candidateType);
            } catch (Exception e) {
                log.debug("Failed to set remote address for {}: {}",
                    Long.toUnsignedString(this.connectionId), e.getMessage());
            }
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            if (log.isTraceEnabled()) {
                log.trace("Generated ICE Candidate for {}: {} (Type: {})",
                    Long.toUnsignedString(this.connectionId), candidate.sdp, extractCandidateType(candidate.sdp));
            }
            try {
                signaling.sendSignal(
                    remoteNetworkId,
                    NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate.sdp)
                );
            } catch (Exception e) {
                // Signaling dropped mid-handshake. Established connections don't
                // signal candidates, so only this in-flight handshake is affected;
                // the handshake timeout cleans it up if it cannot complete.
                log.debug("Failed to signal ICE candidate for {} (signaling unavailable): {}",
                    Long.toUnsignedString(this.connectionId), e.getMessage());
            }
        }

        private String extractCandidateType(String sdp) {
            if (sdp.contains(" typ host ")) return "host";
            if (sdp.contains(" typ srflx ")) return "srflx";
            if (sdp.contains(" typ relay ")) return "relay";
            return "unknown";
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            log.debug("Connection {} state changed: {}", Long.toUnsignedString(this.connectionId), state);
            if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED) {
                if (child != null && child.isOpen()) {
                    log.debug("Closing connection {} due to state change: {}", Long.toUnsignedString(this.connectionId), state);
                    child.close();
                }
                if (handshakeTimeout != null) {
                    handshakeTimeout.cancel(false);
                }
            }
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            String label = dataChannel.getLabel();
            log.debug("Received Data Channel: {}", label);

            if (NetherNetConstants.RELIABLE_CHANNEL_LABEL.equals(label)) {
                this.reliable = dataChannel;
            } else if (NetherNetConstants.UNRELIABLE_CHANNEL_LABEL.equals(label)) {
                this.unreliable = dataChannel;
            }

            checkDataChannels();
        }

        private void checkDataChannels() {
            if (child != null && reliable != null && unreliable != null && !dataChannelsSet) {
                dataChannelsSet = true;
                if (handshakeTimeout != null) {
                    handshakeTimeout.cancel(false);
                }

                log.debug("Data Channels established for {}", Long.toUnsignedString(this.connectionId));
                child.setDataChannels(reliable, unreliable);
                child.fireChannelActiveIfReady();
            }
        }

        //
        // private void activateOnce(NetherNetChildChannel child, java.util.concurrent.atomic.AtomicBoolean activated) {
        //     if (!activated.compareAndSet(false, true)) return;
        //     child.eventLoop().execute(() -> {
        //         if (child.isOpen() && child.pipeline() != null) {
        //             child.pipeline().fireChannelActive();
        //         }
        //     });
        // }
    }

    /**
     * Generates a unique placeholder address in the 10.x.x.x range for a new
     * Nethernet connection. The 10.0.0.0/8 range is private (RFC 1918) and
     * will not collide with real public client addresses.
     */
    private static InetSocketAddress generatePlaceholderAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String ip = "10." + (r.nextInt(1, 256)) + "." + (r.nextInt(256)) + "." + (r.nextInt(1, 256));
        return new InetSocketAddress(ip, 0);
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;

        try {
            signaling.close();
        } finally {
            Exception failure = null;
            for (PeerConnectionFactory factory : factories) {
                try {
                    factory.dispose();
                } catch (Exception e) {
                    // Keep disposing the rest; rethrow the first failure after.
                    if (failure == null) failure = e;
                    log.warn("Failed to dispose PeerConnectionFactory: {}", e.getMessage());
                }
            }
            if (failure != null) {
                throw failure;
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
    public ChannelConfig config() { return config; }

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
