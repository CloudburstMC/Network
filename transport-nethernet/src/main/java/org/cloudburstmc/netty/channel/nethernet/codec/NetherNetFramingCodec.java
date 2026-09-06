package org.cloudburstmc.netty.channel.nethernet.codec;

import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.NetherNetUnreliableFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * NetherNet countdown framing as a standalone duplex pipeline codec.
 *
 * Every data channel message carries a one byte header: the number of
 * fragments still to come after this one. A complete single message is
 * header 0 plus payload; a fragmented message counts down to a final
 * fragment with header 0, at which point the accumulated payload is one
 * complete Bedrock batch. Unreliable inbound frames must have header 0 and
 * bypass reliable reassembly. Decoded messages from either channel are byte
 * buffers; unreliable messages can arrive while a reliable message is incomplete.
 *
 * Inbound reassembly accumulates retained slices in a composite buffer, so
 * reassembly itself copies nothing. Outbound fragments at the maximum
 * message size the remote peer advertised (a=max-message-size), read live
 * from the {@link NetherNetChannel} so a value negotiated after pipeline
 * construction is honored; a fixed size constructor exists for tests and
 * non NetherNet channels.
 */
public class NetherNetFramingCodec extends ChannelDuplexHandler {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetFramingCodec.class);

    public static final String NAME = "nethernet-framing";

    /**
     * A message can address at most 256 fragments through its one byte
     * countdown header.
     */
    public static final int MAX_FRAGMENT_COUNT = 256;

    /**
     * Upper bound on a reassembled message. Far above anything a Bedrock
     * batch produces; converts a malicious or corrupted fragment stream into
     * a dropped message instead of unbounded memory growth.
     */
    public static final int MAX_REASSEMBLED_SIZE = 16 * 1024 * 1024;

    private final int fixedMaxMessageSize;

    private CompositeByteBuf assembly;
    private int expectedCountdown = -1;
    private boolean receivedFrame;
    private boolean deliveredMessage;

    /**
     * Creates a codec that reads the negotiated max message size from the
     * channel it is installed on.
     */
    public NetherNetFramingCodec() {
        this(0);
    }

    /**
     * Creates a codec with a fixed max message size, for tests or channels
     * that are not {@link NetherNetChannel}s.
     *
     * @param fixedMaxMessageSize the max message size in bytes, or 0 to read
     *                            it from the channel
     */
    public NetherNetFramingCodec(int fixedMaxMessageSize) {
        if (fixedMaxMessageSize < 0 || fixedMaxMessageSize == 1) {
            throw new IllegalArgumentException("fixedMaxMessageSize must be 0 or at least 2");
        }
        this.fixedMaxMessageSize = fixedMaxMessageSize;
    }

    private int maxMessageSize(ChannelHandlerContext ctx) {
        if (fixedMaxMessageSize > 0) {
            return fixedMaxMessageSize;
        }
        if (ctx.channel() instanceof NetherNetChannel) {
            return ((NetherNetChannel) ctx.channel()).getMaxOutboundMessageSize();
        }
        return NetherNetConstants.MAX_SCTP_MESSAGE_SIZE;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean unreliable = msg instanceof NetherNetUnreliableFrame;
        if (!unreliable && !(msg instanceof ByteBuf)) {
            deliveredMessage = true;
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf buf = unreliable ? ((NetherNetUnreliableFrame) msg).content() : (ByteBuf) msg;
        receivedFrame = true;
        try {
            if (!buf.isReadable()) {
                return;
            }

            int header = buf.readUnsignedByte();
            if (unreliable) {
                // An unreliable message must never complete or reset a reliable assembly.
                if (header == 0 && buf.isReadable() && buf.readableBytes() <= MAX_REASSEMBLED_SIZE) {
                    deliveredMessage = true;
                    ctx.fireChannelRead(buf.readRetainedSlice(buf.readableBytes()));
                }
                return;
            }
            if (buf.readableBytes() > MAX_REASSEMBLED_SIZE) {
                log.warn("Inbound message exceeds {} bytes, dropping", MAX_REASSEMBLED_SIZE);
                resetAssembly();
                return;
            }

            if (header == 0) {
                if (assembly != null) {
                    if (expectedCountdown != 0) {
                        log.warn("Fragment out of order: expected countdown {}, got 0", expectedCountdown);
                        resetAssembly();
                        return;
                    }
                    if (assembly.readableBytes() > MAX_REASSEMBLED_SIZE - buf.readableBytes()) {
                        log.warn("Reassembled message exceeds {} bytes, dropping", MAX_REASSEMBLED_SIZE);
                        resetAssembly();
                        return;
                    }
                    if (buf.isReadable()) {
                        assembly.addComponent(true, buf.readRetainedSlice(buf.readableBytes()));
                    }
                    ByteBuf complete = assembly;
                    assembly = null;
                    expectedCountdown = -1;
                    deliveredMessage = true;
                    ctx.fireChannelRead(complete);
                } else if (buf.isReadable()) {
                    // Complete single message, the common case.
                    deliveredMessage = true;
                    ctx.fireChannelRead(buf.readRetainedSlice(buf.readableBytes()));
                }
            } else {
                if (assembly == null) {
                    assembly = ctx.alloc().compositeBuffer(MAX_FRAGMENT_COUNT);
                } else if (header != expectedCountdown) {
                    log.warn("Fragment out of order: expected countdown {}, got {}", expectedCountdown, header);
                    resetAssembly();
                    return;
                }
                expectedCountdown = header - 1;

                if (assembly.readableBytes() > MAX_REASSEMBLED_SIZE - buf.readableBytes()) {
                    log.warn("Reassembled message exceeds {} bytes, dropping", MAX_REASSEMBLED_SIZE);
                    resetAssembly();
                    return;
                }
                if (buf.isReadable()) {
                    assembly.addComponent(true, buf.readRetainedSlice(buf.readableBytes()));
                }
            }
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        boolean needsAnotherFrame = receivedFrame && !deliveredMessage;
        receivedFrame = false;
        deliveredMessage = false;
        // One application read must be able to finish a fragmented message.
        if (needsAnotherFrame && !ctx.channel().config().isAutoRead() && ctx.channel().isActive()) {
            ctx.read();
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }
        ByteBuf payload = (ByteBuf) msg;
        try {
            int maxPayload = maxMessageSize(ctx) - 1;
            int total = payload.readableBytes();

            if (total <= maxPayload) {
                ByteBuf framed = ctx.alloc().directBuffer(1 + total);
                framed.writeByte(0);
                framed.writeBytes(payload);
                ctx.write(framed, promise);
                return;
            }

            int fragments = 1 + (total - 1) / maxPayload;
            if (fragments > MAX_FRAGMENT_COUNT) {
                // Effectively unreachable with a sane negotiated size; fail
                // loudly instead of wrapping the header byte and corrupting
                // the stream.
                String reason = "Outbound message of " + total + " bytes requires " + fragments
                        + " fragments (max " + MAX_FRAGMENT_COUNT + ")";
                log.error(reason);
                promise.setFailure(new IllegalArgumentException(reason));
                return;
            }

            PromiseCombiner combiner = promise.isVoid() ? null : new PromiseCombiner(ctx.executor());
            int countdown = fragments - 1;
            for (int i = 0; i < fragments; i++) {
                int chunkSize = Math.min(maxPayload, payload.readableBytes());
                ByteBuf framed = ctx.alloc().directBuffer(1 + chunkSize);
                framed.writeByte(countdown);
                framed.writeBytes(payload, chunkSize);
                countdown--;

                ChannelPromise fragmentPromise = combiner == null ? ctx.voidPromise() : ctx.newPromise();
                if (combiner != null) {
                    combiner.add((Future<?>) fragmentPromise);
                }
                ctx.write(framed, fragmentPromise);
            }
            if (combiner != null) {
                combiner.finish(promise);
            }
        } finally {
            payload.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        resetAssembly();
        receivedFrame = false;
        deliveredMessage = false;
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        resetAssembly();
        super.handlerRemoved(ctx);
    }

    private void resetAssembly() {
        if (assembly != null) {
            assembly.release();
            assembly = null;
        }
        expectedCountdown = -1;
    }
}
