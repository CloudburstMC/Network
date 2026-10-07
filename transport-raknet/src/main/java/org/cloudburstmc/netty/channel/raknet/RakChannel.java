/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ChannelPipeline;
import org.cloudburstmc.netty.channel.TransportChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;

public interface RakChannel extends TransportChannel {

    ChannelPipeline rakPipeline();

    @Override
    RakChannelConfig config();

    @Override
    default int maxMessageSize() {
        RakSessionCodec session = this.rakPipeline().get(RakSessionCodec.class);
        // No session before the handshake or after close, so assume the smallest MTU, over IPv6
        return session != null ? session.getMaxMessageSize()
                : RakSessionCodec.maxMessageSize(RakConstants.MINIMUM_MTU_SIZE - RakConstants.UDP_HEADER_SIZE - 40);
    }
}
