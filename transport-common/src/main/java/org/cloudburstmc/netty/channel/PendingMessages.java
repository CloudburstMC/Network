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

package org.cloudburstmc.netty.channel;

import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.ToIntFunction;

/**
 * Messages read before a channel is active, held until it is. Holds a limited number of messages and bytes. Not
 * thread safe.
 *
 * @param <T> The message type
 */
public final class PendingMessages<T extends ReferenceCounted> {
    private final Queue<T> messages = new ArrayDeque<>();
    private final int maxMessages;
    private final int maxBytes;
    private final ToIntFunction<? super T> size;
    private int bytes;

    /**
     * @param maxMessages Most messages held at once
     * @param maxBytes    Most bytes held at once
     * @param size        How many bytes a message counts for, the same for as long as it is held
     */
    public PendingMessages(int maxMessages, int maxBytes, ToIntFunction<? super T> size) {
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
        this.size = size;
    }

    /**
     * Holds a message, taking ownership of it.
     *
     * @param message The message to hold
     * @return Whether it is held. If not, it would pass a limit, and it is released instead.
     */
    public boolean offer(T message) {
        int length = this.size.applyAsInt(message);
        if (this.messages.size() >= this.maxMessages || length > this.maxBytes - this.bytes) {
            ReferenceCountUtil.release(message);
            return false;
        }
        this.messages.add(message);
        this.bytes += length;
        return true;
    }

    /**
     * @return The oldest message, now owned by the caller, or null if none is held
     */
    public T poll() {
        T message = this.messages.poll();
        if (message != null) {
            this.bytes -= this.size.applyAsInt(message);
        }
        return message;
    }

    /**
     * Releases every message held.
     */
    public void clear() {
        T message;
        while ((message = this.messages.poll()) != null) {
            ReferenceCountUtil.release(message);
        }
        this.bytes = 0;
    }
}