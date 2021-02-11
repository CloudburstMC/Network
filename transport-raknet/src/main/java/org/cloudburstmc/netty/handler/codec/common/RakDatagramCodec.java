package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import org.cloudburstmc.netty.EncapsulatedPacket;
import org.cloudburstmc.netty.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakReliability;

import java.util.List;

import static org.cloudburstmc.netty.RakNetConstants.FLAG_VALID;

@Sharable
public class RakDatagramCodec extends ByteToMessageCodec<RakDatagramPacket> {

    public static final RakDatagramCodec INSTANCE = new RakDatagramCodec();
    public static final String NAME = "rak-datagram-codec";

    private RakDatagramCodec() {
    }

    private static void encodeEncapsulated(CompositeByteBuf buffer, EncapsulatedPacket packet) {
        RakReliability reliability = packet.reliability;
        ByteBuf header = buffer.alloc().ioBuffer(3 + reliability.getSize() + (packet.split ? 10 : 0));

        int flags = packet.reliability.ordinal() << 5;
        if (packet.split) {
            flags |= 0x10;
        }
        header.writeByte(flags);
        header.writeShort(packet.buffer.readableBytes() << 3); // size

        if (reliability.isReliable()) {
            header.writeMediumLE(packet.reliabilityIndex);
        }

        if (reliability.isSequenced()) {
            header.writeMediumLE(packet.sequenceIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            header.writeMediumLE(packet.orderingIndex);
            header.writeByte(packet.orderingChannel);
        }

        if (packet.split) {
            header.writeInt(packet.partCount);
            header.writeShort(packet.partId);
            header.writeInt(packet.partIndex);
        }

        buffer.addComponent(header);
        buffer.addComponent(false, packet.buffer);
    }

    public static void decodeEncapsulated(ByteBuf buf, EncapsulatedPacket packet) {
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

    @Override
    protected void encode(ChannelHandlerContext ctx, RakDatagramPacket datagram, ByteBuf buf) throws Exception {
        ByteBuf header = ctx.alloc().ioBuffer(4);
        header.writeByte(datagram.flags);
        header.writeMediumLE(datagram.sequenceIndex);

        // Use a composite buffer so we don't have to do any memory copying.
        CompositeByteBuf buffer = ctx.alloc().compositeBuffer((datagram.packets.size() * 2) + 1);

        buffer.addComponent(header);
        for (EncapsulatedPacket packet : datagram.packets) {
            encodeEncapsulated(buffer, packet);
        }
        buf.writeBytes(buffer);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> list) throws Exception {
        buffer.markReaderIndex();
        byte potentialFlags = buffer.readByte();

        if ((potentialFlags & FLAG_VALID) == 0) {
            // Not a RakNet datagram
            buffer.resetReaderIndex();
            list.add(buffer);
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
}
