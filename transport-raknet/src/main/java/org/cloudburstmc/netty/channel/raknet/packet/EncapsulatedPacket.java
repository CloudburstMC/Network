package org.cloudburstmc.netty.channel.raknet.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ObjectPool;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;

public class EncapsulatedPacket extends AbstractReferenceCounted {

    private static final ObjectPool<EncapsulatedPacket> RECYCLER = ObjectPool.newPool(EncapsulatedPacket::new);

    private final ObjectPool.Handle<EncapsulatedPacket> handle;
    public RakReliability reliability;
    public RakPriority priority;
    public int reliabilityIndex;
    public int sequenceIndex;
    public int orderingIndex;
    public short orderingChannel;
    public boolean split;
    public int partCount;
    public int partId;
    public int partIndex;
    public ByteBuf buffer;

    public static EncapsulatedPacket newInstance() {
        return RECYCLER.get();
    }

    private EncapsulatedPacket(ObjectPool.Handle<EncapsulatedPacket> handle) {
        this.handle = handle;
    }

    public void encode(CompositeByteBuf buffer) {
        RakReliability reliability = this.reliability;
        ByteBuf header = buffer.alloc().ioBuffer(3 + reliability.getSize() + (this.split ? 10 : 0));

        int flags = this.reliability.ordinal() << 5;
        if (this.split) {
            flags |= 0x10;
        }
        header.writeByte(flags);
        header.writeShort(this.buffer.readableBytes() << 3); // size

        if (reliability.isReliable()) {
            header.writeMediumLE(this.reliabilityIndex);
        }

        if (reliability.isSequenced()) {
            header.writeMediumLE(this.sequenceIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            header.writeMediumLE(this.orderingIndex);
            header.writeByte(this.orderingChannel);
        }

        if (this.split) {
            header.writeInt(this.partCount);
            header.writeShort(this.partId);
            header.writeInt(this.partIndex);
        }

        buffer.addComponent(header);
        buffer.addComponent(false, this.buffer);
    }

    public void decode(ByteBuf buf) {
        byte flags = buf.readByte();
        this.reliability = RakReliability.fromId(flags >>> 5);
        this.split = (flags & 0x10) != 0;
        int size = (buf.readUnsignedShort() + 7) >> 3;

        if (this.reliability.isReliable()) {
            this.reliabilityIndex = buf.readUnsignedMediumLE();
        }

        if (this.reliability.isSequenced()) {
            this.sequenceIndex = buf.readUnsignedMediumLE();
        }

        if (this.reliability.isOrdered() || this.reliability.isSequenced()) {
            this.orderingIndex = buf.readUnsignedMediumLE();
            this.orderingChannel = buf.readUnsignedByte();
        }

        if (this.split) {
            this.partCount = buf.readInt();
            this.partId = buf.readUnsignedShort();
            this.partIndex = buf.readInt();
        }

        // Slice the buffer to use less memory
        this.buffer = buf.readSlice(size);
    }

    public int getSize() {
        // Include back of the envelope calculation
        return 3 + this.reliability.getSize() + (this.split ? 10 : 0) + this.buffer.readableBytes();
    }

    public EncapsulatedPacket fromSplit(ByteBuf reassembled) {
        EncapsulatedPacket packet = newInstance();
        packet.reliability = this.reliability;
        packet.reliabilityIndex = this.reliabilityIndex;
        packet.sequenceIndex = this.sequenceIndex;
        packet.orderingIndex = this.orderingIndex;
        packet.orderingChannel = this.orderingChannel;
        packet.buffer = reassembled;
        return packet;
    }


    @Override
    protected void deallocate() {
        this.buffer.release();
        this.handle.recycle(this);
    }

    @Override
    public ReferenceCounted touch(Object o) {
        return this.buffer.touch();
    }

    @Override
    public EncapsulatedPacket retain() {
        return (EncapsulatedPacket) super.retain();
    }
}

