package org.cloudburstmc.netty.channel.raknet.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.cloudburstmc.netty.RakNetConstants.*;

public class RakDatagramPacket extends AbstractReferenceCounted implements RakCodecPacket {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakDatagramPacket.class);

    private static final ObjectPool<RakDatagramPacket> RECYCLER = ObjectPool.newPool(RakDatagramPacket::new);

    private final ObjectPool.Handle<RakDatagramPacket> handle;
    public final List<EncapsulatedPacket> packets = new ArrayList<EncapsulatedPacket>();
    public byte flags;
    public long sendTime;
    public long nextSend;
    public int sequenceIndex = -1;

    public static RakDatagramPacket newInstance() {
        return RECYCLER.get();
    }

    private RakDatagramPacket(ObjectPool.Handle<RakDatagramPacket> handle) {
        this.handle = handle;
    }

    @Override
    public void encode(ByteBuf buffer) {
        ByteBuf header = buffer.alloc().ioBuffer(4);
        header.writeByte(this.flags);
        header.writeMediumLE(this.sequenceIndex);

        // Use a composite buffer so we don't have to do any memory copying.
        CompositeByteBuf composite = buffer.alloc().compositeBuffer((this.packets.size() * 2) + 1);

        composite.addComponent(header);
        for (EncapsulatedPacket packet : this.packets) {
            packet.encode(composite);
        }
        buffer.writeBytes(composite);
    }

    @Override
    public void decode(ByteBuf buffer) {
        this.flags = buffer.readByte();
        this.sequenceIndex = buffer.readUnsignedMediumLE();
        while (buffer.isReadable()) {
            EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
            packet.decode(buffer);
            this.packets.add(packet);
        }
    }

    @Override
    public RakDatagramPacket retain() {
        super.retain();
        return this;
    }

    @Override
    public RakDatagramPacket retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public RakDatagramPacket touch(Object hint) {
        for (EncapsulatedPacket packet : this.packets) {
            packet.touch(hint);
        }
        return this;
    }

    public boolean tryAddPacket(EncapsulatedPacket packet, int mtu) {
        if (this.getSize() + packet.getSize() > mtu - RAKNET_DATAGRAM_HEADER_SIZE) {
            return false;
        }

        this.packets.add(packet);
        if (packet.split) {
            flags |= FLAG_CONTINUOUS_SEND;
        }
        return true;
    }

    @Override
    public boolean release() {
        return super.release();
    }

    @Override
    protected void deallocate() {
        for (EncapsulatedPacket packet : this.packets) {
            packet.release();
        }
        this.packets.clear();
        this.handle.recycle(this);
    }

    public int getSize() {
        int size = RAKNET_DATAGRAM_HEADER_SIZE;
        for (EncapsulatedPacket packet : this.packets) {
            size += packet.getSize();
        }
        return size;
    }
}