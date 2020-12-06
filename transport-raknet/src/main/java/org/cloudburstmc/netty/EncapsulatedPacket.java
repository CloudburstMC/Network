package org.cloudburstmc.netty;

import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ObjectPool;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import org.cloudburstmc.netty.channel.raknet.RakPriority;
import org.cloudburstmc.netty.channel.raknet.RakReliability;

public class EncapsulatedPacket extends AbstractReferenceCounted {

    private static final ObjectPool<EncapsulatedPacket> RECYCLER = ObjectPool.newPool(new ObjectPool.ObjectCreator<EncapsulatedPacket>() {
        public EncapsulatedPacket newObject(ObjectPool.Handle<EncapsulatedPacket> handle) {
            return new EncapsulatedPacket(handle);
        }
    });

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

    public int getSize() {
        // Include back of the envelope calculation
        return 3 + this.reliability.getSize() + (this.split ? 10 : 0) + this.buffer.readableBytes();
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
    protected void deallocate() {
        this.buffer.release();
        this.handle.recycle(this);
    }

    @Override
    public ReferenceCounted touch(Object o) {
        return this.buffer.touch();
    }
}

