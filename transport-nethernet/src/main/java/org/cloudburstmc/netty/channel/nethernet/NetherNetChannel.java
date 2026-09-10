package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.codec.NetherNetFramingCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Base netty channel for NetherNet connections. Deliberately thin: it moves
 * already framed messages between the pipeline and the underlying WebRTC
 * transport and manages activation state. NetherNet's countdown framing is
 * NOT handled here; pipelines built on this channel must install
 * {@link org.cloudburstmc.netty.channel.nethernet.codec.NetherNetFramingCodec},
 * which performs fragmentation and reassembly. Messages written to this
 * channel are therefore expected to already carry their framing header and
 * fit within the negotiated maximum message size, and messages fired into
 * the pipeline still carry their header byte. Reliable frames are byte buffers;
 * unreliable frames use {@link NetherNetUnreliableFrame} until the codec decodes
 * them into independent messages. All outbound writes remain reliable.
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
    // Leave room for two maximum-sized messages, including their fragment headers.
    private static final int MAX_PENDING_INBOUND_MESSAGES = 2 * NetherNetFramingCodec.MAX_FRAGMENT_COUNT;
    private static final long MAX_PENDING_INBOUND_BYTES = 2L * NetherNetFramingCodec.MAX_REASSEMBLED_SIZE
            + MAX_PENDING_INBOUND_MESSAGES;
    private static final int MAX_MESSAGES_PER_DRAIN = 64;

    protected DefaultNetherChannelConfig config;
    protected volatile SocketAddress remoteAddress;
    protected volatile SocketAddress localAddress;

    private final AtomicBoolean channelActiveFired = new AtomicBoolean();
    private final Object inboundLock = new Object();
    private final Runnable inboundDrainTask = this::drainInbound;
    private final Runnable clearReadPendingTask = () -> this.readPending = false;
    private volatile boolean inboundReady;
    private volatile boolean inboundClosed;
    private volatile boolean readPending;
    private Queue<Object> pendingInbound;
    private int pendingInboundBytes;
    private boolean inboundDrainScheduled;
    private volatile boolean inboundDrainRejected;

    // Native acceptance completes a write; only buffered-amount notifications
    // reduce this counter. Failed sends may also be included in those deltas.
    private final AtomicLong engineOutstanding = new AtomicLong();
    private final AtomicReference<IOException> writeFailure = new AtomicReference<>();
    private final AtomicBoolean writeCompletionScheduled = new AtomicBoolean();
    private final Runnable writeCompletionTask = this::completeWrites;
    private final Runnable shutdownHook = () -> {
        if (isOpen() && eventLoop().inEventLoop()) {
            IOException failure = writeFailure.get();
            ((NetherNetUnsafe) unsafe()).closeWithSendFailure(failure != null ? failure
                    : new IOException("Event loop stopped before NetherNet sends completed"));
        }
    };
    // Netty retains native-pending writes; keep unsent frames reachable without scanning them.
    private PendingWrite unsubmittedHead;
    private PendingWrite unsubmittedTail;
    private PendingWrite flushedWriteTail;
    private boolean removingCompletedWrites;
    // Set on the event loop when doWrite pauses on the high water mark; the
    // engine thread that drains below the low water mark clears it and
    // schedules the resume flush. The writer rechecks the counter after
    // setting the flag so a concurrent drain cannot lose the wakeup.
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
    private volatile int maxOutboundMessageSize = NetherNetConstants.DEFAULT_SCTP_MESSAGE_SIZE;

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
        this.localAddress = local;
    }

    /**
     * Sets the maximum outbound SCTP message size, in bytes, for this channel.
     * Should be the {@code a=max-message-size} the remote peer advertised in
     * its SDP. Zero means unlimited; our local outgoing ceiling still applies.
     *
     * @param size the negotiated maximum message size
     * @throws IllegalArgumentException if negative or too small for a header and payload
     */
    public void setMaxOutboundMessageSize(int size) {
        this.maxOutboundMessageSize = NetherNetConstants.outboundMessageSize(size);
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
        deliverInbound(data, true);
    }

    /**
     * Copies a callback-scoped frame, preserving its data channel until decoding.
     * Both channels share read demand and the same inbound queue budget.
     */
    protected void deliverInbound(ByteBuffer data, boolean reliable) {
        if (!open || inboundClosed || !data.hasRemaining()) {
            return;
        }
        boolean overflow = false;
        synchronized (inboundLock) {
            if (!isOpen() || inboundClosed) {
                return;
            }
            int bytes = data.remaining();
            if (bytes <= MAX_PENDING_INBOUND_BYTES - pendingInboundBytes
                    && (pendingInbound == null || pendingInbound.size() < MAX_PENDING_INBOUND_MESSAGES)) {
                ByteBuf copy = config.getAllocator().buffer(bytes);
                try {
                    copy.writeBytes(data);
                } catch (Throwable cause) {
                    copy.release();
                    throw cause;
                }
                if (pendingInbound == null) {
                    pendingInbound = new ArrayDeque<>();
                }
                pendingInbound.add(reliable ? copy : new NetherNetUnreliableFrame(copy));
                pendingInboundBytes += bytes;
            } else {
                inboundClosed = true;
                overflow = true;
            }
        }
        if (overflow) {
            discardPendingInbound();
            log.warn("Closing {}: inbound backlog exceeded {} frames or {} bytes",
                    remoteAddress, MAX_PENDING_INBOUND_MESSAGES, MAX_PENDING_INBOUND_BYTES);
            close();
        } else {
            requestInboundDrain();
        }
    }

    /** Drops frames belonging to an abandoned transport attempt. */
    protected final void discardPendingInbound() {
        Queue<Object> pending;
        synchronized (inboundLock) {
            pending = pendingInbound;
            pendingInbound = null;
            pendingInboundBytes = 0;
        }
        if (pending != null) {
            Object frame;
            while ((frame = pending.poll()) != null) {
                ReferenceCountUtil.release(frame);
            }
        }
    }

    private boolean canReadInbound() {
        return inboundReady && !inboundClosed && isRegistered() && isActive()
                && (config.isAutoRead() || readPending);
    }

    private void requestInboundDrain() {
        synchronized (inboundLock) {
            if (inboundDrainScheduled || pendingInbound == null || pendingInbound.isEmpty() || !canReadInbound()) {
                return;
            }
            inboundDrainScheduled = true;
        }
        scheduleInboundDrain();
    }

    private void scheduleInboundDrain() {
        EventLoop loop = eventLoop();
        try {
            loop.execute(inboundDrainTask);
        } catch (RejectedExecutionException e) {
            synchronized (inboundLock) {
                inboundDrainScheduled = false;
                inboundDrainRejected = true;
            }
            if (loop != eventLoop()) {
                requestInboundDrain();
            } else if (loop.isShuttingDown()) {
                discardPendingInbound();
            } else {
                // Share the recovery wakeup, without making new reads wait for it.
                requestWriteCompletion();
            }
        }
    }

    private void drainInbound() {
        // Re-registration can move the channel after this task was queued.
        if (!eventLoop().inEventLoop()) {
            scheduleInboundDrain();
            return;
        }
        inboundDrainRejected = false;
        int messages = 0;
        try {
            fireChannelActiveIfReady();
            while (messages < MAX_MESSAGES_PER_DRAIN && canReadInbound()) {
                Object frame;
                synchronized (inboundLock) {
                    frame = pendingInbound == null ? null : pendingInbound.poll();
                    if (frame == null) {
                        break;
                    }
                    ByteBuf content = frame instanceof ByteBuf buffer ? buffer
                            : ((NetherNetUnreliableFrame) frame).content();
                    pendingInboundBytes -= content.readableBytes();
                }
                readPending = false;
                pipeline().fireChannelRead(frame);
                messages++;
                if (!config.isAutoRead()) {
                    break;
                }
            }
            if (messages != 0) {
                pipeline().fireChannelReadComplete();
            }
        } finally {
            synchronized (inboundLock) {
                inboundDrainScheduled = false;
            }
            requestInboundDrain();
        }
    }

    protected void fireChannelActiveIfReady() {
        if (!isRegistered() || !isActive()) {
            return;
        }

        // Activation state and the sampler are owned by the event loop.
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
        requestInboundDrain();
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (!(msg instanceof ByteBuf buffer)) {
            throw new UnsupportedOperationException("NetherNet writes require a ByteBuf");
        }
        PendingWrite write = new PendingWrite(this, buffer);
        write.previous = unsubmittedTail;
        if (unsubmittedTail == null) {
            unsubmittedHead = write;
        } else {
            unsubmittedTail.next = write;
        }
        unsubmittedTail = write;
        return write;
    }

    private void removeUnsubmitted(PendingWrite write) {
        if (write.previous == null && unsubmittedHead != write) {
            return;
        }
        if (flushedWriteTail == write) {
            flushedWriteTail = write.previous;
        }
        if (write.previous == null) {
            unsubmittedHead = write.next;
        } else {
            write.previous.next = write.next;
        }
        if (write.next == null) {
            unsubmittedTail = write.previous;
        } else {
            write.next.previous = write.previous;
        }
        write.previous = null;
        write.next = null;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (removingCompletedWrites) {
            // The outer drain submits any writes added by completion listeners.
            return;
        }
        removeCompletedWrites(in);
        if (!isOpen()) {
            return;
        }
        IOException failure = writeFailure.get();
        if (failure != null) {
            ((NetherNetUnsafe) unsafe()).closeWithSendFailure(failure);
            return;
        }
        // Native acceptance and a queued recovery task must not gate an explicit flush.
        while (flushedWriteTail != null && isOpen()) {
            if (engineSaturated(in)) {
                return;
            }
            PendingWrite write = unsubmittedHead;
            removeUnsubmitted(write);
            // A drain notification can beat sendFramed's return.
            engineOutstanding.addAndGet(write.content().readableBytes());
            try {
                sendFramed(write.content(), write);
                write.releaseContent();
            } catch (Throwable cause) {
                write.accept(cause);
            }
            IOException sendFailure = writeFailure.get();
            if (sendFailure != null) {
                ((NetherNetUnsafe) unsafe()).closeWithSendFailure(sendFailure);
                return;
            }
        }
    }

    /**
     * True while the engine's send buffer holds too much unsent data to
     * accept more. Leaves the remaining messages in netty's outbound
     * buffer; {@link #onEngineBytesSent}
     * resumes the flush once the buffer drains. A peer whose backlog also
     * exceeds the hard cap is closed instead.
     */
    private boolean engineSaturated(ChannelOutboundBuffer in) {
        if (engineOutstanding.get() < ENGINE_HIGH_WATER_MARK) {
            return false;
        }
        writesPaused = true;
        if (engineOutstanding.get() <= ENGINE_RESUME_LOW_WATER_MARK) {
            writesPaused = false;
            return false;
        }
        if (in.totalPendingWriteBytes() > MAX_BACKLOG_BYTES) {
            log.warn("Closing {}: peer cannot keep up ({} bytes unsent in the engine, {} bytes backlogged)",
                remoteAddress, engineOutstanding.get(), in.totalPendingWriteBytes());
            close();
        }
        return true;
    }

    /**
     * Reports a decrease in the engine's buffered amount, not peer delivery.
     * Called from engine threads via the session listener; resumes a paused
     * write path once the buffer is below the low water mark.
     */
    protected void onEngineBytesSent(long bytes) {
        if (!isOpen() || bytes <= 0) {
            return;
        }
        long outstanding = engineOutstanding.addAndGet(-bytes);
        if (outstanding < 0) {
            // Sends dropped by the engine or counter reset races; clamp.
            engineOutstanding.compareAndSet(outstanding, 0);
            outstanding = 0;
        }
        if (writesPaused && outstanding <= ENGINE_RESUME_LOW_WATER_MARK) {
            writesPaused = false;
            requestWriteCompletion();
        }
    }

    private void requestWriteCompletion() {
        if (isOpen() && writeCompletionScheduled.compareAndSet(false, true)) {
            scheduleWriteCompletion();
        }
    }

    private void scheduleWriteCompletion() {
        EventLoop loop = eventLoop();
        try {
            loop.execute(writeCompletionTask);
        } catch (RejectedExecutionException cause) {
            if (loop != eventLoop()) {
                scheduleWriteCompletion();
            } else if (!loop.isShuttingDown()) {
                // Recover even if a quiet connection produces no further events.
                // Explicit flushes can submit writes while this retry is pending.
                GlobalEventExecutor.INSTANCE.schedule(() -> {
                    if (isOpen()) {
                        scheduleWriteCompletion();
                    }
                }, 10, TimeUnit.MILLISECONDS);
            } else {
                // The shutdown hook closes on the owner thread, never here on
                // a native callback thread or after that owner has terminated.
                writeCompletionScheduled.set(false);
            }
        }
    }

    private void completeWrites() {
        if (!eventLoop().inEventLoop()) {
            scheduleWriteCompletion();
            return;
        }
        writeCompletionScheduled.set(false);
        if (!isOpen()) {
            return;
        }
        if (inboundDrainRejected) {
            requestInboundDrain();
        }
        ChannelOutboundBuffer in = unsafe().outboundBuffer();
        if (in == null) {
            return;
        }
        removeCompletedWrites(in);
        IOException failure = writeFailure.get();
        if (failure != null && isOpen()) {
            // Retrying a rejected fragment after later frames were submitted
            // could corrupt the reliable stream. Fail the connection instead.
            ((NetherNetUnsafe) unsafe()).closeWithSendFailure(failure);
        } else if (isOpen() && flushedWriteTail != null) {
            ((NetherNetUnsafe) unsafe()).flushPendingWrites();
        }
    }

    private void removeCompletedWrites(ChannelOutboundBuffer in) {
        removingCompletedWrites = true;
        try {
            Object msg;
            while (isOpen() && (msg = in.current()) != null) {
                if (msg instanceof PendingWrite write) {
                    if (write.result != PendingWrite.SUCCESS) {
                        break;
                    }
                }
                // Netty replaces cancelled entries with an empty ByteBuf.
                in.remove();
            }
        } finally {
            removingCompletedWrites = false;
        }
    }

    @Override
    protected abstract NetherNetUnsafe newUnsafe();

    protected abstract class NetherNetUnsafe extends AbstractUnsafe {
        @Override
        protected void flush0() {
            // Netty has marked the current writes uncancellable before reaching here.
            flushedWriteTail = unsubmittedTail;
            super.flush0();
        }

        private void flushPendingWrites() {
            // Bypass the explicit-flush boundary update when resuming existing work.
            super.flush0();
        }

        private void closeWithSendFailure(IOException failure) {
            ClosedChannelException closed = new ClosedChannelException();
            closed.initCause(failure);
            close(voidPromise(), failure, closed);
        }
    }

    private static final class PendingWrite extends AbstractReferenceCounted implements ByteBufHolder, Consumer<Throwable> {
        private static final Object SUCCESS = new Object();
        private static final AtomicReferenceFieldUpdater<PendingWrite, Object> RESULT =
                AtomicReferenceFieldUpdater.newUpdater(PendingWrite.class, Object.class, "result");
        private final NetherNetChannel channel;
        private ByteBuf content;
        private volatile Object result;
        private PendingWrite previous;
        private PendingWrite next;

        private PendingWrite(NetherNetChannel channel, ByteBuf content) {
            this.channel = channel;
            this.content = content;
        }

        @Override
        public ByteBuf content() {
            if (refCnt() == 0) {
                throw new IllegalReferenceCountException(0);
            }
            return content;
        }

        private void releaseContent() {
            // The binding copied the payload before returning. Only the result
            // state needs to wait in Netty's outbound buffer for the callback.
            ByteBuf buffer = content;
            content = Unpooled.EMPTY_BUFFER;
            buffer.release();
        }

        @Override
        protected void deallocate() {
            // Cancellation or a failed size estimate can release a write before submission.
            channel.removeUnsubmitted(this);
            releaseContent();
        }
        @Override public ByteBufHolder copy() { return replace(content().copy()); }
        @Override public ByteBufHolder duplicate() { return replace(content().duplicate()); }
        @Override public ByteBufHolder retainedDuplicate() { return replace(content().retainedDuplicate()); }
        @Override public ByteBufHolder replace(ByteBuf buffer) { return new DefaultByteBufHolder(buffer); }
        @Override public PendingWrite retain() { super.retain(); return this; }
        @Override public PendingWrite retain(int increment) { super.retain(increment); return this; }
        @Override public PendingWrite touch() { content().touch(); return this; }
        @Override public PendingWrite touch(Object hint) { content().touch(hint); return this; }

        @Override
        public void accept(Throwable cause) {
            if (!channel.isOpen()) {
                return;
            }
            IOException failure = cause == null ? null : cause instanceof IOException io ? io
                    : new IOException("Failed to send NetherNet message", cause);
            if (!RESULT.compareAndSet(this, null, failure == null ? SUCCESS : failure)) {
                return;
            }
            if (failure != null) {
                channel.writeFailure.compareAndSet(null, failure);
            }
            channel.requestWriteCompletion();
        }
    }

    /**
     * Ships one already framed message (header byte included, at most the
     * negotiated maximum message size) to the transport. Must not take
     * ownership of the buffer; the caller releases it. Runs on the event
     * loop. Completion reports native acceptance (null) or failure and may run
     * on another thread. It must be invoked once unless preparation throws.
     */
    protected abstract void sendFramed(ByteBuf framed, Consumer<Throwable> completion);

    /**
     * Converts a framed buffer into a NIO buffer suitable for the WebRTC
     * send path. The binding copies heap and direct windows before returning.
     */
    protected static ByteBuffer toNioBuffer(ByteBuf framed) {
        return framed.nioBuffer();
    }

    @Override
    protected void doRegister() throws Exception {
        if (eventLoop() instanceof SingleThreadEventExecutor executor) {
            executor.addShutdownHook(shutdownHook);
        }
        if (isActive()) {
            // Netty's register flow fires channelActive itself for already
            // active channels; pre set the flag so it is not fired twice, and
            // start the sampler here since fireChannelActiveIfReady's CAS
            // will never win on this path.
            channelActiveFired.set(true);
            startRttSampler();
        }
        // Handler initialization and Netty's initial channelActive run after doRegister returns.
        eventLoop().execute(() -> {
            if (!isOpen()) {
                return;
            }
            inboundReady = true;
            fireChannelActiveIfReady();
        });
    }

    @Override
    protected void doDeregister() throws Exception {
        inboundReady = false;
        if (eventLoop() instanceof SingleThreadEventExecutor executor) {
            executor.removeShutdownHook(shutdownHook);
        }
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
        while (unsubmittedHead != null) {
            removeUnsubmitted(unsubmittedHead);
        }
        inboundReady = false;
        inboundClosed = true;
        readPending = false;
        discardPendingInbound();

        if (rttSampler != null) {
            rttSampler.cancel(false);
            rttSampler = null;
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        readPending = !config.isAutoRead();
        requestInboundDrain();
    }

    /** Clears read demand when the channel configuration disables automatic reads. */
    public final void clearReadPending() {
        if (!isRegistered() || eventLoop().inEventLoop()) {
            readPending = false;
        } else {
            try {
                // Order this after any automatic read already queued by a configuration change.
                eventLoop().execute(clearReadPendingTask);
            } catch (RejectedExecutionException ignored) {
                readPending = false;
            }
        }
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
    public SocketAddress remoteAddress() {
        // ICE nomination can replace an address already observed by a handler.
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
