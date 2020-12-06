package org.cloudburstmc.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.*;
import org.cloudburstmc.netty.EncapsulatedPacket;
import org.cloudburstmc.netty.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakReliability;

import java.util.List;

import static org.cloudburstmc.netty.RakNetConstants.FLAG_VALID;

@Sharable
public class RakDatagramDecoder extends MessageToMessageDecoder<DatagramPacket> {

    public static final RakDatagramDecoder INSTANCE = new RakDatagramDecoder();
    public static final String NAME = "rak-datagram-decoder";

    private RakDatagramDecoder() {
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket datagram, List<Object> list) throws Exception {
        ByteBuf buffer = datagram.content();

        buffer.markReaderIndex();
        byte potentialFlags = buffer.readByte();

        if ((potentialFlags & FLAG_VALID) == 0) {
            // Not a RakNet datagram
            buffer.resetReaderIndex();
            list.add(datagram);
            return;
        }

        RakDatagramPacket rakDatagramPacket = RakDatagramPacket.newInstance();
        rakDatagramPacket.flags = buffer.readByte();
        rakDatagramPacket.sequenceIndex = buffer.readUnsignedMediumLE();
        while (buffer.isReadable()) {
            EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
            decodeEncapsulated(buffer, packet);
            rakDatagramPacket.packets.add(packet);
        }
        list.add(rakDatagramPacket);
    }

    public void decodeEncapsulated(ByteBuf buf, EncapsulatedPacket packet) {
        byte flags = buf.readByte();
        packet.reliability = RakReliability.fromId(flags >>> 5);
        packet.split = (flags & 0x10) != 0;
        int size = (buf.readUnsignedShort() + 7) >> 3;

        if (packet.reliability.isReliable()) {
            packet.reliabilityIndex = buf.readUnsignedMediumLE();
        }

        if (packet.reliability.isSequenced()) {
            packet.sequenceIndex = buf.readUnsignedMediumLE();
        }

        if (packet.reliability.isOrdered() || packet.reliability.isSequenced()) {
            packet.orderingIndex = buf.readUnsignedMediumLE();
            packet.orderingChannel = buf.readUnsignedByte();
        }

        if (packet.split) {
            packet.partCount = buf.readInt();
            packet.partId = buf.readUnsignedShort();
            packet.partIndex = buf.readInt();
        }

        // Slice the buffer to use less memory
        packet.buffer = buf.readSlice(size);
    }
}
