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

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hands out direct buffers and keeps them, so a test can prove every one was released. */
final class TrackingAllocator extends AbstractByteBufAllocator {
    final List<ByteBuf> buffers = new ArrayList<>();
    boolean fail;

    TrackingAllocator() {
        super(true);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        throw new AssertionError("Expected direct allocation");
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        if (fail) {
            throw new IllegalStateException("Allocation failed");
        }
        ByteBuf buffer = Unpooled.directBuffer(initialCapacity, maxCapacity);
        buffers.add(buffer);
        return buffer;
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    void assertReleased() {
        for (ByteBuf buffer : buffers) {
            assertEquals(0, buffer.refCnt(), "payload allocation leaked");
        }
    }
}
