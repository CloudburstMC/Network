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

    // Mirrors the reliable channel's OPEN state, updated from the data channel
    // observer. isActive() is called on every write and formerly crossed JNI
    // into a blocking native call for it; the cached flag makes it free.
    private volatile boolean reliableOpen;

    // Reusable outbound fragment buffer. Confined to the event loop (doWrite),
    // and safe to reuse across sends because the native layer copies the data
    // out before sendAsync returns.
    private ByteBuffer outboundScratch;

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
        // Seed the cached state once; the observer keeps it current from here.
        this.reliableOpen = reliable.getState() == RTCDataChannelState.OPEN;
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
                // The ByteBuffer wraps native memory that is only valid for
                // the duration of this callback, so every path below must copy
                // exactly once into a netty buffer before handing off.
                ByteBuffer data = buffer.data;
                if (!data.hasRemaining())
                    return;

                int segments = data.get() & 0xFF;

                synchronized (assemblyLock) {
                    if (assemblyClosed || assemblyBuf == null) {
                        return;
                    }

                    // Fast path: complete single-segment message (the common
                    // case) skips the assembly buffer entirely.
                    if (currentSegmentCount == -1 && segments == 0) {
                        if (data.hasRemaining()) {
                            deliver(copyToBuf(data));
                        }
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
                        assemblyBuf.writeBytes(data);
                    }

                    if (segments == 0) {
                        currentSegmentCount = -1;
                        if (assemblyBuf.isReadable()) {
                            // Hand the assembled buffer off to the pipeline and
                            // start a fresh one instead of copying it again.
                            ByteBuf packet = assemblyBuf;
                            assemblyBuf = config.getAllocator().buffer();
                            deliver(packet);
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
     * Copies the remaining bytes of the given callback-scoped buffer into a
     * freshly allocated netty buffer.
     */
    private ByteBuf copyToBuf(ByteBuffer data) {
        ByteBuf packet = config.getAllocator().buffer(data.remaining());
        packet.writeBytes(data);
        return packet;
    }

    /**
     * Fires a fully reassembled packet down the pipeline on the event loop.
     * Releases the packet if the event loop rejects the task (shutdown race)
     * so the buffer cannot leak.
     */
    private void deliver(ByteBuf packet) {
        try {
            eventLoop().execute(() -> {
                fireChannelActiveIfReady();
                pipeline().fireChannelRead(packet);
                pipeline().fireChannelReadComplete();
            });
        } catch (Exception e) {
            packet.release();
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
        RTCDataChannel channel = this.reliableChannel;
        if (channel == null) {
            return;
        }
        // One native call per state transition; every other isActive() check
        // reads the cached flag.
        RTCDataChannelState state = channel.getState();
        reliableOpen = state == RTCDataChannelState.OPEN;

        if (isActive()) {
            fireChannelActiveIfReady();
        } else if (state == RTCDataChannelState.CLOSED) {
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
            // One reusable direct buffer for every fragment of every message:
            // sendAsync copies the bytes out before returning, so the scratch
            // can be refilled immediately, and unlike the blocking send it
            // never stalls this event loop on the native network thread.
            ByteBuffer chunk = outboundScratch(1 + maxPayload);

            int offset = 0;
            for (int i = 0; i < segments; i++) {
                int remaining = segments - 1 - i;
                int chunkSize = Math.min(maxPayload, framed.readableBytes() - offset);

                chunk.clear();
                chunk.limit(1 + chunkSize);
                chunk.put((byte) remaining);

                framed.getBytes(offset, chunk);
                chunk.flip();

                reliableChannel.sendAsync(new RTCDataChannelBuffer(chunk, true));
                offset += chunkSize;
            }
        } catch (Exception e) {
            pipeline().fireExceptionCaught(e);
        } finally {
            framed.release();
        }
    }

    /**
     * Returns the reusable outbound fragment buffer, growing it when the
     * negotiated max message size demands more. Only used from doWrite on the
     * event loop.
     */
    private ByteBuffer outboundScratch(int capacity) {
        ByteBuffer scratch = this.outboundScratch;
        if (scratch == null || scratch.capacity() < capacity) {
            scratch = ByteBuffer.allocateDirect(capacity);
            this.outboundScratch = scratch;
        }
        return scratch;
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
        this.reliableOpen = false;
        this.outboundScratch = null;

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
        // Hot path: called around every write. Reads the observer-maintained
        // flag instead of a blocking JNI call into the native network thread.
        return isOpen() && this.reliableChannel != null && this.reliableOpen;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
