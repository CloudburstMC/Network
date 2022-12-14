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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.Inet6Address;
import java.net.InetSocketAddress;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

@Sharable
public class RakServerOnlineInitialHandler extends SimpleChannelInboundHandler<EncapsulatedPacket> {

    public static final String NAME = "rak-server-online-initial-handler";

    private final RakChildChannel channel;

    public RakServerOnlineInitialHandler(RakChildChannel channel) {
        this.channel = channel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EncapsulatedPacket message) throws Exception {
        ByteBuf buf = message.getBuffer();
        int packetId = buf.getUnsignedByte(buf.readerIndex());

        switch (packetId) {
            case ID_CONNECTION_REQUEST:
                this.onConnectionRequest(ctx, buf);
                break;
            case ID_NEW_INCOMING_CONNECTION:
                buf.skipBytes(1);
                // We have connected and no longer need this handler
                ctx.pipeline().remove(this);
                channel.setActive(true);
                channel.pipeline().fireChannelActive();
                break;
            default:
                ctx.fireChannelRead(message.retain());
                break;
        }
    }

    private void onConnectionRequest(ChannelHandlerContext ctx, ByteBuf buffer) {
        buffer.skipBytes(1);
        long guid = ((RakChannelConfig) this.channel.config()).getGuid();
        long serverGuid = buffer.readLong();
        long timestamp = buffer.readLong();
        boolean security = buffer.readBoolean();

        if (serverGuid != guid || security) {
            this.sendConnectionRequestFailed(ctx, guid);
        } else {
            this.sendConnectionRequestAccepted(ctx, timestamp);
        }
    }

    private void sendConnectionRequestAccepted(ChannelHandlerContext ctx, long time) {
        InetSocketAddress address = ((InetSocketAddress) this.channel.remoteAddress());
        boolean ipv6 = address.getAddress() instanceof Inet6Address;
        ByteBuf outBuf = ctx.alloc().ioBuffer(ipv6 ? 628 : 166);

        outBuf.writeByte(ID_CONNECTION_REQUEST_ACCEPTED);
        RakUtils.writeAddress(outBuf, address);
        outBuf.writeShort(0); // System index
        for (InetSocketAddress socketAddress : ipv6 ? LOCAL_IP_ADDRESSES_V6 : LOCAL_IP_ADDRESSES_V4) {
            RakUtils.writeAddress(outBuf, socketAddress);
        }
        outBuf.writeLong(time);
        outBuf.writeLong(System.currentTimeMillis());

        ctx.writeAndFlush(new RakMessage(outBuf, RakReliability.RELIABLE, RakPriority.IMMEDIATE));
    }

    private void sendConnectionRequestFailed(ChannelHandlerContext ctx, long guid) {
        ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
        int length = 9 + magicBuf.readableBytes();

        ByteBuf reply = ctx.alloc().ioBuffer(length);
        reply.writeByte(ID_CONNECTION_REQUEST_FAILED);
        reply.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        reply.writeLong(guid);

        sendRaw(ctx, reply);
        ctx.fireUserEventTriggered(RakDisconnectReason.CONNECTION_REQUEST_FAILED).close();
    }

    private void sendRaw(ChannelHandlerContext ctx, ByteBuf buf) {
        ctx.pipeline().context(RakSessionCodec.NAME).writeAndFlush(buf);
    }
}
