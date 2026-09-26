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

import java.nio.ByteBuffer;

/** Copies callback storage into Netty ownership and joins fragments without copying them again. */
final class NetherNetMessageAssembler implements AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetMessageAssembler.class);

    private final String label;
    private final int maxAssembledSize;
    private CompositeByteBuf assembly;
    private int expected = -1;
    private boolean dropping;
    private boolean closed;

    NetherNetMessageAssembler(String label) {
        this(label, NetherNetConstants.MAX_ASSEMBLED_MESSAGE_SIZE);
    }

    NetherNetMessageAssembler(String label, int maxAssembledSize) {
        this.label = label;
        this.maxAssembledSize = maxAssembledSize;
    }

    /** The returned message belongs to the caller and may outlive the native callback. */
    synchronized ByteBuf decode(ByteBuffer data, ByteBufAllocator allocator) {
        if (closed) {
            return null;
        }
        if (!data.hasRemaining()) {
            log.debug("Empty message on the {} channel", label);
            return null;
        }

        int remaining = data.get() & 0xFF;
        if (expected != -1 && remaining != expected) {
            if (remaining > expected) {
                // A countdown only ever falls, so the rest of the message being assembled will
                // never arrive and this fragment opens a new one
                log.debug("Restarting assembly on the {} channel: expected segment {}, got {}",
                        label, expected, remaining);
                clear();
            } else {
                // Fragments went missing inside this message, so it can never be completed.
                // Follow its countdown out rather than assembling, so the next one starts clean
                log.debug("Dropping a gapped message on the {} channel: expected segment {}, got {}",
                        label, expected, remaining);
                clear();
                dropping = true;
            }
        }

        if (dropping) {
            expected = remaining == 0 ? -1 : remaining - 1;
            dropping = remaining != 0;
            return null;
        }

        try {
            if (expected == -1 && remaining == 0) {
                return data.hasRemaining() ? copy(data, allocator) : null;
            }

            if (data.hasRemaining()) {
                int assembled = assembly == null ? 0 : assembly.readableBytes();
                if (data.remaining() > maxAssembledSize - assembled) {
                    log.debug("Dropping a message over {} bytes on the {} channel", maxAssembledSize, label);
                    clear();
                    // Follow the rest of its countdown out, as for a gap, so the next message starts clean
                    expected = remaining == 0 ? -1 : remaining - 1;
                    dropping = remaining != 0;
                    return null;
                }
                if (assembly == null) {
                    // Allow every remaining fragment, avoiding CompositeByteBuf's automatic consolidation.
                    assembly = allocator.compositeBuffer(remaining + 1);
                }
                // addComponent takes release ownership of the copied payload.
                assembly.addComponent(true, copy(data, allocator));
            }
            expected = remaining - 1;
            if (remaining != 0) {
                return null;
            }

            ByteBuf message = assembly;
            assembly = null;
            return message;
        } catch (RuntimeException | Error e) {
            clear();
            throw e;
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
