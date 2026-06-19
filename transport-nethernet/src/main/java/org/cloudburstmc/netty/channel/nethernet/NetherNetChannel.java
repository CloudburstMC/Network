package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import dev.kastle.webrtc.RTCDataChannel;
import dev.kastle.webrtc.RTCDataChannelBuffer;
import dev.kastle.webrtc.RTCDataChannelObserver;
import dev.kastle.webrtc.RTCDataChannelState;
import dev.kastle.webrtc.RTCPeerConnection;
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class NetherNetChannel extends AbstractChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChannel.class);
    protected static final ChannelMetadata METADATA = new ChannelMetadata(false);

    protected DefaultNetherChannelConfig config;
    protected volatile RTCPeerConnection peerConnection;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;

    protected RTCDataChannel reliableChannel;
    protected RTCDataChannel unreliableChannel;

    protected final Queue<Object> pendingWrites = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean channelActiveFired = new AtomicBoolean();

    protected volatile boolean open = true;

    // Reassembly state for inbound fragmented messages. assemblyBuf is written on
    // the WebRTC callback thread (the data channel observer) and released on the
    // event loop (doClose), so every access is guarded by assemblyLock together
    // with the assemblyClosed flag, which orders the release strictly after the
    // observer is detached and prevents a use-after-free.
    private final Object assemblyLock = new Object();
    private ByteBuf assemblyBuf;
    private boolean assemblyClosed;
    private int currentSegmentCount = -1;

    // Maximum outbound SCTP message size in bytes. writeInternal must never
    // fragment beyond what the remote advertised it can receive (the
    // a=max-message-size attribute in its SDP); subclasses set this from the
    // negotiated description. Defaults to the conservative MAX_SCTP_MESSAGE_SIZE
    // until negotiation supplies the real value.
    protected volatile int maxOutboundMessageSize = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE;

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
        this.localAddress = local;
    }

    public void setDataChannels(RTCDataChannel reliable, RTCDataChannel unreliable) {
        this.reliableChannel = reliable;
        // The unreliable channel is intentionally stored but never observed or
        // written to: this is a reliable-only implementation, which gives the
        // ordered, lossless stream a Bedrock translation proxy needs. If it is
        // ever wired up it must stay single-message only, because an unreliable
        // channel can drop or reorder and would strand a multi-fragment message
        // mid-reassembly: observe it but never run reassembly on it (every
        // inbound message must arrive complete, header 0), and on send drop
        // anything too large to fit one message rather than fragmenting it.
        this.unreliableChannel = unreliable;

        synchronized (assemblyLock) {
            if (assemblyBuf == null && !assemblyClosed) {
                assemblyBuf = config.getAllocator().buffer();
            }
        }

        RTCDataChannelObserver observer = new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                eventLoop().execute(() -> onDataChannelStateChange());
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                ByteBuffer data = buffer.data;
                if (!data.hasRemaining())
                    return;

                int segments = data.get() & 0xFF;

                synchronized (assemblyLock) {
                    if (assemblyClosed || assemblyBuf == null) {
                        return;
                    }

                    if (currentSegmentCount == -1) {
                        currentSegmentCount = segments;
                    } else {
                        if (segments != currentSegmentCount - 1) {
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

                                eventLoop().execute(() -> {
                                    fireChannelActiveIfReady();
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
        };

        this.reliableChannel.registerObserver(observer);

        if (reliableChannel.getState() == RTCDataChannelState.OPEN) {
            eventLoop().execute(this::onDataChannelStateChange);
        }
    }

    /**
     * Sets the maximum outbound SCTP message size, in bytes, for this channel.
     * Should be the {@code a=max-message-size} the remote peer advertised in its
     * SDP. Values too small to leave room for the fragment header are ignored.
     *
     * @param size the negotiated maximum message size
     */
    public void setMaxOutboundMessageSize(int size) {
        if (size > 1) {
            this.maxOutboundMessageSize = size;
        }
    }

    private void onDataChannelStateChange() {
        if (isActive()) {
            fireChannelActiveIfReady();
        } else if (reliableChannel.getState() == RTCDataChannelState.CLOSED) {
            close();
        }
    }

    protected void fireChannelActiveIfReady() {
        if (!isRegistered() || !isActive()) {
            return;
        }

        if (channelActiveFired.compareAndSet(false, true)) {
            pipeline().fireChannelActive();
        }

        if (!pendingWrites.isEmpty()) {
            pipeline().fireChannelWritabilityChanged();
            unsafe().flush();
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
        if (!(msg instanceof ByteBuf))
            return;

        ByteBuf payload = (ByteBuf) msg;

        int maxPayload = maxOutboundMessageSize - 1;
        int totalLength = payload.readableBytes();

        int segments = (totalLength / maxPayload);
        if (totalLength % maxPayload != 0)
            segments++;

        // Each fragment carries a one-byte countdown header, so a message can
        // span at most 256 fragments. The negotiated max-message-size keeps this
        // ceiling far out of reach, but guard it anyway: without the check an
        // oversized message would wrap the header byte and silently corrupt the
        // stream instead of failing.
        if (segments > 256) {
            pipeline().fireExceptionCaught(new IllegalStateException(
                "Outbound message of " + totalLength + " bytes exceeds the maximum "
                    + (256 * maxPayload) + " bytes addressable by the fragment header"));
            return;
        }

        ByteBuf framed = payload.retainedDuplicate();
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

                reliableChannel.send(new RTCDataChannelBuffer(chunk, true));
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
        if (isActive()) {
            channelActiveFired.set(true);
        }
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

        if (reliableChannel != null) {
            reliableChannel.unregisterObserver();
            reliableChannel.close();
        }
        if (unreliableChannel != null) {
            unreliableChannel.unregisterObserver();
            unreliableChannel.close();
        }
        if (peerConnection != null) {
            peerConnection.close();
        }

        // Release the reassembly buffer now that the observer is unregistered.
        // Guarded by assemblyLock with assemblyClosed so it cannot race, or be
        // re-allocated by, an in-flight onMessage on the WebRTC callback thread.
        synchronized (assemblyLock) {
            assemblyClosed = true;
            if (assemblyBuf != null) {
                assemblyBuf.release();
                assemblyBuf = null;
            }
            currentSegmentCount = -1;
        }

        Object msg;
        while ((msg = pendingWrites.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
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
        return isOpen() && this.reliableChannel != null && this.reliableChannel.getState() == RTCDataChannelState.OPEN;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
