package org.cloudburstmc.netty.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ObjectUtil;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;

public class SplitPacketHelper extends AbstractReferenceCounted {
    private final EncapsulatedPacket[] packets;
    private final long created = System.currentTimeMillis();

    public SplitPacketHelper(long expectedLength) {
        ObjectUtil.checkPositive(expectedLength, "expectedLength");
        this.packets = new EncapsulatedPacket[(int) expectedLength];
    }

    public EncapsulatedPacket add(EncapsulatedPacket packet, ByteBufAllocator alloc) {
        ObjectUtil.checkNotNull(packet, "packet cannot be null");
        if (!packet.isSplit()) throw new IllegalArgumentException("Packet is not split");
        if (this.refCnt() <= 0) throw new IllegalReferenceCountException(this.refCnt());
        ObjectUtil.checkInRange(packet.getPartIndex(), 0, this.packets.length - 1, "part index");

        int partIndex = packet.getPartIndex();
        if (this.packets[partIndex] != null) {
            // Duplicate
            return null;
        }
        this.packets[partIndex] = packet;
        // Retain the packet so it can be reassembled later.
        packet.retain();

        int sz = 0;
        for (EncapsulatedPacket netPacket : this.packets) {
            if (netPacket == null) {
                return null;
            }
            sz += netPacket.getBuffer().readableBytes();
        }

        // We can't use a composite buffer as the native code will choke on it
        ByteBuf reassembled = alloc.ioBuffer(sz);
        for (EncapsulatedPacket netPacket : this.packets) {
            ByteBuf buf = netPacket.getBuffer();
            reassembled.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
        }

        return packet.fromSplit(reassembled);
    }

    public boolean expired() {
        // If we're waiting on a split packet for more than 30 seconds, the client on the other end is either severely
        // lagging, or has died.
        if (this.refCnt() <= 0) throw new IllegalReferenceCountException(this.refCnt());
        return System.currentTimeMillis() - created >= 30000;
    }

    @Override
    protected void deallocate() {
        for (EncapsulatedPacket packet : this.packets) {
            ReferenceCountUtil.release(packet);
        }
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        throw new UnsupportedOperationException();
    }
}
