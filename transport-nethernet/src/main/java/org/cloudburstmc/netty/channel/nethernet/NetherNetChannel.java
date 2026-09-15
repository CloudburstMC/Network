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
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class NetherNetChannel extends AbstractChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChannel.class);
    protected static final ChannelMetadata METADATA = new ChannelMetadata(false);

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
     * Candidate types come from the descriptions rather than the native selected pair, whose Java
     * binding only carries addresses. A {@code relay} type on either side means the traffic passes
     * through a TURN server.
     */
    public Path selectedPath() {
        PeerConnection peer = this.peerConnection;
        if (peer == null) {
            return null;
        }
        try {
            InetSocketAddress local = peer.localAddress();
            InetSocketAddress remote = peer.remoteAddress();
            return new Path(local, candidateType(peer.localDescription(), local),
                    remote, candidateType(peer.remoteDescription(), remote));
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
     * Finds the {@code typ} of the candidate line in an SDP that names the given address, or
     * {@code null} when none does, which happens for a peer reflexive pair discovered mid check.
     */
    static String candidateType(String sdp, InetSocketAddress address) {
        if (sdp == null || address == null) {
            return null;
        }
        InetAddress ip = address.getAddress();
        for (String line : sdp.split("\\r?\\n")) {
            if (!line.startsWith("a=candidate:")) {
                continue;
            }
            // a=candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> ...
            String[] parts = line.split(" ");
            if (parts.length < 8 || !"typ".equals(parts[6])) {
                continue;
            }
            try {
                if (Integer.parseInt(parts[5]) == address.getPort()
                        && InetAddress.getByName(parts[4]).equals(ip)) {
                    return parts[7].toLowerCase(Locale.ROOT);
                }
            } catch (Exception malformed) {
                // A line this parser does not understand is not the one we are looking for
            }
        }
        return null;
    }

    /**
     * One side of the selected pair carries the address the socket uses and the candidate type it
     * was gathered as: {@code host}, {@code srflx}, {@code prflx} or {@code relay}. A type is
     * {@code null} when the description names no candidate at that address.
     */
    public record Path(InetSocketAddress local, String localType, InetSocketAddress remote, String remoteType) {
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
        // The native ByteBuffer expires when this callback returns.
        ByteBuf packet = assembler.decode(data, alloc());
        if (packet == null) {
            return;
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

        ByteBuf framed = payload.retainedDuplicate();
        int totalLength = framed.readableBytes();

        try {
            int segments = segment(framed, alloc(), NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1,
                    reliableChannel::sendMessage);
            if (segments == 0) {
                log.debug("Nothing sent for an empty outbound message");
            } else {
                log.trace("Wrote {} bytes to the reliable channel in {} segments", totalLength, segments);
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
