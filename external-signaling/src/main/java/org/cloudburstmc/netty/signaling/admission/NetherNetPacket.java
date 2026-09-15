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
import io.netty.buffer.DefaultByteBufHolder;

/**
 * Explicit outbound channel selection. Plain ByteBuf writes use the reliable channel.
 */
public final class NetherNetPacket extends DefaultByteBufHolder {
    private final boolean reliable;

    public NetherNetPacket(ByteBuf content, boolean reliable) {
        super(content);
        this.reliable = reliable;
    }

    public boolean reliable() {
        return reliable;
    }

    @Override
    public NetherNetPacket replace(ByteBuf content) {
        return new NetherNetPacket(content, reliable);
    }

    /**
     * Fired immediately before the corresponding inbound ByteBuf, on the same event loop.
     */
    public record Delivery(boolean reliable) {
    }
}
