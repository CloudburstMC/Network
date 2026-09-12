package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
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

import java.net.InetSocketAddress;
import java.time.Duration;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
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

    public void setDataChannels(DataChannel reliable, DataChannel unreliable) {
        this.reliableChannel = reliable;
        this.unreliableChannel = unreliable;

        this.reliableChannel.onOpen.register(channel -> eventLoop().execute(this::onDataChannelStateChange));
        this.reliableChannel.onClosed.register(channel -> eventLoop().execute(this::onDataChannelStateChange));

        this.reliableChannel.onMessage.register(
                DataChannelCallback.Message.handleBinary(new MessageAssembler("reliable")));
        if (this.unreliableChannel != null) {
            this.unreliableChannel.onMessage.register(
                    DataChannelCallback.Message.handleBinary(new MessageAssembler("unreliable")));
        }

        if (reliableChannel.isOpen()) {
            eventLoop().execute(this::onDataChannelStateChange);
        }
    }

    /**
     * Reassembles the segmented messages of one data channel. The two channels are separate
     * streams, so each needs its own assembler.
     */
    private final class MessageAssembler implements DataChannelCallback.BinaryMessage {
        private final ByteBuf assemblyBuf = config.getAllocator().buffer();
        private final String label;
        private int currentSegmentCount = -1;

        MessageAssembler(String label) {
            this.label = label;
        }

        @Override
        public void onBinary(DataChannel channel, ByteBuffer data) {
            if (!data.hasRemaining()) {
                log.debug("Empty message on the {} channel", label);
                return;
            }

            int segments = data.get() & 0xFF;

            if (currentSegmentCount == -1) {
                currentSegmentCount = segments;
            } else {
                if (segments != currentSegmentCount - 1) {
                    log.debug("Discarding {} assembled bytes on the {} channel: segment {} does not follow {}",
                            assemblyBuf.readableBytes(), label, segments, currentSegmentCount);
                    assemblyBuf.clear();
                    currentSegmentCount = -1;
                    return;
                }
                currentSegmentCount = segments;
            }

            if (data.hasRemaining()) {
                byte[] payload = new byte[data.remaining()];
                data.get(payload);
                assemblyBuf.writeBytes(payload);
            }

            if (segments == 0) {
                try {
                    if (assemblyBuf.isReadable()) {
                        ByteBuf packet = assemblyBuf.copy();
                        assemblyBuf.skipBytes(assemblyBuf.readableBytes());

                        log.trace("Read {} bytes from the {} channel", packet.readableBytes(), label);
                        eventLoop().execute(() -> {
                            pipeline().fireChannelRead(packet);
                            pipeline().fireChannelReadComplete();
                        });
                    }
                } catch (Exception e) {
                    log.error("Error processing packet", e);
                } finally {
                    assemblyBuf.clear();
                    currentSegmentCount = -1;
                }
            }
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
        if (!(msg instanceof ByteBuf)) {
            log.debug("Dropping an outbound {}, which this channel cannot frame", msg == null ? null :
                    msg.getClass().getName());
            return;
        }

        ByteBuf payload = (ByteBuf) msg;

        ByteBuf framed = payload.retainedDuplicate();

        int totalLength = framed.readableBytes();
        int maxPayload = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1;

        int segments = (totalLength / maxPayload);
        if (totalLength % maxPayload != 0) {
            segments++;
        }
        try {
            int offset = 0;
            for (int i = 0; i < segments; i++) {
                int remaining = segments - 1 - i;
                int chunkSize = Math.min(maxPayload, framed.readableBytes() - offset);

                ByteBuffer chunk = ByteBuffer.allocateDirect(1 + chunkSize);
                chunk.put((byte) remaining);

                framed.getBytes(offset, chunk);
                chunk.position(chunk.limit());
                chunk.flip();

                reliableChannel.sendMessage(chunk);
                offset += chunkSize;
            }
        } catch (Exception e) {
            pipeline().fireExceptionCaught(e);
        } finally {
            framed.release();
        }
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
