package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;

import java.util.List;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakDatagramCodec extends MessageToMessageCodec<ByteBuf, RakDatagramPacket> {
    public static final String NAME = "rak-datagram-codec";

    public RakDatagramCodec() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RakDatagramPacket packet, List<Object> out) throws Exception {
        ByteBuf header = ctx.alloc().ioBuffer(4);
        header.writeByte(packet.getFlags());
        header.writeMediumLE(packet.getSequenceIndex());

        // Use a composite buffer so we don't have to do any memory copying.
        CompositeByteBuf buf = ctx.alloc().compositeBuffer((packet.getPackets().size() * 2) + 1);
        buf.addComponent(header);

        for (EncapsulatedPacket encapsulated : packet.getPackets()) {
            encapsulated.encode(buf);
        }
        System.out.println("Encoded " + packet);
        out.add(buf);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> list) throws Exception {
        byte potentialFlags = buffer.getByte(buffer.readerIndex());
        if ((potentialFlags & FLAG_VALID) == 0) {
            // Not a RakNet datagram
            list.add(buffer.retain());
            return;
        }

        if ((potentialFlags & FLAG_ACK) != 0 || (potentialFlags & FLAG_NACK) != 0) {
            // Do not handle Acknowledge packets here
            list.add(buffer.retain());
            return;
        }

        RakDatagramPacket packet = RakDatagramPacket.newInstance();
        try {
            packet.setFlags(buffer.readByte());
            packet.setSequenceIndex(buffer.readUnsignedMediumLE());
            while (buffer.isReadable()) {
                EncapsulatedPacket encapsulated = EncapsulatedPacket.newInstance();
                try {
                    encapsulated.decode(buffer);
                    packet.getPackets().add(encapsulated.retain());
                } finally {
                    encapsulated.release();
                }
            }
            System.out.println("Decoded " + packet);
            list.add(packet.retain());
        } finally {
            packet.release();
        }
    }
}
