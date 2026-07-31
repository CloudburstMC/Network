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

    protected volatile boolean open = true;

    protected NetherNetChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent);
        this.remoteAddress = remote;
        this.localAddress = local;
    }

    public void setDataChannels(RTCDataChannel reliable, RTCDataChannel unreliable) {
        this.reliableChannel = reliable;
        this.unreliableChannel = unreliable;

        RTCDataChannelObserver observer = new RTCDataChannelObserver() {
            private final ByteBuf assemblyBuf = config.getAllocator().buffer();
            private int currentSegmentCount = -1;

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
                            assemblyBuf.skipBytes(assemblyBuf.readableBytes());

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
        };

        this.reliableChannel.registerObserver(observer);

        if (reliableChannel.getState() == RTCDataChannelState.OPEN) {
            eventLoop().execute(this::onDataChannelStateChange);
        }
    }

    private void onDataChannelStateChange() {
        if (isActive()) {
            if (!pendingWrites.isEmpty()) {
                pipeline().fireChannelWritabilityChanged();
                unsafe().flush();
            }
        } else if (reliableChannel.getState() == RTCDataChannelState.CLOSED) {
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
        if (!(msg instanceof ByteBuf))
            return;

        ByteBuf payload = (ByteBuf) msg;

        ByteBuf framed = payload.retainedDuplicate();

        int totalLength = framed.readableBytes();
        int maxPayload = NetherNetConstants.MAX_SCTP_MESSAGE_SIZE - 1;

        int segments = (totalLength / maxPayload);
        if (totalLength % maxPayload != 0)
            segments++;

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

    public void setRemoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }
}