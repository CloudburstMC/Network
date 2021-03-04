package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.cloudburstmc.netty.channel.raknet.packet.AcknowledgedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakCodecPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import java.util.List;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class RakDatagramCodec extends ByteToMessageCodec<RakCodecPacket> {
    public static final String NAME = "rak-datagram-codec";

    public RakDatagramCodec() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RakCodecPacket packet, ByteBuf buf) throws Exception {
        packet.encode(buf);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> list) throws Exception {
        buffer.markReaderIndex();
        byte potentialFlags = buffer.getByte(buffer.readerIndex());
        if ((potentialFlags & FLAG_VALID) == 0) {
            // Not a RakNet datagram
            buffer.resetReaderIndex();
            list.add(buffer);
            return;
        }

        RakCodecPacket packet;
        if ((potentialFlags & FLAG_ACK) != 0 || (potentialFlags & FLAG_NACK) != 0) {
            packet = new AcknowledgedPacket();
        } else {
            packet = RakDatagramPacket.newInstance();
        }

        packet.decode(buffer);
        list.add(packet);
    }
}
