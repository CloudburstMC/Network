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

package org.cloudburstmc.netty.signaling.admission;

import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.concurrent.ScheduledFuture;
import tel.schich.libdatachannel.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Native admission child with bounded queues and both NetherNet channel semantics.
 */
public final class AdmittedNetherNetChildChannel extends NetherNetChildChannel {
    public static final int WRITE_LIMIT = 1 << 20;
    public static final int NATIVE_WRITE_LIMIT = 1 << 19;
    public static final int INBOUND_FRAMES = 128;
    /** Bounds queued frames by size too, at what {@link #INBOUND_FRAMES} frames of {@code FRAME_LIMIT} bytes hold. */
    public static final int INBOUND_BYTES = INBOUND_FRAMES * NetherNetFrameDecoder.FRAME_LIMIT;

    private record Incoming(ByteBuf bytes, boolean reliable) {
    }

    private final ArrayBlockingQueue<Incoming> incoming = new ArrayBlockingQueue<>(INBOUND_FRAMES);
    private final AtomicInteger incomingBytes = new AtomicInteger();
    private final NetherNetFrameDecoder decoder = new NetherNetFrameDecoder();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
    private final Consumer<PeerConnection> nativeCloser;
    private ScheduledFuture<?> tick;
    private volatile boolean installed;
    private boolean activated, readDemand;

    public AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress remote,
                                         InetSocketAddress local) {
        this(parent, peer, remote, local, AdmittedNetherNetChildChannel::closeNativePeer);
    }

    AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress remote,
                                  InetSocketAddress local, Consumer<PeerConnection> nativeCloser) {
        super(parent, peer, remote, local);
        this.nativeCloser = nativeCloser;
        config().setWriteBufferWaterMark(new WriteBufferWaterMark(WRITE_LIMIT / 4, WRITE_LIMIT / 2));
    }

    @Override
    protected void doRegister() {
        tick = eventLoop().scheduleWithFixedDelay(this::pump, 0, 5, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void setDataChannels(DataChannel reliable, DataChannel unreliable) {
        acceptDataChannel(reliable);
        acceptDataChannel(unreliable);
    }

    /**
     * Install immediately on the inline JNI callback; never retain an unobserved receive queue.
     */
    public synchronized void acceptDataChannel(DataChannel dc) {
        if (!isOpen()) {
            throw new IllegalStateException("Child closed");
        }

        String label = dc.label();
        if (label.equals("ReliableDataChannel") && reliableChannel == null) {
            checkSemantics(dc, true);
            listen(dc, true);
            reliableChannel = dc;
        } else if (label.equals("UnreliableDataChannel") && unreliableChannel == null) {
            checkSemantics(dc, false);
            listen(dc, false);
            unreliableChannel = dc;
        } else {
            throw new IllegalArgumentException("Unexpected or duplicate NetherNet channel");
        }

        installed = reliableChannel != null && unreliableChannel != null;
    }

    private static void checkSemantics(DataChannel channel, boolean reliable) {
        DataChannelReliability r = channel.reliability();
        if (r.isUnordered() == reliable || r.isUnreliable() == reliable ||
                (!reliable && (r.maxRetransmits() != 0 || !r.maxPacketLifeTime().isZero()))) {
            throw new IllegalArgumentException("Incorrect NetherNet channel reliability");
        }
    }

    private void listen(DataChannel dc, boolean reliable) {
        // Peers use an INLINE JNI executor. Copy before native callback storage expires.
        dc.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
            if (!isOpen()) {
                return;
            }
            int size = bytes.remaining();
            if (size < 2 || size > NetherNetFrameDecoder.MESSAGE_LIMIT) {
                failed.set(true);
                return;
            }
            // Past the bound the channel closes, so the count need not be undone
            if (incomingBytes.addAndGet(size) > INBOUND_BYTES) {
                failed.set(true);
                return;
            }
            ByteBuf copy = alloc().buffer(size);
            copy.writeBytes(bytes);
            if (!incoming.offer(new Incoming(copy, reliable))) {
                copy.release();
                failed.set(true);
            }
        }));
        dc.onClosed.register(channel -> failed.set(true));
        dc.onError.register((channel, message) -> failed.set(true));
        dc.bufferedAmountLowThreshold(NATIVE_WRITE_LIMIT / 2);
    }

    private void pump() {
        if (!isOpen()) {
            return;
        }

        if (failed.get()) {
            close();
            return;
        }

        try {
            if (isActive() && !activated) {
                var selected = peerConnection.remoteAddress();
                setRemoteAddress(
                        new InetSocketAddress(
                                org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(
                                        selected.getHostString()),
                                selected.getPort()));
                activated = true;
                pipeline().fireChannelActive();
            }

            if (config().isAutoRead() || readDemand) {
                readDemand = false;
                boolean read = false;
                for (int count = 0; count < INBOUND_FRAMES; count++) {
                    Incoming frame = incoming.poll();
                    if (frame == null) {
                        break;
                    }
                    incomingBytes.addAndGet(-frame.bytes().readableBytes());

                    ByteBuf message = decoder.decode(frame.bytes(), frame.reliable());
                    if (message != null) {
                        pipeline().fireUserEventTriggered(new NetherNetPacket.Delivery(frame.reliable()));
                        pipeline().fireChannelRead(message);
                        read = true;
                    }
                }

                if (read) {
                    pipeline().fireChannelReadComplete();
                }
            }

            if (isActive()) {
                ChannelOutboundBuffer out = unsafe().outboundBuffer();
                if (out != null) {
                    out.setUserDefinedWritability(1, reliableChannel.bufferedAmount() < NATIVE_WRITE_LIMIT / 2
                            && unreliableChannel.bufferedAmount() < NATIVE_WRITE_LIMIT / 2);
                    unsafe().flush();
                }
            }
        } catch (Exception e) {
            pipeline().fireExceptionCaught(e);
            close();
        }
    }

    @Override
    protected Object filterOutboundMessage(Object message) {
        ByteBuf payload = payload(message);
        boolean reliable = !(message instanceof NetherNetPacket p) || p.reliable();
        int size = payload.readableBytes();
        if (size < 1 || size > (reliable ? NetherNetFrameDecoder.MESSAGE_LIMIT :
                NetherNetFrameDecoder.FRAME_LIMIT - 1)) {
            throw new IllegalArgumentException("NetherNet message exceeds channel framing limit");
        }

        ChannelOutboundBuffer out = unsafe().outboundBuffer();
        if (out == null || out.totalPendingWriteBytes() + size + 128 > WRITE_LIMIT) {
            throw new IllegalStateException("NetherNet outbound queue full");
        }

        return message;
    }

    private static ByteBuf payload(Object message) {
        if (message instanceof ByteBuf buf) {
            return buf;
        }

        if (message instanceof NetherNetPacket packet) {
            return packet.content();
        }

        throw new IllegalArgumentException("Expected ByteBuf or NetherNetPacket");
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer out) {
        if (!isActive()) {
            return; // Netty retains ownership and promises; no private unbounded queue
        }

        while (out.current() != null) {
            Object message = out.current();
            ByteBuf payload = payload(message);
            boolean reliable = !(message instanceof NetherNetPacket packet) || packet.reliable();
            DataChannel dc = reliable ? reliableChannel : unreliableChannel;
            int length = payload.readableBytes();
            // Unordered traffic is never fragmented, so it always goes out as one frame
            int maxPayload = reliable ? maxSegmentPayload() : length;
            int chunks;
            try {
                // Checked before the first frame goes out, so the peer is never left inside a message
                chunks = NetherNetConstants.segmentCount(length, maxPayload);
            } catch (IllegalArgumentException refused) {
                out.remove(refused);
                continue;
            }
            if (dc.bufferedAmount() + length + chunks > NATIVE_WRITE_LIMIT) {
                out.setUserDefinedWritability(1, false);
                return;
            }

            try {
                for (int i = 0, offset = payload.readerIndex(); i < chunks; i++) {
                    int count = Math.min(maxPayload, length - i * maxPayload);
                    ByteBuffer frame = ByteBuffer.allocateDirect(count + 1);
                    frame.put((byte) (chunks - i - 1));
                    payload.getBytes(offset, frame);
                    frame.flip();
                    dc.sendMessage(frame);
                    offset += count;
                }
                out.remove();
            } catch (Exception failure) {
                out.remove(failure);
                close();
                return;
            }
        }
    }

    @Override
    protected void doBeginRead() {
        readDemand = true;
    }

    @Override
    public boolean isActive() {
        DataChannel reliable = reliableChannel, unreliable = unreliableChannel;
        return open && installed && reliable != null && unreliable != null && reliable.isOpen() && unreliable.isOpen();
    }

    @Override
    protected void doClose() {
        PeerConnection peer;
        synchronized (this) {
            open = false;
            installed = false;
            peer = peerConnection;
            peerConnection = null;
            reliableChannel = null;
            unreliableChannel = null;
        }
        if (tick != null) {
            tick.cancel(false);
            tick = null;
        }
        // Native close waits for callbacks. Never hold the monitor used by acceptDataChannel here.
        try {
            nativeCloser.accept(peer);
        } catch (RuntimeException | Error failure) {
            nativeTermination.completeExceptionally(failure);
            throw failure;
        } finally {
            this.discardQueuedFrames();
            decoder.clear();
        }
        nativeTermination.complete(null);
    }

    private static void closeNativePeer(PeerConnection peer) {
        if (peer != null && !peer.closeAndAwait(Duration.ofSeconds(5))) {
            peer.close();
            throw new IllegalStateException("Native transport teardown did not complete within its deadline");
        }
    }

    public CompletionStage<Void> nativeTermination() {
        return nativeTermination;
    }

    void closeUnregistered() {
        doClose();
    }

    private void discardQueuedFrames() {
        Incoming frame = incoming.poll();
        while (frame != null) {
            incomingBytes.addAndGet(-frame.bytes().readableBytes());
            frame.bytes().release();
            frame = incoming.poll();
        }
    }

    public int queuedFrames() {
        return incoming.size();
    }

    public int retainedAssemblyBytes() {
        return decoder.retainedBytes();
    }
}
