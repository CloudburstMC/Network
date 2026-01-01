/*
 * Copyright 2025 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.handler.codec.raknet.cookieservice;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.AdvancedChannelInboundHandler;
import org.cloudburstmc.netty.util.RakUtils;
import org.cloudburstmc.netty.util.SipHash;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakServerCookieHandler extends AdvancedChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-cookie-handler";

    public RakServerCookieHandler() {
    }

    @Override
    protected boolean acceptInboundMessage(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!super.acceptInboundMessage(ctx, msg)) {
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        if (!buf.isReadable()) {
            return false;
        }

        int startIndex = buf.readerIndex();
        try {
            int packetId = buf.readUnsignedByte();
            if (packetId == ID_OPEN_CONNECTION_REQUEST_1) {
                ByteBuf magicBuf = ((RakServerChannelConfig) ctx.channel().config()).getUnconnectedMagic();
                return buf.isReadable(magicBuf.readableBytes()) && ByteBufUtil.equals(buf.readSlice(magicBuf.readableBytes()), magicBuf);
            }
            return false;
        } finally {
            buf.readerIndex(startIndex);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buf = packet.content();
        buf.skipBytes(1); // Packet ID

        RakServerChannelConfig config = (RakServerChannelConfig) ctx.channel().config();
        ByteBuf magicBuf = config.getUnconnectedMagic();
        long guid = config.getGuid();

        // Magic is already verified in acceptInboundMessage, just skip it
        buf.skipBytes(magicBuf.readableBytes());
        
        int protocolVersion = buf.readUnsignedByte();
        InetSocketAddress sender = packet.sender();
        
        // MTU Calculation: 1 (ID) + Magic + 1 (Protocol) + IP Header (20/40) + UDP Header (8)
        int mtu = buf.readableBytes() + 1 + magicBuf.readableBytes() + 1 + (sender.getAddress() instanceof Inet6Address ? 40 : 20) + UDP_HEADER_SIZE;

        int[] supportedProtocols = config.getSupportedProtocols();
        if (supportedProtocols != null && Arrays.binarySearch(supportedProtocols, protocolVersion) < 0) {
            int latestVersion = supportedProtocols[supportedProtocols.length - 1];
            this.sendIncompatibleVersion(ctx, packet, latestVersion, magicBuf, guid);
            return;
        }

        // Generate Stateless Cookie
        SipHash sipHash = config.getSipHash();
        int cookie = sipHash.generateStatelessCookie(sender);

        ByteBuf replyBuffer = ctx.alloc().ioBuffer(32);
        replyBuffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        replyBuffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        replyBuffer.writeLong(guid);
        replyBuffer.writeBoolean(true); // Security (Always true for cookie service)
        replyBuffer.writeInt(cookie);
        replyBuffer.writeShort(RakUtils.clamp(mtu, config.getMinMtu(), config.getMaxMtu()));

        ctx.writeAndFlush(RakUtils.datagramReply(replyBuffer, packet));
    }

    private void sendIncompatibleVersion(ChannelHandlerContext ctx, DatagramPacket request, int protocolVersion, ByteBuf magicBuf, long guid) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(protocolVersion);
        buffer.writeBytes(magicBuf, magicBuf.readerIndex(), magicBuf.readableBytes());
        buffer.writeLong(guid);
        ctx.writeAndFlush(RakUtils.datagramReply(buffer, request));
    }
}
