package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import java.util.List;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class RakDatagramCodec extends ByteToMessageCodec<RakDatagramPacket> {
    public static final String NAME = "rak-datagram-codec";

    public RakDatagramCodec() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RakDatagramPacket packet, ByteBuf buf) throws Exception {
        ByteBuf header = ctx.alloc().ioBuffer(4);
        header.writeByte(packet.flags);
        header.writeMediumLE(packet.sequenceIndex);

        // Use a composite buffer so we don't have to do any memory copying.
        CompositeByteBuf composite = ctx.alloc().compositeBuffer((packet.packets.size() * 2) + 1);
        composite.addComponent(header);

        for (EncapsulatedPacket encapsulated : packet.packets) {
            encapsulated.encode(composite);
        }
        buf.writeBytes(composite);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> list) throws Exception {
        byte potentialFlags = buffer.getByte(buffer.readerIndex());
        if ((potentialFlags & FLAG_VALID) == 0) {
            // Not a RakNet datagram
            list.add(buffer);
            return;
        }

        if ((potentialFlags & FLAG_ACK) != 0 || (potentialFlags & FLAG_NACK) != 0) {
            // Do not handle Acknowledge packets here
            list.add(buffer);
            return;
        }

        RakDatagramPacket packet = RakDatagramPacket.newInstance();
        packet.flags = buffer.readByte();
        packet.sequenceIndex = buffer.readUnsignedMediumLE();
        while (buffer.isReadable()) {
            EncapsulatedPacket encapsulated = EncapsulatedPacket.newInstance();
            encapsulated.decode(buffer);
            packet.packets.add(encapsulated);
        }
        list.add(packet);
    }
}
