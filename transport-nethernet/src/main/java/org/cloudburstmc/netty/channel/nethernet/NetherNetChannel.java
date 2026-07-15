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
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base netty channel for NetherNet connections. Deliberately thin: it moves
 * already framed messages between the pipeline and the underlying WebRTC
 * transport and manages activation state. NetherNet's countdown framing is
 * NOT handled here; pipelines built on this channel must install
 * {@link org.cloudburstmc.netty.channel.nethernet.codec.NetherNetFramingCodec},
 * which performs fragmentation and reassembly. Messages written to this
 * channel are therefore expected to already carry their framing header and
 * fit within the negotiated maximum message size, and messages fired into
 * the pipeline still carry their header byte.
 */
public abstract class NetherNetChannel extends AbstractChannel {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetChannel.class);
    protected static final ChannelMetadata METADATA = new ChannelMetadata(false);

    /**
     * Stop handing messages to the engine once this many bytes sit unsent in
     * its buffer. The engine's own buffer is finite and overflowing it kills
     * the connection, so above this mark writes stay queued in netty's
     * outbound buffer (flipping the channel's writability flag) and the
     * remote peer's receive rate paces the flow.
     */
    private static final long ENGINE_HIGH_WATER_MARK = 2 * 1024 * 1024;
    /** Resume handing messages to the engine below this many unsent bytes. */
    private static final long ENGINE_RESUME_LOW_WATER_MARK = 512 * 1024;
    /**
     * A peer that cannot drain the engine buffer AND lets this many bytes
     * accumulate behind it is not experiencing a burst, it is unrecoverably
     * slow; close deterministically instead of queueing without bound.
     */
    private static final long MAX_BACKLOG_BYTES = 8 * 1024 * 1024;

    protected DefaultNetherChannelConfig config;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;

    private final Queue<Object> pendingWrites = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean channelActiveFired = new AtomicBoolean();

    // Bytes handed to the engine and not yet reported sent via
    // onEngineBytesSent. Incremented on the event loop, decremented on engine
    // threads.
    private final AtomicLong engineOutstanding = new AtomicLong();
    // Set on the event loop when doWrite pauses on the high water mark; the
    // engine thread that drains below the low water mark clears it and
    // schedules the resume flush. While at least ENGINE_HIGH_WATER_MARK bytes
    // are outstanding, more sent callbacks are guaranteed, so a set flag is
    // always observed.
    private volatile boolean writesPaused;

    private volatile boolean transportOpen;

    // Latest ICE round trip time in milliseconds, sampled periodically while
    // the channel is active; negative until the first measurement arrives.
    // Failed samples keep the last good value.
    private volatile long rttMillis = -1;
    private ScheduledFuture<?> rttSampler;
    protected volatile boolean open = true;

    /**
     * Maximum outbound SCTP message size in bytes, the a=max-message-size the
     * remote peer advertised. Read live by the framing codec so a value
     * negotiated after pipeline construction is honored.
     */
    private volatile int maxOutboundMessageSize = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE;

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
        this.localAddress = local;
    }

    /**
     * Sets the maximum outbound SCTP message size, in bytes, for this channel.
     * Should be the {@code a=max-message-size} the remote peer advertised in
     * its SDP. Values too small to leave room for the fragment header are
     * ignored.
     *
     * @param size the negotiated maximum message size
     */
    public void setMaxOutboundMessageSize(int size) {
        if (size > 1) {
            this.maxOutboundMessageSize = size;
        }
    }

    public int getMaxOutboundMessageSize() {
        return maxOutboundMessageSize;
    }

    /**
     * Signals that the underlying transport can carry traffic. Safe to call
     * from any thread; fires channelActive on the event loop once the channel
     * is also registered.
     */
    protected void markTransportOpen() {
        transportOpen = true;
        if (isRegistered()) {
            eventLoop().execute(this::fireChannelActiveIfReady);
        }
    }

    protected void markTransportClosed() {
        transportOpen = false;
    }

    /**
     * Delivers one raw framed message from the transport into the pipeline.
     * Safe to call from engine threads: the callback scoped buffer is copied
     * exactly once here, then handed to the event loop in arrival order.
     */
    protected void deliverInbound(ByteBuffer data) {
        if (!open || !data.hasRemaining()) {
            return;
        }
        ByteBuf copy = config.getAllocator().buffer(data.remaining());
        copy.writeBytes(data);
        try {
            eventLoop().execute(() -> {
                fireChannelActiveIfReady();
                pipeline().fireChannelRead(copy);
                pipeline().fireChannelReadComplete();
            });
        } catch (Exception e) {
            // Event loop rejected the task (shutdown race); do not leak.
            copy.release();
        }
    }

    protected void fireChannelActiveIfReady() {
        if (!isRegistered() || !isActive()) {
            return;
        }

        // Callers include native WebRTC callback threads (data channel state
        // changes). The pipeline fire methods would marshal themselves, but
        // unsafe().flush() is event loop only, so hop for the whole body.
        EventLoop loop = eventLoop();
        if (!loop.inEventLoop()) {
            loop.execute(this::fireChannelActiveIfReady);
            return;
        }

        if (channelActiveFired.compareAndSet(false, true)) {
            // Started before firing active: a handler that closes the channel
            // synchronously from channelActive runs doClose first otherwise,
            // and the sampler created afterwards would never be cancelled.
            startRttSampler();
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
            if (engineSaturated(in)) {
                return;
            }
            Object msg = pendingWrites.poll();
            try {
                writeFramed(msg);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        Object msg;
        while ((msg = in.current()) != null) {
            if (engineSaturated(in)) {
                return;
            }
            writeFramed(msg);
            in.remove();
        }
    }

    /**
     * True while the engine's send buffer holds too much unsent data to
     * accept more. Leaves the remaining messages queued (netty's outbound
     * buffer plus the pre-activation queue); {@link #onEngineBytesSent}
     * resumes the flush once the buffer drains. A peer whose backlog also
     * exceeds the hard cap is closed instead.
     */
    private boolean engineSaturated(ChannelOutboundBuffer in) {
        if (engineOutstanding.get() < ENGINE_HIGH_WATER_MARK) {
            return false;
        }
        writesPaused = true;
        if (in.totalPendingWriteBytes() > MAX_BACKLOG_BYTES) {
            log.warn("Closing {}: peer cannot keep up ({} bytes unsent in the engine, {} bytes backlogged)",
                remoteAddress, engineOutstanding.get(), in.totalPendingWriteBytes());
            close();
        }
        return true;
    }

    /**
     * Reports engine buffer drain progress (bytes written to the wire).
     * Called from engine threads via the session listener; resumes a paused
     * write path once the buffer is below the low water mark.
     */
    protected void onEngineBytesSent(long bytes) {
        long outstanding = engineOutstanding.addAndGet(-bytes);
        if (outstanding < 0) {
            // Sends dropped by the engine or counter reset races; clamp.
            engineOutstanding.compareAndSet(outstanding, 0);
            outstanding = 0;
        }
        if (writesPaused && outstanding <= ENGINE_RESUME_LOW_WATER_MARK) {
            writesPaused = false;
            try {
                eventLoop().execute(() -> {
                    if (open) {
                        unsafe().flush();
                    }
                });
            } catch (Exception ignored) {
                // Event loop rejected the task (shutdown race).
            }
        }
    }

    private void writeFramed(Object msg) {
        if (!(msg instanceof ByteBuf)) {
            return;
        }
        ByteBuf framed = (ByteBuf) msg;
        int bytes = framed.readableBytes();
        // Count before handing to the engine: its bytes sent callback fires
        // from engine threads and can beat the statement after sendFramed,
        // where a subtract first would clamp to zero and the late increment
        // would inflate the counter permanently, wedging the write gate.
        engineOutstanding.addAndGet(bytes);
        try {
            sendFramed(framed);
        } catch (Exception e) {
            engineOutstanding.addAndGet(-bytes);
            pipeline().fireExceptionCaught(e);
        }
    }

    /**
     * Ships one already framed message (header byte included, at most the
     * negotiated maximum message size) to the transport. Must not take
     * ownership of the buffer; the caller releases it. Runs on the event
     * loop.
     */
    protected abstract void sendFramed(ByteBuf framed);

    /**
     * Converts a framed buffer into a NIO buffer suitable for the WebRTC
     * send path, which requires position and limit to delimit the payload
     * and handles direct memory most efficiently.
     */
    protected static ByteBuffer toNioBuffer(ByteBuf framed) {
        ByteBuffer nio = framed.nioBuffer();
        if (!nio.isDirect()) {
            ByteBuffer direct = ByteBuffer.allocateDirect(nio.remaining());
            direct.put(nio);
            direct.flip();
            return direct;
        }
        return nio;
    }

    @Override
    protected void doRegister() throws Exception {
        if (isActive()) {
            // Netty's register flow fires channelActive itself for already
            // active channels; pre set the flag so it is not fired twice, and
            // start the sampler here since fireChannelActiveIfReady's CAS
            // will never win on this path.
            channelActiveFired.set(true);
            startRttSampler();
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

    /**
     * @return the latest sampled transport round trip time in milliseconds,
     *         or a negative value while no measurement is available yet
     */
    public long rttMillis() {
        return rttMillis;
    }

    /**
     * Requests one transport RTT measurement; the callback receives
     * milliseconds or a negative value when unavailable. Subclasses with an
     * RTT source override this.
     */
    protected void requestRttSample(DoubleConsumer callback) {
        callback.accept(-1);
    }

    // Runs on the event loop, once, when the channel goes active.
    private void startRttSampler() {
        if (rttSampler != null || !isOpen()) {
            return;
        }
        rttSampler = eventLoop().scheduleAtFixedRate(
                () -> requestRttSample(ms -> {
                    if (ms >= 0) {
                        rttMillis = Math.round(ms);
                    }
                }),
                1, 3, TimeUnit.SECONDS);
    }

    @Override
    protected void doClose() throws Exception {
        this.open = false;
        this.transportOpen = false;
        this.writesPaused = false;
        this.engineOutstanding.set(0);

        if (rttSampler != null) {
            rttSampler.cancel(false);
            rttSampler = null;
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
        return isOpen() && transportOpen;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }
}
