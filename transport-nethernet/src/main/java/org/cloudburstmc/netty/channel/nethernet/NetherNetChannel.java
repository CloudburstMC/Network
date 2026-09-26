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
import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.CandidatePair;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.cloudburstmc.netty.channel.nethernet.config.NetherConnectionFailure;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerState;

import java.util.List;
import java.util.ArrayList;
import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class NetherNetChannel extends AbstractChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChannel.class);
    protected static final ChannelMetadata METADATA = new ChannelMetadata(false);

    /**
     * One side of the selected pair carries the address the socket uses and the candidate type it
     * was gathered as: {@code host}, {@code srflx}, {@code prflx} or {@code relay}. A type is
     * {@code null} when the description names no candidate at that address.
     */
    public record Path(InetSocketAddress local, String localType, InetSocketAddress remote, String remoteType) {
    }

    private record DataChannels(DataChannel reliable, DataChannel unreliable) {
    }

    protected DefaultNetherChannelConfig config;
    protected volatile PeerConnection peerConnection;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;

    protected DataChannel reliableChannel;
    protected DataChannel unreliableChannel;

    protected final Queue<Object> pendingWrites = new ConcurrentLinkedQueue<>();

    private volatile NetherNetMessageAssembler reliableAssembler;
    private volatile NetherNetMessageAssembler unreliableAssembler;

    protected volatile boolean open = true;

    /**
     * The largest SCTP message this channel sends, from the peer's {@code a=max-message-size}. Read
     * at every write, so a limit learned after the pipeline was built still applies.
     */
    private volatile int maxOutboundMessageSize = NetherNetConstants.DEFAULT_SCTP_MESSAGE_SIZE;

    private volatile DataChannels pending;
    /** Read from the libdatachannel callback thread, so it cannot be plain. */
    private volatile boolean activeFired;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
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

    /**
     * The round trip time of the underlying transport in milliseconds, or {@code 0} while it is unknown, which
     * matches how the raknet transport reports a session without a completed ping.
     *
     * @return the round trip time in milliseconds
     */
    public long getPing() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return 0;
        }
        return peer.rtt().map(Duration::toMillis).orElse(0L);
    }

    /**
     * The candidate pair ICE settled on, which is where the media really flows: the signaling
     * endpoint a client dialed and the address a server saw a join from are only where the
     * conversation started. Once connected, a channel's remote address is the pair's remote side.
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

    /**
     * Sets the largest SCTP message this channel sends, normally the {@code a=max-message-size} the
     * peer advertised. Zero means the peer accepts any size;
     * {@link NetherNetConstants#MAX_OUTBOUND_MESSAGE_SIZE} still applies.
     *
     * @param size The peer's limit in bytes, header included
     * @throws IllegalArgumentException if negative, or too small for a header and payload
     */
    public void setMaxOutboundMessageSize(int size) {
        this.maxOutboundMessageSize = NetherNetConstants.outboundMessageSize(size);
    }

    public int getMaxOutboundMessageSize() {
        return this.maxOutboundMessageSize;
    }

    /**
     * The most payload one segment carries. libdatachannel refuses a message over its own reading of
     * the peer's limit, which it also holds to the size this side advertises, so the smaller applies.
     */
    protected int maxSegmentPayload() {
        int size = this.maxOutboundMessageSize;
        int engine = remoteMaxMessageSize();
        if (engine > 0) {
            size = Math.min(size, engine);
        }
        return size - 1;
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

        setDataChannels(channels.reliable(), channels.unreliable());
        pipeline().fireChannelActive();
    }

    public void setDataChannels(DataChannel reliable, DataChannel unreliable) {
        NetherNetMessageAssembler reliableMessages = new NetherNetMessageAssembler("reliable");
        NetherNetMessageAssembler unreliableMessages = new NetherNetMessageAssembler("unreliable");
        synchronized (this) {
            if (!open) {
                throw new IllegalStateException("Channel closed");
            }
            closeMessageAssemblers();
            reliableAssembler = reliableMessages;
            unreliableAssembler = unreliableMessages;
        }
        this.reliableChannel = reliable;
        this.unreliableChannel = unreliable;

        this.reliableChannel.onOpen.register(channel -> eventLoop().execute(this::onDataChannelStateChange));
        this.reliableChannel.onClosed.register(channel -> eventLoop().execute(this::onDataChannelStateChange));

        this.reliableChannel.onMessage.register(
                DataChannelCallback.Message.handleBinary((channel, data) -> onMessage(reliableMessages, data)));
        if (this.unreliableChannel != null) {
            this.unreliableChannel.onMessage.register(
                    DataChannelCallback.Message.handleBinary((channel, data) -> onMessage(unreliableMessages, data)));
        }

        if (reliableChannel.isOpen()) {
            eventLoop().execute(this::onDataChannelStateChange);
        }
    }

    private void onMessage(NetherNetMessageAssembler assembler, ByteBuffer data) {
        NetherChannelMetrics metrics = config.getMetrics();

        // The native ByteBuffer expires when this callback returns.
        ByteBuf packet = assembler.decode(data, alloc());
        if (packet == null) {
            if (metrics != null) {
                metrics.decodeFail(1);
            }
            return;
        }

        try {
            if (metrics != null) {
                metrics.messagesIn(1);
                metrics.bytesIn(packet.readableBytes());
            }
            eventLoop().execute(() -> {
                if (!isOpen() || (assembler != reliableAssembler && assembler != unreliableAssembler)) {
                    packet.release();
                    return;
                }
                pipeline().fireChannelRead(packet);
                pipeline().fireChannelReadComplete();
            });
        } catch (RuntimeException | Error e) {
            packet.release();
            throw e;
        }
    }

    private void onDataChannelStateChange() {
        if (isActive()) {
            if (!pendingWrites.isEmpty()) {
                pipeline().fireChannelWritabilityChanged();
                unsafe().flush();
            }
        } else if (reliableChannel != null && reliableChannel.isClosed()) {
            close();
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (!isActive()) {
            Object msg;
            while ((msg = in.current()) != null) {
                ReferenceCountUtil.retain(msg);
                pendingWrites.add(msg);
                in.remove();
            }
            return;
        }

        while (!pendingWrites.isEmpty()) {
            Object msg = pendingWrites.poll();
            try {
                writeInternal(msg);
            } catch (IllegalArgumentException refused) {
                // Its promise completed when it was queued, so only the pipeline can still hear of it
                pipeline().fireExceptionCaught(refused);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        Object msg;
        while ((msg = in.current()) != null) {
            try {
                writeInternal(msg);
            } catch (IllegalArgumentException refused) {
                in.remove(refused);
                continue;
            }
            in.remove();
        }
    }

    /**
     * Sends one message over the reliable channel. A failure while sending reaches the pipeline.
     *
     * @throws IllegalArgumentException if the message cannot be segmented, in which case nothing was sent
     */
    private void writeInternal(Object msg) {
        if (!(msg instanceof ByteBuf payload)) {
            log.debug("Dropping an outbound {}, which this channel cannot frame",
                    msg == null ? null : msg.getClass().getName());
            return;
        }

        if (reliableChannel.isClosed()) {
            return;
        }

        int maxPayload = maxSegmentPayload();
        // Checked before the first segment goes out, so the peer is never left inside a message
        NetherNetConstants.segmentCount(payload.readableBytes(), maxPayload);

        ByteBuf framed = payload.retainedDuplicate();
        int totalLength = framed.readableBytes();

        try {
            int segments = segment(framed, alloc(), maxPayload, reliableChannel::sendMessage);
            if (segments == 0) {
                log.debug("Nothing sent for an empty outbound message");
            } else {
                log.trace("Wrote {} bytes to the reliable channel in {} segments", totalLength, segments);

                NetherChannelMetrics metrics = config.getMetrics();
                if (metrics != null) {
                    metrics.messagesOut(segments);
                    metrics.bytesOut(totalLength);
                }
            }
        } catch (Exception e) {
            pipeline().fireExceptionCaught(e);
        } finally {
            framed.release();
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
    static int segment(ByteBuf framed, ByteBufAllocator allocator, int maxPayload,
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
        eventLoop().execute(this::activate0);
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

        Object msg;
        while ((msg = pendingWrites.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Closes the data channels and peer connection, dropping their listeners first.
     */
    protected void closeWebRTC() {
        closeMessageAssemblers();
        this.pending = null;
        if (reliableChannel != null) {
            deregisterAll(reliableChannel);
            reliableChannel.close();
            reliableChannel = null;
        }
        if (unreliableChannel != null) {
            deregisterAll(unreliableChannel);
            unreliableChannel.close();
            unreliableChannel = null;
        }
        if (peerConnection != null) {
            deregisterAll(peerConnection);
            peerConnection.close();
            peerConnection = null;
        }
    }

    private synchronized void closeMessageAssemblers() {
        if (reliableAssembler != null) {
            reliableAssembler.close();
            reliableAssembler = null;
        }
        if (unreliableAssembler != null) {
            unreliableAssembler.close();
            unreliableAssembler = null;
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
        return isOpen() && this.reliableChannel != null && this.reliableChannel.isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    protected void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }
}
