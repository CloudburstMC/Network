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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;

/**
 * Bounded countdown framing. Unordered traffic must fit one SCTP message.
 */
public final class NetherNetFrameDecoder {
    public static final int MESSAGE_LIMIT = 262144;
    private CompositeByteBuf assembly;
    private int expected = -1;

    /**
     * Consumes the frame and returns a completed message, or {@code null} while one is still assembling. The
     * returned buffer belongs to the caller.
     */
    public ByteBuf decode(ByteBuf frame, boolean reliable) {
        try {
            return assemble(frame, reliable);
        } catch (RuntimeException | Error e) {
            clear();
            throw e;
        } finally {
            frame.release();
        }
    }

    private ByteBuf assemble(ByteBuf frame, boolean reliable) {
        int length = frame.readableBytes();
        // The peer may send one frame as large as the message size this side advertises
        if (length < 2 || length > MESSAGE_LIMIT) {
            throw new IllegalArgumentException("Invalid NetherNet frame length");
        }

        int start = frame.readerIndex();
        int remaining = frame.getUnsignedByte(start);
        int payload = length - 1;

        // Countdown alone cannot disambiguate interleaved/reordered fragmented messages.
        if (!reliable) {
            if (remaining != 0) {
                throw new IllegalArgumentException("Fragmented unordered NetherNet message is unsupported");
            }

            return frame.retainedSlice(start + 1, payload);
        }

        int size = this.assembly == null ? 0 : this.assembly.readableBytes();
        if ((this.expected != -1 && this.expected != remaining) || size + payload > MESSAGE_LIMIT) {
            this.clear();
            throw new IllegalArgumentException("Invalid NetherNet fragment sequence");
        }

        if (this.expected == -1 && remaining == 0) {
            return frame.retainedSlice(start + 1, payload);
        }

        if (this.assembly == null) {
            // Every frame already belongs to Netty. Retain its payload instead of copying it.
            // The countdown bounds components and prevents automatic consolidation.
            this.assembly = frame.alloc().compositeBuffer(remaining + 1);
        }

        this.assembly.addComponent(true, frame.retainedSlice(start + 1, payload));
        this.expected = remaining - 1;
        if (remaining != 0) {
            return null;
        }

        ByteBuf message = this.assembly;
        this.assembly = null;
        this.clear();

        return message;
    }

    public void clear() {
        if (this.assembly != null) {
            this.assembly.release();
            this.assembly = null;
        }

        this.expected = -1;
    }

    public int retainedBytes() {
        return this.assembly == null ? 0 : this.assembly.capacity();
    }
}
