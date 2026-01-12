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
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final DefaultNetherServerChannelConfig config;
    private final PeerConnectionFactory factory;
    private final NetherNetServerSignaling signaling;
    
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
        this.factory = factory;
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

    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
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
        RTCPeerConnection pc = factory.createPeerConnection(rtcConfig, observer);

        NetherNetChildChannel child = new NetherNetChildChannel(this, pc, new InetSocketAddress(0), localAddress);
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
        
        // Register Signal Handler
        signaling.setSignalHandler(connectionId, (signal) -> {
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
        });

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
                                signaling.sendSignal(
                                    remoteNetworkId, 
                                    NetherNetConstants.buildSignalConnectResponse(connectionId, description.sdp)
                                );
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

        private ScheduledFuture<?> handshakeTimeout;

        public ServerPeerConnectionObserver(long connectionId, String remoteNetworkId) {
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
        }

        public void setHandshakeTimeout(ScheduledFuture<?> handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
        }

        public void setChildChannel(NetherNetChildChannel child) {
            this.child = child;
            checkDataChannels();
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            if (log.isTraceEnabled()) {
                log.trace("Generated ICE Candidate for {}: {} (Type: {})", 
                    Long.toUnsignedString(this.connectionId), candidate.sdp, extractCandidateType(candidate.sdp));
            }
            signaling.sendSignal(
                remoteNetworkId, 
                NetherNetConstants.buildSignalCandidateAdd(connectionId, candidate.sdp)
            );
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
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
        
        try {
            signaling.close();
        } finally {
            factory.dispose();
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