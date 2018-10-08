package com.nukkitx.network.raknet.datagram;

import com.nukkitx.network.raknet.session.RakNetSession;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import static com.nukkitx.network.raknet.RakNetUtil.MAX_ENCAPSULATED_HEADER_SIZE;
import static com.nukkitx.network.raknet.RakNetUtil.MAX_MESSAGE_HEADER_SIZE;

@Data
public class EncapsulatedRakNetPacket implements ReferenceCounted {
    private RakNetReliability reliability;
    private int reliabilityNumber;
    private int sequenceIndex;
    private int orderingIndex;
    private byte orderingChannel;
    private boolean split;
    private int partCount;
    private short partId;
    private int partIndex;
    private ByteBuf buffer;

    public static List<EncapsulatedRakNetPacket> encapsulatePackage(ByteBuf buffer, RakNetSession session, boolean isOrdered) {
        // Potentially split the package.
        List<ByteBuf> bufs = new ArrayList<>();
        int by = session.getMtu() - MAX_ENCAPSULATED_HEADER_SIZE - MAX_MESSAGE_HEADER_SIZE;
        if (buffer.readableBytes() > by) {
            // Packet requires splitting
            int split = ((buffer.readableBytes() - 1) / by) + 1;
            buffer.retain(split - 1); // All derived ByteBufs share the same reference counter.
            for (int i = 0; i < split; i++) {
                bufs.add(buffer.readSlice(Math.min(by, buffer.readableBytes())));
            }
        } else {
            // No splitting required.
            bufs.add(buffer);
        }

        // Now create the packets.
        List<EncapsulatedRakNetPacket> packets = new ArrayList<>();
        short splitId = (short) (System.nanoTime() % Short.MAX_VALUE);
        int orderNumber = isOrdered ? session.getOrderSequenceGenerator().getAndIncrement() : 0;
        for (int i = 0; i < bufs.size(); i++) {
            // Encryption requires RELIABLE_ORDERED
            EncapsulatedRakNetPacket packet = new EncapsulatedRakNetPacket();
            packet.setBuffer(bufs.get(i));
            packet.setReliability(isOrdered ? RakNetReliability.RELIABLE_ORDERED : RakNetReliability.RELIABLE);
            packet.setReliabilityNumber(session.getReliabilitySequenceGenerator().getAndIncrement());
            packet.setOrderingIndex(orderNumber);
            if (bufs.size() > 1) {
                packet.setSplit(true);
                packet.setPartIndex(i);
                packet.setPartCount(bufs.size());
                packet.setPartId(splitId);
            }
            packets.add(packet);
        }
        return packets;
    }

    public void encode(ByteBuf buf) {
        int flags = reliability.ordinal();
        buf.writeByte((byte) ((flags << 5) | (split ? 0b00010000 : 0x00))); // flags
        buf.writeShort(buffer.readableBytes() * 8); // size

        if (reliability.isReliable()) {
            buf.writeMediumLE(reliabilityNumber);
        }

        if (reliability.isSequenced()) {
            buf.writeMediumLE(sequenceIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            buf.writeMediumLE(orderingIndex);
            buf.writeByte(orderingChannel);
        }

        if (split) {
            buf.writeInt(partCount);
            buf.writeShort(partId);
            buf.writeInt(partIndex);
        }

        buf.writeBytes(buffer);
    }

    public void decode(ByteBuf buf) {
        short flags = buf.readUnsignedByte();
        reliability = RakNetReliability.values()[((flags & 0b11100000) >> 5)];
        split = ((flags & 0b00010000) > 0);
        short size = (short) Math.ceil(buf.readShort() / 8D);

        if (reliability.isReliable()) {
            reliabilityNumber = buf.readUnsignedMediumLE();
        }

        if (reliability.isSequenced()) {
            sequenceIndex = buf.readUnsignedMediumLE();
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            orderingIndex = buf.readUnsignedMediumLE();
            orderingChannel = buf.readByte();
        }

        if (split) {
            partCount = buf.readInt();
            partId = buf.readShort();
            partIndex = buf.readInt();
        }

        buffer = buf.readBytes(size);
    }

    public ByteBuf getBuffer() {
        return buffer;
    }

    public void setBuffer(ByteBuf buffer) {
        this.buffer = buffer;
    }

    public int totalLength() {
        // Include back of the envelope calculation
        return buffer.writerIndex() + 24;
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

