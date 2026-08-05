package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.LibWebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcServerBackend;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSessionListener;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherServerChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import dev.kastle.webrtc.PeerConnectionFactory;
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
import java.util.function.Supplier;

/**
 * NetherNet server channel: accepts connection offers arriving over a
 * {@link NetherNetServerSignaling} implementation and negotiates them through
 * a {@link WebRtcServerBackend}, emitting accepted
 * {@link NetherNetChildChannel}s into the pipeline like any netty server
 * channel. No WebRTC engine types appear here; the backend seam owns them.
 */
public class NetherNetServerChannel extends AbstractServerChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetServerChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final DefaultNetherServerChannelConfig config;
    private final Supplier<? extends WebRtcServerBackend> backendSupplier;
    private final NetherNetServerSignaling signaling;

    /**
     * The WebRTC backend. Set at construction for the eager constructors;
     * for the supplier constructor it materializes in {@link #doBind} only
     * after the signaling endpoint bound successfully, so a failed bind
     * never creates (and then immediately tears down) native engine state.
     * Written and read on this channel's event loop; volatile for doClose.
     */
    private volatile WebRtcServerBackend backend;

    private InetSocketAddress localAddress;
    private volatile boolean open = true;

    /**
     * The built in self signed identity backing the default answer
     * decoration. Generated for every channel regardless of constructor so
     * no construction path can reach an answer without an identity to sign
     * it with.
     */
    private final ServerIdentity serverIdentity = generateDefaultIdentity();

    /**
     * Creates a NetherNetServerChannel with a single default engine factory.
     *
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(NetherNetServerSignaling signaling) {
        this(new LibWebRtcServerBackend(List.of(new PeerConnectionFactory())), signaling);
    }

    /**
     * Creates a NetherNetServerChannel backed by a single engine factory.
     *
     * @param factory   The PeerConnectionFactory to use for creating peer connections.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(PeerConnectionFactory factory, NetherNetServerSignaling signaling) {
        this(new LibWebRtcServerBackend(List.of(factory)), signaling);
    }

    /**
     * Creates a NetherNetServerChannel backed by a pool of engine factories
     * with round robin connection assignment.
     *
     * @param factories The PeerConnectionFactory pool, at least one. The
     *                  channel takes ownership and disposes each on close.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(List<PeerConnectionFactory> factories, NetherNetServerSignaling signaling) {
        this(new LibWebRtcServerBackend(factories), signaling);
    }

    /**
     * Creates a NetherNetServerChannel over an explicit backend.
     *
     * @param backend   The WebRTC backend negotiating and carrying connections.
     *                  The channel takes ownership and closes it on close.
     * @param signaling The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(WebRtcServerBackend backend, NetherNetServerSignaling signaling) {
        this.backend = backend;
        this.backendSupplier = null;
        this.signaling = signaling;
        this.config = new DefaultNetherServerChannelConfig(this);
    }

    /**
     * Creates a NetherNetServerChannel whose backend is created lazily,
     * only after the signaling endpoint bound successfully. A signaling
     * endpoint that cannot bind (a taken TCP port, a refused websocket)
     * therefore never creates native engine state, instead of creating a
     * factory pool and disposing it milliseconds later, a teardown that
     * races engine initialization.
     *
     * @param backendSupplier Invoked once from {@link #doBind} after the
     *                        signaling bind succeeded. The channel takes
     *                        ownership of the returned backend and closes
     *                        it on close.
     * @param signaling       The NetherNetServerSignaling instance for signaling.
     */
    public NetherNetServerChannel(Supplier<? extends WebRtcServerBackend> backendSupplier, NetherNetServerSignaling signaling) {
        this.backend = null;
        this.backendSupplier = backendSupplier;
        this.signaling = signaling;
        this.config = new DefaultNetherServerChannelConfig(this);
    }

    private static ServerIdentity generateDefaultIdentity() {
        try {
            return ServerIdentity.generate("self");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        if (!(localAddress instanceof InetSocketAddress)) throw new IllegalArgumentException("Unsupported address type");
        this.localAddress = (InetSocketAddress) localAddress;

        this.signaling.setNewConnectionHandler((connectionId, remoteNetworkId, offerSdp) -> {
            acceptConnection(connectionId, offerSdp, remoteNetworkId);
        });

        // Bind the signaling endpoint before any backend exists: binding is
        // the step that fails in the wild (a taken TCP port), and a lazily
        // supplied backend means that failure creates no native state at
        // all. Offers cannot outrun the backend: they hop onto this event
        // loop, which is still running doBind.
        this.signaling.bind(localAddress);

        if (backend == null) {
            try {
                backend = backendSupplier.get();
            } catch (Exception e) {
                // The signaling endpoint is up but the backend never came to
                // be; close signaling so the failed channel holds nothing.
                try {
                    signaling.close();
                } catch (Exception closeError) {
                    log.debug("Failed to close signaling after backend creation failed: {}", closeError.getMessage());
                }
                throw e;
            }
        }

        // Channel options are set between construction and bind, so this is
        // the moment to hand the configured port allocator settings (with
        // their NetherNet defaults: no TCP candidates, IPv6, shared socket)
        // to the backend. An explicit backend construction wins. Offers
        // queued behind doBind on this event loop see the configured
        // backend.
        if (backend instanceof LibWebRtcServerBackend) {
            ((LibWebRtcServerBackend) backend).applyDefaultPortAllocatorConfig(
                    config.getOption(NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG));
        }
    }

    /**
     * Accepts an incoming connection offer. Runs on the signaling I/O thread:
     * only the signal handler registration happens here (a cheap map put, so
     * candidates arriving right behind the offer are never dropped), then all
     * negotiation work hops onto this server channel's event loop. Backend
     * calls block on engine threads and must never stall the signaling
     * socket's thread, whose keepalives hold the connection to the signaling
     * service open.
     */
    public void acceptConnection(long connectionId, String offerSdp, String remoteNetworkId) {
        PendingConnection pending = new PendingConnection(connectionId);
        signaling.setSignalHandler(connectionId, signal -> eventLoop().execute(() -> pending.handleSignal(signal)));
        eventLoop().execute(() -> establishConnection(pending, connectionId, offerSdp, remoteNetworkId));
    }

    /**
     * Negotiates an accepted offer through the backend. Runs on this server
     * channel's event loop; incoming signals for the connection hop onto the
     * same loop, so everything here is single threaded and ordered.
     */
    private void establishConnection(PendingConnection pending, long connectionId, String offerSdp, String remoteNetworkId) {
        // Offers racing a failed doBind: signaling briefly accepted
        // connections but the backend never materialized.
        if (backend == null) {
            log.debug("Dropping offer {} received before the backend existed", Long.toUnsignedString(connectionId));
            signaling.removeSignalHandler(connectionId);
            return;
        }
        try {
            // An HTTP front end knows the peer's address from the request; ICE
            // nomination later overwrites it with the actual candidate pair.
            InetSocketAddress signaledAddress = signaling.remoteAddressOf(connectionId);
            NetherNetChildChannel child = new NetherNetChildChannel(this,
                    signaledAddress != null ? signaledAddress : generatePlaceholderAddress(), localAddress);
            // Fragment outbound data no larger than the client advertised it
            // can receive (a=max-message-size in its offer), falling back to
            // the conservative default when the client does not advertise one.
            child.setMaxOutboundMessageSize(NetherNetConstants.parseMaxMessageSize(offerSdp, NetherNetConstants.MAX_SCTP_MESSAGE_SIZE));

            child.closeFuture().addListener(future -> signaling.removeSignalHandler(connectionId));

            WebRtcSession session = backend.accept(offerSdp, signaling.getIceServers(),
                    new ChildSessionBridge(child, connectionId, remoteNetworkId), signaling.fullIceAnswers());
            child.attachSession(session);
            pending.attach(child, session);

            int handshakeTimeoutSeconds = this.config.getOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS);
            ScheduledFuture<?> handshakeTimeout = eventLoop().schedule(() -> {
                if (!child.isActive()) {
                    log.warn("Connection {} timed out during handshake ({}s)", Long.toUnsignedString(connectionId), handshakeTimeoutSeconds);
                    child.close();
                }
            }, handshakeTimeoutSeconds, TimeUnit.SECONDS);
            // The reaper exists only to catch a handshake that silently never
            // completes, leaving the child open but inactive. Any close makes
            // it obsolete; without this cancellation a session that connects
            // and ends within the timeout gets a bogus timed out warning,
            // because isActive() is also false after a completed life.
            child.closeFuture().addListener(future -> handshakeTimeout.cancel(false));
        } catch (Exception e) {
            log.error("Failed to establish connection {}: {}", Long.toUnsignedString(connectionId), e.getMessage(), e);
            signaling.removeSignalHandler(connectionId);
        }
    }

    /**
     * Bridges backend session events into the child channel and outbound
     * signaling. Callbacks arrive on engine threads; everything they touch
     * is thread safe (volatile channel state, netty writes, event loop
     * dispatch).
     */
    private final class ChildSessionBridge implements WebRtcSessionListener {
        private final NetherNetChildChannel child;
        private final long connectionId;
        private final String remoteNetworkId;

        private ChildSessionBridge(NetherNetChildChannel child, long connectionId, String remoteNetworkId) {
            this.child = child;
            this.connectionId = connectionId;
            this.remoteNetworkId = remoteNetworkId;
        }

        @Override
        public void onAnswerReady(String answerSdp) {
            String finalAnswer = answerSdp;
            // The built in self signed identity keeps answers acceptable to
            // 26.40 clients out of the box; consumers replace it to own the
            // keys and domain of the assertion.
            NetherNetAnswerDecorator decorator = config.getOption(NetherChannelOption.NETHER_SERVER_ANSWER_DECORATOR);
            if (decorator == null) {
                decorator = serverIdentity::augmentAnswer;
            }
            try {
                finalAnswer = decorator.decorate(answerSdp);
            } catch (Exception e) {
                // Peers require the decoration (the identity assertion); an
                // undecorated answer would only be refused by the peer after
                // parsing. Fail the exchange instead: the signaling layer
                // reports the error (a 400 on HTTP) and the peer falls back
                // immediately.
                log.warn("Answer decoration failed for {}; failing the exchange: {}",
                        Long.toUnsignedString(connectionId), e.getMessage());
                try {
                    signaling.sendSignal(remoteNetworkId, NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR
                            + " " + Long.toUnsignedString(connectionId) + " answer decoration failed");
                } catch (Exception signalError) {
                    // Signaling gone too; the handshake timeout reaps the child.
                    log.debug("Could not signal decoration failure for {}: {}",
                            Long.toUnsignedString(connectionId), signalError.getMessage());
                }
                child.close();
                return;
            }
            try {
                signaling.sendSignal(remoteNetworkId,
                        NetherNetConstants.buildSignalConnectResponse(connectionId, finalAnswer));
            } catch (Exception e) {
                // Signaling dropped mid handshake; the client cannot receive
                // the answer, so let the handshake timeout reap this
                // connection.
                log.warn("Failed to send answer for {} (signaling unavailable): {}",
                        Long.toUnsignedString(connectionId), e.getMessage());
                return;
            }
            pipeline().fireChannelRead(child);
        }

        @Override
        public void onLocalCandidate(String candidateSdp) {
            try {
                signaling.sendSignal(remoteNetworkId,
                        NetherNetConstants.buildSignalCandidateAdd(connectionId, candidateSdp));
            } catch (Exception e) {
                // Established connections do not signal candidates, so only
                // this in flight handshake is affected; the handshake timeout
                // cleans it up if it cannot complete.
                log.debug("Failed to signal ICE candidate for {} (signaling unavailable): {}",
                        Long.toUnsignedString(connectionId), e.getMessage());
            }
        }

        @Override
        public void onTransportOpen() {
            child.markTransportOpen();
        }

        @Override
        public void onMessage(java.nio.ByteBuffer data) {
            child.deliverInbound(data);
        }

        @Override
        public void onBytesSent(long bytes) {
            child.onEngineBytesSent(bytes);
        }

        @Override
        public void onRemoteAddress(InetSocketAddress address, String candidateType) {
            // Fires before the transport opens, so the channel carries its
            // real remote address before activation; the unique random
            // placeholder covers the window until then and the case of this
            // callback never firing. For relayed connections this is the TURN
            // relay, the peer actually connected to us. A re nomination
            // simply overwrites.
            child.remoteAddress = address;
            log.debug("Resolved remote address for {}: {} (type: {})",
                    Long.toUnsignedString(connectionId), address, candidateType);
        }

        @Override
        public void onTransportClosed() {
            child.markTransportClosed();
            child.close();
        }
    }

    /**
     * Per connection signal state. All methods run on this server channel's
     * event loop, so no synchronization is needed. Signals that arrive
     * between the offer and the session becoming available are queued and
     * drained by attach, preserving arrival order.
     */
    private final class PendingConnection {
        private final long connectionId;
        private NetherNetChildChannel child;
        private WebRtcSession session;
        private List<String> queued = new ArrayList<>();

        PendingConnection(long connectionId) {
            this.connectionId = connectionId;
        }

        void attach(NetherNetChildChannel child, WebRtcSession session) {
            this.child = child;
            this.session = session;
            List<String> pendingSignals = this.queued;
            this.queued = null;
            for (String signal : pendingSignals) {
                apply(signal);
            }
        }

        void handleSignal(String signal) {
            if (session == null) {
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
                    log.trace("Applying remote candidate for {}", Long.toUnsignedString(connectionId));
                    session.addRemoteCandidate(data);
                }
                case NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR -> {
                    log.debug("Received CONNECT_ERROR for {}", Long.toUnsignedString(connectionId));
                    child.close();
                }
            }
        }
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
            // Null when a lazily supplied backend never materialized
            // (signaling bind failed); there is nothing to tear down then.
            WebRtcServerBackend materialized = backend;
            if (materialized != null) {
                materialized.close();
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
