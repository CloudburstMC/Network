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

package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelMetrics;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Copies callback storage into Netty ownership and joins fragments without copying them again.
 * <p>
 * The reliable channel is held to its framing: it promises every message, so dropping one would leave the session
 * silently out of step. Like the retail client, a frame of a byte or less, a countdown out of step or a message
 * past {@link NetherNetChannel#MAX_RECEIVED_MESSAGE_SIZE} ends it, and nothing is delivered after. The
 * unreliable channel loses messages anyway, so it drops what it cannot deliver and resyncs.
 */
final class NetherNetMessageAssembler implements AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetMessageAssembler.class);

    private final boolean reliable;
    private CompositeByteBuf assembly;
    private int expected = -1;
    private boolean dropping;
    private boolean closed;

    NetherNetMessageAssembler(boolean reliable) {
        this.reliable = reliable;
    }

    /**
     * The returned message belongs to the caller and may outlive the native callback. Null while a
     * split message is still arriving and for anything that cannot be delivered, which the metrics
     * count apart.
     *
     * @throws NetherNetFramingException on the reliable channel, for framing it cannot continue past
     */
    synchronized ByteBuf decode(ByteBuffer data, ByteBufAllocator allocator, @Nullable NetherChannelMetrics metrics) {
        if (closed) {
            return null;
        }
        if (reliable && (data.remaining() < 2 || data.remaining() > NetherNetConstants.MAX_ADVERTISED_MESSAGE_SIZE)) {
            throw broken(metrics, TransportDisconnectReason.BAD_PACKET, "A frame of " + data.remaining() + " bytes");
        }
        if (!data.hasRemaining()) {
            log.debug("Empty message on the unreliable channel");
            failed(metrics);
            return null;
        }

        int remaining = data.get() & 0xFF;
        if (expected != -1 && remaining != expected) {
            if (reliable) {
                throw broken(metrics, TransportDisconnectReason.BAD_PACKET,
                        "Expected segment " + expected + ", got " + remaining);
            }
            failed(metrics);
            if (remaining > expected) {
                // A countdown only ever falls, so the rest of the message being assembled will
                // never arrive and this fragment opens a new one
                log.debug("Restarting assembly on the unreliable channel: expected segment {}, got {}",
                        expected, remaining);
                clear();
            } else {
                // Fragments went missing inside this message, so it can never be completed.
                // Follow its countdown out rather than assembling, so the next one starts clean
                log.debug("Dropping a gapped message on the unreliable channel: expected segment {}, got {}",
                        expected, remaining);
                clear();
                dropping = true;
            }
        }

        int assembled = assembly == null ? 0 : assembly.readableBytes();
        if (!dropping && assembled + data.remaining() > NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE) {
            if (reliable) {
                throw broken(metrics, TransportDisconnectReason.SPLIT_QUEUE_TOO_LONG,
                        "A message over " + NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE + " bytes");
            }
            log.debug("Dropping a message over {} bytes on the unreliable channel",
                    NetherNetChannel.MAX_RECEIVED_MESSAGE_SIZE);
            failed(metrics);
            clear();
            dropping = true;
        }

        if (dropping) {
            expected = remaining == 0 ? -1 : remaining - 1;
            dropping = remaining != 0;
            return null;
        }

        try {
            if (expected == -1 && remaining == 0) {
                if (data.hasRemaining()) {
                    return copy(data, allocator);
                }
                failed(metrics);
                return null;
            }

            if (data.hasRemaining()) {
                if (assembly == null) {
                    // Allow every remaining fragment, avoiding CompositeByteBuf's automatic consolidation.
                    assembly = allocator.compositeBuffer(remaining + 1);
                }
                // addComponent takes release ownership of the copied payload.
                assembly.addComponent(true, copy(data, allocator));
            }
            expected = remaining - 1;
            if (remaining != 0) {
                if (metrics != null) {
                    metrics.fragmentsIn(1);
                }
                return null;
            }

            ByteBuf message = assembly;
            assembly = null;
            if (message == null) {
                // Every fragment was a bare header
                failed(metrics);
            }
            return message;
        } catch (RuntimeException | Error e) {
            clear();
            throw e;
        }
    }

    /** Ends the reliable stream: what was assembled goes, and nothing is delivered after. */
    private NetherNetFramingException broken(@Nullable NetherChannelMetrics metrics,
                                             TransportDisconnectReason reason, String what) {
        failed(metrics);
        close();
        return new NetherNetFramingException(reason, what + " on the reliable channel");
    }

    private static void failed(@Nullable NetherChannelMetrics metrics) {
        if (metrics != null) {
            metrics.decodeFail(1);
        }
    }

    private static ByteBuf copy(ByteBuffer data, ByteBufAllocator allocator) {
        ByteBuf payload = allocator.buffer(data.remaining(), data.remaining());
        try {
            payload.writeBytes(data);
            return payload;
        } catch (RuntimeException | Error e) {
            payload.release();
            throw e;
        }
    }

    private void clear() {
        if (assembly != null) {
            assembly.release();
            assembly = null;
        }
        expected = -1;
        dropping = false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }
}
