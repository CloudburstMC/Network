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

import tel.schich.libdatachannel.PeerConnectionConfiguration;
import org.cloudburstmc.netty.channel.PendingMessages;
import org.cloudburstmc.netty.channel.TransportChannel;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import org.cloudburstmc.netty.util.nethernet.IceProxyProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.EventLoop;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jspecify.annotations.Nullable;
import tel.schich.libdatachannel.CandidatePair;
import tel.schich.libdatachannel.CloseReason;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherConnectionFailure;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SctpStats;
import tel.schich.libdatachannel.PeerState;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.concurrent.RejectedExecutionException;

/**
 * A NetherNet connection, carrying each message whole over a reliable and an unreliable data channel.
 * <p>
 * Writes work as on RakNet: a flushed message goes to native at once and its future completes when it is
 * handed over, and once more than {@link #MAX_QUEUED_BYTES} wait to be sent the connection ends with
 * {@link TransportDisconnectReason#QUEUE_TOO_LONG}. Unlike RakNet, {@link #isWritable()} also turns off while
 * either data channel holds more than 512 KiB. A plain {@link ByteBuf} goes on the reliable channel, a
 * {@link NetherNetPacket} on the one it names.
 * <p>
 * Reads ignore {@code autoRead}, as on RakNet, and each message follows a {@link NetherNetPacket.Delivery}
 * naming its data channel. Broken framing on the reliable channel ends the connection with
 * {@link TransportDisconnectReason#BAD_PACKET}, a message over 8 MiB, RakNet's split budget, with
 * {@link TransportDisconnectReason#SPLIT_QUEUE_TOO_LONG}.
 */
public abstract class NetherNetChannel extends AbstractChannel implements TransportChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChannel.class);
    protected static final ChannelMetadata METADATA = new ChannelMetadata(false);
    static final int MAX_SEGMENT_PAYLOAD = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1;
    /** As many segments as the countdown numbers, each the size a retail client sends. */
    static final int MAX_MESSAGE_SIZE = NetherNetConstants.MAX_SEGMENTS * MAX_SEGMENT_PAYLOAD;
    /** RakNet's split budget, as a retail client splits at the advertised size and may send more than this side. */
    static final int MAX_RECEIVED_MESSAGE_SIZE = 8 << 20;
    /**
     * Past this many bytes waiting in the data channels the connection ends, RakNet's default limit. What the SCTP
     * association buffers below them does not count, so only a remote that stopped reading gets here.
     */
    public static final int MAX_QUEUED_BYTES = 64 << 20;
    /** Writability turns off past this many bytes waiting in either data channel. */
    static final int WRITABILITY_HIGH = 512 << 10;
    /** And back on once both are down to this many. */
    static final int WRITABILITY_LOW = 256 << 10;
    private static final int NATIVE_WRITABILITY = 1;

    /**
     * One side of the selected pair carries the address the socket uses and the candidate type it
     * was gathered as: {@code host}, {@code srflx}, {@code prflx} or {@code relay}. A type is
     * {@code null} when the description names no candidate at that address.
     */
    public record Path(InetSocketAddress local, String localType, InetSocketAddress remote, String remoteType) {
    }

    private record DataChannels(DataChannel reliable, DataChannel unreliable) {
    }

    private record Reader(DataChannel channel, NetherNetMessageAssembler assembler, boolean reliable) {
    }

    /** How many messages may arrive before the data channels are bound, far more than a join sends. */
    static final int MAX_EARLY_MESSAGES = 64;

    protected DefaultNetherChannelConfig config;
    protected volatile PeerConnection peerConnection;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;
    /** See {@link #signaledAddress()}. */
    protected volatile SourcedAddress signaledAddress;
    /** See {@link #mediaAddress()}. */
    private volatile SourcedAddress mediaAddress = SourcedAddress.unknown();
    /** The PROXY protocol relays on the ICE port, which every peer there comes through, or null when none are set. */
    protected volatile @Nullable IceProxyProtocol iceRelays;
    /** See {@link NetherChannelOption#NETHER_SERVER_PREFER_PROXIED_ADDRESS}. */
    protected volatile boolean preferProxiedAddress;
    /** The current attempt's remote candidates, which know the peer's TURN relays. */
    volatile RemoteCandidates remoteCandidates;

    /** Read off the loop by {@link #isActive}, so it cannot be plain. */
    protected volatile DataChannel reliableChannel;
    protected DataChannel unreliableChannel;

    private volatile Reader reliableReader;
    private volatile Reader unreliableReader;

    /** Messages read before the data channels were bound, delivered after channelActive. Guarded by this. */
    private final PendingMessages<NetherNetPacket> earlyMessages =
            new PendingMessages<>(MAX_EARLY_MESSAGES, MAX_RECEIVED_MESSAGE_SIZE,
                    early -> early.content().readableBytes());
    /** Whether reads can go to the loop yet, rather than wait in {@link #earlyMessages}. Guarded by this. */
    private boolean readsRegistered;
    /** Whether reads failed before there was a loop to close on, which closes once there is. Guarded by this. */
    private boolean failedBeforeRegistration;
    /** The send queue limit, which only tests lower. Event loop only. */
    int maxQueuedBytes = MAX_QUEUED_BYTES;

    protected volatile boolean open = true;

    private volatile DataChannels pending;
    /** Read from the libdatachannel callback thread, so it cannot be plain. */
    private volatile boolean activeFired;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    protected NetherNetChannel(Channel parent, @Nullable SourcedAddress signaled, InetSocketAddress local) {
        super(parent);
        // A client only learns where it signals to when it connects
        this.signaledAddress = signaled == null ? SourcedAddress.unknown() : signaled;
        this.remoteAddress = signaled == null ? null : signaled.address();
        this.localAddress = local;
    }

    /**
     * Reports that this connection ended before it ever carried traffic, at most once per attempt.
     * Both sides call it, so a failure counts the same whether this channel dialed out or was
     * accepted.
     */
    protected void connectionFailed(NetherConnectionFailure reason) {
        NetherChannelMetrics metrics = config.getMetrics();
        if (metrics != null && this.failureReported.compareAndSet(false, true)) {
            metrics.connectionFailed(reason);
        }
    }

    /** Starts a new attempt, so the next failure is reportable again. */
    protected void clearFailureReported() {
        this.failureReported.set(false);
    }

    /**
     * Registers the peer callbacks that only report metrics, so neither side has to repeat them.
     * Call it once per peer connection, which on the client means again after every retry; closing
     * the channel drops them with the peer's other listeners.
     */
    protected void registerMetrics(PeerConnection peer) {
        if (peer == null) {
            return;
        }
        peer.onStateChange.register((p, state) -> {
            NetherChannelMetrics metrics = config.getMetrics();
            if (metrics != null) {
                metrics.peerStateChange(state);
            }
            if (state == PeerState.RTC_CONNECTED) {
                Path path = selectedPath();
                if (metrics != null && path != null) {
                    metrics.pathSelected(path.localType(), path.remoteType());
                }
            } else if ((state == PeerState.RTC_FAILED || state == PeerState.RTC_CLOSED) && !this.activeFired) {
                connectionFailed(state == PeerState.RTC_FAILED ? NetherConnectionFailure.PEER_FAILED
                        : NetherConnectionFailure.PEER_CLOSED);
            }
        });
        peer.onIceStateChange.register((p, state) -> {
            NetherChannelMetrics metrics = config.getMetrics();
            if (metrics != null) {
                metrics.iceStateChange(state);
            }
        });
    }

    @Override
    public long getPing() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return 0;
        }
        return peer.rtt().map(Duration::toMillis).orElse(0L);
    }

    /**
     * What the SCTP association under this channel reports about itself, or null while there is
     * none. Its counters are cumulative, so a rate comes from the difference between two reads.
     *
     * @return The association's stats, or null
     */
    public SctpStats sctpStats() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return null;
        }
        try {
            return peer.sctpStats().orElse(null);
        } catch (Exception closed) {
            return null;
        }
    }

    /**
     * The candidate pair ICE settled on, which is where the media really flows: the signaling
     * endpoint a client dialed and the address a server saw a join from are only where the
     * conversation started. {@link #remoteAddress()} takes the pair's remote side once active.
     *
     * @return The selected pair, or null while there is none
     */
    public Path selectedPath() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return null;
        }
        try {
            CandidatePair pair = peer.selectedCandidatePair();
            return new Path(pair.local(), pair.localType(), pair.remote(), pair.remoteType());
        } catch (Exception notConnected) {
            return null;
        }
    }

    /**
     * The largest message the remote accepts on a data channel, or {@code 0} while unknown. Larger
     * batches are segmented before they are sent.
     */
    public int remoteMaxMessageSize() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return 0;
        }
        try {
            return Math.max(0, peer.remoteMaxMessageSize());
        } catch (Exception notConnected) {
            return 0;
        }
    }

    @Override
    public int maxMessageSize() {
        return MAX_MESSAGE_SIZE;
    }

    /**
     * Hands this channel the data channels it carries, activating it once they are in place.
     * Idempotent, and safe to call from a libdatachannel callback.
     *
     * @param reliable   The reliable data channel, which this channel sends over
     * @param unreliable The unreliable data channel, or {@code null} when the peer opened none
     */
    protected void activate(DataChannel reliable, DataChannel unreliable) {
        this.pending = new DataChannels(reliable, unreliable);
        if (isRegistered()) {
            eventLoop().execute(this::activate0);
        }
        // Before registration there is no event loop to queue on, so doRegister queues it instead
    }

    /**
     * Activates the channel, always as a queued task so that it is still inactive while registration
     * decides whether to fire {@code channelActive} itself. That leaves the firing here, once.
     */
    private void activate0() {
        DataChannels channels = this.pending;
        if (activeFired || channels == null || !open) {
            return;
        }
        activeFired = true;

        adoptSelectedPath();
        setDataChannels(channels.reliable(), channels.unreliable());
        pipeline().fireChannelActive();

        NetherNetPacket early;
        boolean read = false;
        while ((early = pollEarly()) != null) {
            fireRead(early.reliable(), early.content());
            read = true;
        }
        if (read) {
            pipeline().fireChannelReadComplete();
        }
    }

    private synchronized NetherNetPacket pollEarly() {
        return earlyMessages.poll();
    }

    /**
     * Takes the selected pair as {@link #mediaAddress()} and moves the remote address onto what
     * {@link #reportedAddress()} makes of it. Event loop only, right before the channel goes active.
     *
     * @return The selected pair, or null when there is none and the addresses stay as they were
     */
    protected @Nullable Path adoptSelectedPath() {
        Path path = selectedPath();
        InetSocketAddress remote = path == null ? null : path.remote();
        if (remote == null || remote.isUnresolved()) {
            return path;
        }
        // Taken now, while the relay's flow is fresh, as libjuice forgets a quiet one
        InetSocketAddress via = relayOf(remote);
        AddressSource source = isRelay(path) ? AddressSource.TURN
                : via != null ? AddressSource.ICE_PROXY_PROTOCOL : AddressSource.ICE;
        this.mediaAddress = new SourcedAddress(remote, source, via);
        // A network id names the peer rather than a place, so it stays
        if (this.remoteAddress instanceof InetSocketAddress) {
            this.remoteAddress = reportedAddress().address();
        }
        return path;
    }

    /** The PROXY protocol relay that brought the peer, or null when none did or the lookup failed. */
    private @Nullable InetSocketAddress relayOf(InetSocketAddress peer) {
        IceProxyProtocol relays = this.iceRelays;
        if (relays == null) {
            return null;
        }
        try {
            return relays.relayOf(peer);
        } catch (RuntimeException e) {
            log.debug("Could not look up the relay of {}: {}", peer, e.toString());
            return null;
        }
    }

    /**
     * Whether the pair's remote side is one of the peer's TURN relays: typed so, or a relay the peer signaled,
     * which is how one arrives through a PROXY protocol relay, as peer reflexive.
     */
    private boolean isRelay(Path path) {
        RemoteCandidates candidates = this.remoteCandidates;
        return "relay".equals(path.remoteType())
                || (candidates != null && path.remote() != null && candidates.isRelay(path.remote()));
    }

    /**
     * Where signaling met the peer, kept for the channel's life: how a child's join arrived, or the
     * address a client dialed. {@link AddressSource#UNKNOWN} when signaling did not say, as for a
     * client dialing a network id.
     */
    public SourcedAddress signaledAddress() {
        return this.signaledAddress;
    }

    /**
     * The selected pair's remote side, as taken when the channel went active.
     * {@link AddressSource#UNKNOWN} before then, or when ICE reported none.
     */
    public SourcedAddress mediaAddress() {
        return this.mediaAddress;
    }

    /**
     * What {@link #remoteAddress()} reports, with where it came from. A client dialing a network id
     * keeps that as its remote address, and this says where the peer turned out to be.
     */
    public SourcedAddress reportedAddress() {
        return reported(this.signaledAddress, this.mediaAddress, this.preferProxiedAddress);
    }

    /** See {@link #remoteAddress()}. */
    static SourcedAddress reported(SourcedAddress signaled, SourcedAddress media, boolean preferProxied) {
        if (media.source() == AddressSource.UNKNOWN || (preferProxied && proxied(signaled))) {
            return signaled;
        }
        // A TURN server only stands in for the peer, but only the peer says it is one
        return media.source() == AddressSource.TURN && unforged(signaled) ? signaled : media;
    }

    private static boolean proxied(SourcedAddress signaled) {
        return signaled.source() == AddressSource.SIGNALING_PROXY_PROTOCOL
                || signaled.source() == AddressSource.SIGNALING_FORWARDED_FOR;
    }

    /** Whether the signaled address is known and came from somewhere a forged datagram cannot plant it. */
    private static boolean unforged(SourcedAddress signaled) {
        InetAddress known = signaled.address().getAddress();
        if (known == null || known.isAnyLocalAddress()) {
            return false;
        }
        return switch (signaled.source()) {
            case SIGNALING, SIGNALING_PROXY_PROTOCOL, SIGNALING_FORWARDED_FOR, HOST -> true;
            default -> false;
        };
    }

    /**
     * Fires {@code channelActive} for a channel that activates itself instead of through
     * {@link #activate}, so it reports why the connection ends like any other. Event loop only.
     */
    protected void fireActive() {
        activeFired = true;
        pipeline().fireChannelActive();
    }

    /**
     * Binds the data channels this channel carries, reading any that {@link #readDataChannel} does not
     * already.
     */
    public void setDataChannels(DataChannel reliable, DataChannel unreliable) {
        if (!isReading(reliableReader, reliable)) {
            readDataChannel(reliable, true);
        }
        if (unreliable != null && !isReading(unreliableReader, unreliable)) {
            readDataChannel(unreliable, false);
        }
        this.reliableChannel = reliable;
        this.unreliableChannel = unreliable;
        watchBufferedAmount(reliable);
        if (unreliable != null) {
            watchBufferedAmount(unreliable);
        }

        reliable.onClosed.register(channel -> closeFromTransport());
        if (reliable.isClosed()) {
            // Closed before the listener could hear it; queued, as the caller may be about to fire channelActive
            eventLoop().execute(this::closeFromTransport);
        }
    }

    private static boolean isReading(Reader reader, DataChannel channel) {
        return reader != null && reader.channel() == channel;
    }

    /** Lets writability come back once a data channel drains, see {@link #updateWritability}. */
    private void watchBufferedAmount(DataChannel channel) {
        try {
            channel.bufferedAmountLowThreshold(WRITABILITY_LOW);
        } catch (RuntimeException closed) {
            return;
        }
        channel.onBufferedAmountLow.register(dc -> {
            try {
                eventLoop().execute(this::updateWritability);
            } catch (RejectedExecutionException shutDown) {
                // Nothing will be written anymore
            }
        });
    }

    /**
     * Reads a data channel from the moment the peer opens it, since libdatachannel queues what
     * nobody reads and blocks a native worker once 1024 messages wait. Messages read before
     * {@link #setDataChannels} binds it are delivered after channelActive, and past
     * {@link #MAX_EARLY_MESSAGES} the channel closes. Safe to call from a libdatachannel callback,
     * also before this channel is registered.
     *
     * @param channel  The data channel to read
     * @param reliable Whether it is the reliable one
     */
    protected void readDataChannel(DataChannel channel, boolean reliable) {
        Reader reader = new Reader(channel, new NetherNetMessageAssembler(reliable), reliable);
        synchronized (this) {
            if (!open) {
                throw new IllegalStateException("Channel closed");
            }
            Reader previous = reliable ? reliableReader : unreliableReader;
            if (previous != null) {
                previous.assembler().close();
            }
            if (reliable) {
                reliableReader = reader;
            } else {
                unreliableReader = reader;
            }
        }
        channel.onMessage.register(DataChannelCallback.Message.handleBinary((dc, data) -> onMessage(reader, data)));
    }

    private void onMessage(Reader reader, ByteBuffer data) {
        NetherChannelMetrics metrics = config.getMetrics();

        ByteBuf packet;
        try {
            // The native ByteBuffer expires when this callback returns.
            packet = reader.assembler().decode(data, alloc(), metrics);
        } catch (NetherNetFramingException broken) {
            log.debug("Closing {}: {}", this, broken.getMessage());
            closeFromCallback(broken.reason());
            return;
        }
        if (packet == null) {
            return;
        }

        try {
            countReceived(packet.readableBytes());
            if (!holdBeforeRegistration(reader, packet)) {
                eventLoop().execute(() -> deliver(reader, packet));
            }
        } catch (RuntimeException | Error e) {
            packet.release();
            throw e;
        }
    }

    /**
     * Holds a message that arrived before this channel had a loop, which an accepted channel can be without while
     * its peer already carries messages.
     *
     * @return Whether the message was taken
     */
    private synchronized boolean holdBeforeRegistration(Reader reader, ByteBuf packet) {
        if (readsRegistered) {
            return false;
        }
        if (!earlyMessages.offer(new NetherNetPacket(packet, reader.reliable()))) {
            failedBeforeRegistration = true;
        }
        return true;
    }

    /** Event loop only. */
    private void deliver(Reader reader, ByteBuf packet) {
        if (!isOpen() || (reader != reliableReader && reader != unreliableReader)) {
            packet.release();
        } else if (reliableChannel == null) {
            boolean held;
            synchronized (this) {
                held = earlyMessages.offer(new NetherNetPacket(packet, reader.reliable()));
            }
            if (!held) {
                log.debug("Closing {}, too much arrived before its data channels were bound", this);
                close();
            }
        } else {
            fireRead(reader.reliable(), packet);
            pipeline().fireChannelReadComplete();
        }
    }

    private void fireRead(boolean reliable, ByteBuf message) {
        pipeline().fireUserEventTriggered(NetherNetPacket.Delivery.of(reliable));
        pipeline().fireChannelRead(message);
    }

    /** Closes for {@code reason} from a libdatachannel callback, or once registered if this channel is not yet. */
    private void closeFromCallback(TransportDisconnectReason reason) {
        synchronized (this) {
            if (!readsRegistered) {
                failedBeforeRegistration = true;
                return;
            }
        }
        try {
            eventLoop().execute(() -> closeWithReason(reason));
        } catch (RejectedExecutionException shutDown) {
            closeWithoutLoop();
        }
    }

    /** Closes for a reason this side found, telling the pipeline once it is active. Event loop only. */
    private void closeWithReason(TransportDisconnectReason reason) {
        if (!isOpen()) {
            return;
        }
        if (activeFired) {
            pipeline().fireUserEventTriggered(reason);
        }
        close();
    }

    /**
     * Closes because the connection ended underneath this channel, first telling the pipeline why.
     * Safe to call from a libdatachannel callback. Those are registered only once the channel is,
     * and its loop stays set after it closes, so there always is a loop to close on.
     */
    protected void closeFromTransport() {
        if (!isOpen()) {
            return;
        }
        if (!eventLoop().inEventLoop()) {
            try {
                eventLoop().execute(this::closeFromTransport);
            } catch (RejectedExecutionException shutDown) {
                closeWithoutLoop();
            }
            return;
        }
        fireDisconnectReason();
        close();
    }

    /**
     * Tears the connection down off the loop, for a loop that was shut down under an open channel
     * and runs nothing anymore, as Netty does for a channel it could not register.
     */
    private void closeWithoutLoop() {
        log.debug("Closing {} without its event loop, which was shut down", this);
        try {
            doClose();
        } catch (Exception e) {
            log.debug("Closing {} failed", this, e);
        }
    }

    private void fireDisconnectReason() {
        TransportDisconnectReason reason = disconnectReason();
        if (reason != null) {
            pipeline().fireUserEventTriggered(reason);
        }
    }

    /** Why the connection ended, or null before activation and when this side closed it. */
    private @Nullable TransportDisconnectReason disconnectReason() {
        PeerConnection peer = this.peerConnection;
        if (!activeFired || peer == null) {
            return null;
        }
        CloseReason reason;
        try {
            reason = peer.closeReason();
        } catch (RuntimeException deleted) {
            return null;
        }
        log.debug("{} ended: {}", this, reason);
        return switch (reason) {
            // The peer is still up, so either the remote closed the data channel or a send failed
            case RTC_CLOSE_REASON_NONE -> reliableChannel != null && reliableChannel.isClosed()
                    ? TransportDisconnectReason.CLOSED_BY_REMOTE_PEER : TransportDisconnectReason.TRANSPORT_FAILED;
            case RTC_CLOSE_REASON_REMOTE_SHUTDOWN -> TransportDisconnectReason.CLOSED_BY_REMOTE_PEER;
            case RTC_CLOSE_REASON_REMOTE_ABORT -> TransportDisconnectReason.ABORTED_BY_REMOTE_PEER;
            case RTC_CLOSE_REASON_SCTP_TIMEOUT -> TransportDisconnectReason.TIMED_OUT;
            case RTC_CLOSE_REASON_ICE_FAILED -> TransportDisconnectReason.CONNECTION_LOST;
            case RTC_CLOSE_REASON_TRANSPORT_FAILED -> TransportDisconnectReason.TRANSPORT_FAILED;
            case RTC_CLOSE_REASON_LOCAL -> null;
        };
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        // Refused before it is queued, so the write's promise fails and nothing is sent
        if (msg instanceof ByteBuf payload) {
            NetherNetConstants.segmentCount(payload.readableBytes(), MAX_SEGMENT_PAYLOAD);
        } else if (msg instanceof NetherNetPacket packet) {
            int length = packet.content().readableBytes();
            if (packet.reliable()) {
                NetherNetConstants.segmentCount(length, MAX_SEGMENT_PAYLOAD);
            } else if (length > MAX_SEGMENT_PAYLOAD) {
                throw new IllegalArgumentException("An unreliable message of " + length
                        + " bytes is more than the one segment of " + MAX_SEGMENT_PAYLOAD + " it may take");
            }
        } else {
            throw new UnsupportedOperationException("Unsupported message type: " + StringUtil.simpleClassName(msg));
        }
        return msg;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        Object msg;
        while ((msg = in.current()) != null) {
            boolean reliable = !(msg instanceof NetherNetPacket packet) || packet.reliable();
            ByteBuf payload = msg instanceof NetherNetPacket packet ? packet.content() : (ByteBuf) msg;
            DataChannel channel = reliable ? reliableChannel : unreliableChannel;
            if (channel == null) {
                in.remove(new IllegalStateException("The peer opened no unreliable data channel"));
                continue;
            }
            if (channel.isClosed()) {
                // Gone underneath this channel, whose close is already on its way. Dropped quietly, as a host's
                // batch flush lands here when a player quits
                in.remove();
                continue;
            }

            int length = payload.readableBytes();
            if (queuedBytes() + length > maxQueuedBytes) {
                log.debug("Closing {}, more than {} bytes are waiting to be sent", this, maxQueuedBytes);
                pipeline().fireUserEventTriggered(TransportDisconnectReason.QUEUE_TOO_LONG);
                close();
                return;
            }

            try {
                int segments = segment(payload, alloc(), MAX_SEGMENT_PAYLOAD, channel::sendMessage);
                if (segments == 0) {
                    log.debug("Nothing sent for an empty outbound message");
                } else {
                    log.trace("Wrote {} bytes to the {} channel in {} segments", length,
                            reliable ? "reliable" : "unreliable", segments);
                    countSent(length, segments);
                }
                in.remove();
            } catch (RuntimeException e) {
                // Closed here whatever AUTO_CLOSE says: without it Netty only shuts the output down,
                // which leaves a channel that never reports closing
                log.debug("Sending on {} failed: {}", this, e.toString());
                // The reason first: a write on a void promise reports its failure as an exception, and a
                // handler that closes on it would otherwise never learn why the connection ended
                fireDisconnectReason();
                in.remove(new IOException("Sending on the " + (reliable ? "reliable" : "unreliable")
                        + " data channel failed", e));
                close();
                return;
            }
        }
        updateWritability();
    }

    /** What the data channels hold that native has yet to send. */
    private long queuedBytes() {
        return (long) buffered(reliableChannel) + buffered(unreliableChannel);
    }

    private static int buffered(@Nullable DataChannel channel) {
        if (channel == null) {
            return 0;
        }
        try {
            return channel.bufferedAmount();
        } catch (RuntimeException closed) {
            return 0;
        }
    }

    /**
     * Turns writability off while either data channel holds more than {@link #WRITABILITY_HIGH} and back on once
     * both are down to {@link #WRITABILITY_LOW}. Read afresh each time, as the native callback that lowers it can
     * run before the write that raised it gets here. Event loop only.
     */
    private void updateWritability() {
        ChannelOutboundBuffer out = unsafe().outboundBuffer();
        if (out == null || reliableChannel == null) {
            return;
        }
        int reliable = buffered(reliableChannel);
        int unreliable = buffered(unreliableChannel);
        if (reliable > WRITABILITY_HIGH || unreliable > WRITABILITY_HIGH) {
            out.setUserDefinedWritability(NATIVE_WRITABILITY, false);
        } else if (reliable <= WRITABILITY_LOW && unreliable <= WRITABILITY_LOW) {
            out.setUserDefinedWritability(NATIVE_WRITABILITY, true);
        }
    }

    /** Counts a message delivered whole. */
    protected final void countReceived(int bytes) {
        NetherChannelMetrics metrics = config.getMetrics();
        if (metrics != null) {
            metrics.messagesIn(1);
            metrics.bytesIn(bytes);
        }
    }

    /** Counts a message handed to a data channel in {@code segments} segments. */
    protected final void countSent(int bytes, int segments) {
        NetherChannelMetrics metrics = config.getMetrics();
        if (metrics != null) {
            metrics.messagesOut(1);
            metrics.bytesOut(bytes);
            if (segments > 1) {
                metrics.fragmentsOut(segments - 1);
            }
        }
    }

    /**
     * Splits a message into segments carrying the countdown header, handing each to {@code sender}.
     * <p>
     * A segment is passed as a view of a pooled buffer that is released once {@code sender}
     * returns, so a sender that keeps the bytes must copy them. The native send does.
     *
     * @param framed     The message to split, read absolutely so its own indexes are left alone
     * @param allocator  Where the segment buffers come from
     * @param maxPayload The most payload one segment may carry, excluding the header byte
     * @param sender     Takes each segment, in order
     * @return How many segments were handed over
     * @throws IllegalArgumentException as {@link NetherNetConstants#segmentCount}, before any segment is handed over
     */
    protected static int segment(ByteBuf framed, ByteBufAllocator allocator, int maxPayload,
                                 Consumer<ByteBuffer> sender) {
        int totalLength = framed.readableBytes();
        int segments = NetherNetConstants.segmentCount(totalLength, maxPayload);
        int start = framed.readerIndex();

        for (int i = 0, offset = 0; i < segments; i++, offset += maxPayload) {
            int chunkSize = Math.min(maxPayload, totalLength - offset);
            ByteBuf chunk = allocator.directBuffer(1 + chunkSize, 1 + chunkSize);
            try {
                chunk.writeByte(segments - 1 - i);
                chunk.writeBytes(framed, start + offset, chunkSize);
                sender.accept(chunk.nioBuffer(chunk.readerIndex(), chunk.readableBytes()));
            } finally {
                chunk.release();
            }
        }
        return segments;
    }

    @Override
    protected void doRegister() throws Exception {
        boolean failed;
        synchronized (this) {
            readsRegistered = true;
            failed = failedBeforeRegistration;
        }
        eventLoop().execute(failed ? this::close : this::activate0);
    }

    @Override
    protected void doDeregister() throws Exception {
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException("NetherNetChannel cannot be bound directly");
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
        closeWebRTC();
    }

    /**
     * Closes the data channels and peer connection, dropping their listeners first. The peer goes
     * last, through {@link #closePeer}, so a failing teardown cannot skip the rest.
     */
    protected void closeWebRTC() {
        Reader reliable;
        Reader unreliable;
        synchronized (this) {
            reliable = reliableReader;
            unreliable = unreliableReader;
            reliableReader = null;
            unreliableReader = null;
            earlyMessages.clear();
        }
        closeReader(reliable);
        closeReader(unreliable);
        reliableChannel = null;
        unreliableChannel = null;
        this.pending = null;

        PeerConnection peer = peerConnection;
        peerConnection = null;
        if (peer != null) {
            deregisterAll(peer);
        }
        closePeer(peer);
    }

    /**
     * Tears the peer down, in the background, as deleting it waits for native workers.
     *
     * @param peer The peer, or null when there is none
     */
    protected void closePeer(@Nullable PeerConnection peer) {
        if (peer != null) {
            peer.closeAsync();
        }
    }

    private static void closeReader(Reader reader) {
        if (reader != null) {
            reader.assembler().close();
            deregisterAll(reader.channel());
            reader.channel().close();
        }
    }

    /**
     * The configured ICE servers with the signaling's added, since a host may name its own and a
     * signaling that hands some out is not a reason to lose them.
     */
    static List<URI> withIceServers(PeerConnectionConfiguration configured, List<IceServerInfo> fromSignaling) {
        List<URI> servers = new ArrayList<>(configured.iceServers());
        for (IceServerInfo info : fromSignaling) {
            for (URI uri : info.toUris()) {
                if (!servers.contains(uri)) {
                    servers.add(uri);
                }
            }
        }
        return servers;
    }

    /** Whether an ICE server is a TURN relay rather than a STUN server. */
    static boolean isTurn(URI server) {
        String scheme = server.getScheme();
        return "turn".equalsIgnoreCase(scheme) || "turns".equalsIgnoreCase(scheme);
    }

    static void deregisterAll(PeerConnection peer) {
        peer.onLocalDescription.deregisterAll();
        peer.onLocalCandidate.deregisterAll();
        peer.onStateChange.deregisterAll();
        peer.onIceStateChange.deregisterAll();
        peer.onGatheringStateChange.deregisterAll();
        peer.onSignalingStateChange.deregisterAll();
        peer.onDataChannel.deregisterAll();
        peer.onTrack.deregisterAll();
    }

    static void deregisterAll(DataChannel channel) {
        channel.onOpen.deregisterAll();
        channel.onClosed.deregisterAll();
        channel.onError.deregisterAll();
        channel.onMessage.deregisterAll();
        channel.onBufferedAmountLow.deregisterAll();
        channel.onAvailable.deregisterAll();
    }

    @Override
    protected void doBeginRead() throws Exception {
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return this.localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return this.remoteAddress;
    }

    /**
     * Where the peer is. Before {@code channelActive}, the {@linkplain #signaledAddress() signaled
     * address}. From {@code channelActive} on, the {@linkplain #mediaAddress() selected pair}, where the
     * media flows, which PROXY protocol relays on the ICE port already resolve to the client they
     * declare. A pair through one of the peer's TURN relays, behind those relays too, keeps the
     * signaled address instead when the signaling learned it from a connection, a trusted proxy or
     * the host. Which candidates are relays is the peer's own claim, so a peer can always choose that
     * address, and a LAN datagram's source, which anyone can forge, is never kept. A server can report
     * a trusted signaling proxy's address instead, see
     * {@link NetherChannelOption#NETHER_SERVER_PREFER_PROXIED_ADDRESS}. The pair is taken once, so a
     * later renomination does not move it. {@link #reportedAddress()} says which this is.
     */
    @Override
    public SocketAddress remoteAddress() {
        // AbstractChannel caches the first read, which an initializer makes before ICE connects
        return this.remoteAddress;
    }

    @Override
    public SocketAddress localAddress() {
        return this.localAddress;
    }

    @Override
    public ChannelConfig config() {
        return this.config;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    public boolean isActive() {
        // Not the data channel's own state, which changes off the loop: a flush would find this channel open but
        // inactive and fail, and the close that follows would skip channelInactive
        return isOpen() && this.reliableChannel != null;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
