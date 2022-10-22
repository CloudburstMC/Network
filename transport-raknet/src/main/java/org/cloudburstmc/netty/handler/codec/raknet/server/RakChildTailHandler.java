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

package org.cloudburstmc.netty.handler.codec.raknet.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;

public class RakChildTailHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "rak-child-tail-handler";

    private final RakChildChannel channel;

    public RakChildTailHandler(RakChildChannel channel) {
        this.channel = channel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final Object message = msg instanceof EncapsulatedPacket ? ((EncapsulatedPacket) msg).toMessage() : ReferenceCountUtil.retain(msg);
        if (this.channel.eventLoop().inEventLoop()) {
            this.channel.pipeline().fireChannelRead(message).fireChannelReadComplete();
        } else {
            this.channel.eventLoop().execute(() -> this.channel.pipeline().fireChannelRead(message).fireChannelReadComplete());
        }
    }
}
