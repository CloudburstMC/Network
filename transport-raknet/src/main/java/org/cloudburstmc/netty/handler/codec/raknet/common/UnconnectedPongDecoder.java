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

package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.AdvancedChannelInboundHandler;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_UNCONNECTED_PONG;

@Sharable
public class UnconnectedPongDecoder extends AdvancedChannelInboundHandler<DatagramPacket> {
    public static final UnconnectedPongDecoder INSTANCE = new UnconnectedPongDecoder();
    public static final String NAME = "rak-unconnected-pong-deencoder";

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        return buf.isReadable() && buf.getUnsignedByte(buf.readerIndex()) == ID_UNCONNECTED_PONG;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        buf.readUnsignedByte(); // Packet ID

        long pingTime = buf.readLong();
        long guid = buf.readLong();

        ByteBuf magicBuf = ctx.channel().config().getOption(RakChannelOption.RAK_UNCONNECTED_MAGIC);
        if (!buf.isReadable(magicBuf.readableBytes()) || !ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf)) {
            // Magic does not match
            return;
        }

        byte[] pongData = null;
        if (buf.isReadable(2)) { // Length
            pongData = new byte[buf.readUnsignedShort()];
            buf.readBytes(pongData);
        }

        long pongTime = System.currentTimeMillis();
        ctx.fireChannelRead(new RakPong(pingTime, pongTime, guid, pongData, packet.sender()));
    }
}
