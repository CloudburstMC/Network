package org.cloudburstmc.netty.channel.nethernet;

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
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
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

    private volatile DataChannels pending;
    private boolean activeFired;

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
        this.localAddress = local;
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
     * The ICE candidate pair traffic currently flows over, or {@code null} until the peer connection
     * is connected. The pair can change after connection, so callers should not cache it.
     * <p>
     * Types are what ICE reports for the pair. The remote side is where traffic actually arrives
     * from, which behind a NAT is usually a peer reflexive candidate the offer never carried. For a
     * pair that is not relayed, libjuice sends from one socket and does not track which local
     * candidate it used, so the local side is the first one it gathered. A {@code relay} type on
     * either side means the traffic passes through a TURN server.
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
     * Hands this channel the data channels it carries, activating it once they are in place.
     * Idempotent, and safe to call from a libdatachannel callback.
     *
     * @param reliable   The reliable data channel, which this channel sends over
     * @param unreliable The unreliable data channel, or {@code null} when the peer opened none
     */
    public void activate(DataChannel reliable, DataChannel unreliable) {
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
        NetherChannelMetrics metrics = config.getOption(NetherChannelOption.NETHER_METRICS);

        // The native ByteBuffer expires when this callback returns.
        ByteBuf packet = assembler.decode(data, alloc());
        if (packet == null) {
            if (metrics != null) {
                metrics.decodeFail(1);
            }
            return;
        }

        if (metrics != null) {
            metrics.messagesIn(1);
            metrics.bytesIn(packet.readableBytes());
        }

        try {
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
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        Object msg;
        while ((msg = in.current()) != null) {
            writeInternal(msg);
            in.remove();
        }
    }

    private void writeInternal(Object msg) {
        if (!(msg instanceof ByteBuf payload)) {
            log.debug("Dropping an outbound {}, which this channel cannot frame",
                    msg == null ? null : msg.getClass().getName());
            return;
        }

        if (reliableChannel.isClosed()) {
            return;
        }

        ByteBuf framed = payload.retainedDuplicate();
        int totalLength = framed.readableBytes();

        try {
            int segments = segment(framed, alloc(), NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1,
                    reliableChannel::sendMessage);
            if (segments == 0) {
                log.debug("Nothing sent for an empty outbound message");
            } else {
                log.trace("Wrote {} bytes to the reliable channel in {} segments", totalLength, segments);

                NetherChannelMetrics metrics = config.getOption(NetherChannelOption.NETHER_METRICS);
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
     */
    static int segment(ByteBuf framed, ByteBufAllocator allocator, int maxPayload,
                       Consumer<ByteBuffer> sender) {
        int totalLength = framed.readableBytes();
        int segments = (totalLength + maxPayload - 1) / maxPayload;
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

    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }
}
