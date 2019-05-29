package com.nukkitx.network.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class RakNetDatagram extends AbstractReferenceCounted {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetDatagram.class);
    final List<EncapsulatedPacket> packets = new ArrayList<>();
    final long sendTime;
    long nextSend;
    byte flags = (byte) 0x80;
    int sequenceIndex = -1;

    @Override
    public RakNetDatagram retain() {
        super.retain();
        return this;
    }

    @Override
    public RakNetDatagram retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public RakNetDatagram touch(Object hint) {
        for (EncapsulatedPacket packet : packets) {
            packet.touch(hint);
        }
        return this;
    }

    public void decode(ByteBuf buf) {
        flags = buf.readByte();
        sequenceIndex = buf.readUnsignedMediumLE();
        while (buf.isReadable()) {
            EncapsulatedPacket packet = new EncapsulatedPacket();
            packet.decode(buf);
            packets.add(packet);
        }
    }

    public void encode(ByteBuf buf) {
        buf.writeByte(flags);
        buf.writeMediumLE(sequenceIndex);
        for (EncapsulatedPacket packet : packets) {
            packet.encode(buf);
        }
    }

    boolean tryAddPacket(EncapsulatedPacket packet, int mtu) {
        int packetLn = packet.getSize();
        if (packetLn >= mtu - RakNetConstants.MAXIMUM_UDP_HEADER_SIZE) {
            return false; // Packet is too large
        }

        int existingLn = 0;
        for (EncapsulatedPacket netPacket : this.packets) {
            existingLn += netPacket.getSize();
        }

        if (existingLn + packetLn >= mtu - RakNetConstants.MAXIMUM_UDP_HEADER_SIZE) {
            return false;
        }

        packets.add(packet);
        if (packet.split) {
            flags |= RakNetConstants.FLAG_CONTINOUS_SEND;
        }
        return true;
    }

    @Override
    public boolean release() {
        return super.release();
    }

    @Override
    protected void deallocate() {
        for (EncapsulatedPacket packet : packets) {
            packet.release();
        }
    }

    public int getSize() {
        int size = 1;
        for (EncapsulatedPacket packet : packets) {
            size += packet.getSize();
        }
        return size;
    }
}