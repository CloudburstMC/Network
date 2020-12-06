package org.cloudburstmc.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.netty.EncapsulatedPacket;
import org.cloudburstmc.netty.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.RakReliability;

import java.util.List;

public class RakDatagramEncoder extends MessageToMessageEncoder<RakDatagramPacket> {

    public static final RakDatagramEncoder INSTANCE = new RakDatagramEncoder();
    public static final String NAME = "rak-datagram-encoder";

    private RakDatagramEncoder() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RakDatagramPacket datagram, List<Object> list) throws Exception {
        ByteBuf header = ctx.alloc().ioBuffer(4);

        header.writeByte(datagram.flags);
        header.writeMediumLE(datagram.sequenceIndex);

        // Use a composite buffer so we don't have to do any memory copying.
        CompositeByteBuf buffer = ctx.alloc().compositeBuffer((datagram.packets.size() * 2) + 1);

        buffer.addComponent(header);
        for (EncapsulatedPacket packet : datagram.packets) {
            encodeEncapsulated(buffer, packet);
        }
    }

    private void encodeEncapsulated(CompositeByteBuf buffer, EncapsulatedPacket packet) {
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
}
