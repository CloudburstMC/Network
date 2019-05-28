package com.nukkitx.network.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
@Setter(AccessLevel.NONE)
public class EncapsulatedPacket implements ReferenceCounted {
    RakNetReliability reliability;
    RakNetPriority priority;
    int reliabilityIndex;
    int sequenceIndex;
    int orderingIndex;
    short orderingChannel;
    boolean split;
    long partCount;
    int partId;
    long partIndex;
    ByteBuf buffer;

    public void encode(ByteBuf buf) {
        int flags = reliability.ordinal() << 5;
        if (split) {
            flags |= 0b00010000;
        }
        buf.writeByte(flags); // flags
        buf.writeShort(buffer.readableBytes() << 3); // size

        if (reliability.isReliable()) {
            buf.writeMediumLE(reliabilityIndex);
        }

        if (reliability.isSequenced()) {
            buf.writeMediumLE(sequenceIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            buf.writeMediumLE(orderingIndex);
            buf.writeByte(orderingChannel);
        }

        if (split) {
            buf.writeInt((int) partCount);
            buf.writeShort(partId);
            buf.writeInt((int) partIndex);
        }

        buf.writeBytes(this.buffer, this.buffer.readerIndex(), this.buffer.readableBytes());
        // If we need to resend, we don't want the buffer's reader index changing.
    }

    public void decode(ByteBuf buf) {
        byte flags = buf.readByte();
        reliability = RakNetReliability.fromId((flags & 0b11100000) >> 5);
        split = (flags & 0b00010000) != 0;
        short size = (short) Math.ceil(buf.readShort() / 8D);

        if (reliability.isReliable()) {
            reliabilityIndex = buf.readUnsignedMediumLE();
        }

        if (reliability.isSequenced()) {
            sequenceIndex = buf.readUnsignedMediumLE();
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            orderingIndex = buf.readUnsignedMediumLE();
            orderingChannel = buf.readUnsignedByte();
        }

        if (split) {
            partCount = buf.readUnsignedInt();
            partId = buf.readUnsignedShort();
            partIndex = buf.readUnsignedInt();
        }

        // Slice the buffer to use less memory
        buffer = buf.readSlice(size);
    }

    public int getSize() {
        // Include back of the envelope calculation
        int size = 3 + reliability.getSize();

        if (this.split) {
            size += 10;
        }
        size += buffer.readableBytes();

        return size;
    }

    public EncapsulatedPacket fromSplit(ByteBuf reassembled) {
        EncapsulatedPacket packet = new EncapsulatedPacket();
        packet.reliability = this.reliability;
        packet.reliabilityIndex = this.reliabilityIndex;
        packet.sequenceIndex = this.sequenceIndex;
        packet.orderingIndex = this.orderingIndex;
        packet.orderingChannel = this.orderingChannel;
        packet.buffer = reassembled;
        return packet;
    }

    @Override
    public int refCnt() {
        return buffer.refCnt();
    }

    @Override
    public ReferenceCounted retain() {
        return buffer.retain();
    }

    @Override
    public ReferenceCounted retain(int i) {
        return buffer.retain(i);
    }

    @Override
    public ReferenceCounted touch() {
        return buffer.touch();
    }

    @Override
    public ReferenceCounted touch(Object o) {
        return buffer.touch(o);
    }

    @Override
    public boolean release() {
        return buffer.release();
    }

    @Override
    public boolean release(int i) {
        return buffer.release(i);
    }
}

