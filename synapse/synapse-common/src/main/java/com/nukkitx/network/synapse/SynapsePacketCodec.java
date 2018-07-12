package com.nukkitx.network.synapse;

import com.nukkitx.network.PacketCodec;
import com.nukkitx.network.PacketFactory;
import com.nukkitx.network.util.Preconditions;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnegative;
import javax.annotation.concurrent.Immutable;

@Immutable
@RequiredArgsConstructor
public class SynapsePacketCodec implements PacketCodec<SynapsePacket> {
    public static final int SYNAPSE_PROTOCOL_VERSION = 8;
    public static final SynapsePacketCodec DEFAULT;

    static {
        DEFAULT = builder()
                .protocolVersion(SYNAPSE_PROTOCOL_VERSION)
                .build();
    }

    private final int protocolVersion;
    private final PacketFactory<? extends SynapsePacket>[] factories;
    private final TObjectIntMap<Class<? extends SynapsePacket>> idMappings;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SynapsePacket tryDecode(ByteBuf buf) {
        int id = buf.readUnsignedByte();
        SynapsePacket packet = factories[id].newInstance();
        packet.decode(buf);
        return packet;
    }

    @Override
    public ByteBuf tryEncode(SynapsePacket packet) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();

        buf.writeByte(getId(packet));
        packet.encode(buf);
        return buf;
    }

    @Override
    public int getId(SynapsePacket packet) {
        Class<? extends SynapsePacket> clazz = packet.getClass();
        int id = idMappings.get(clazz);
        if (id == -1) {
            throw new IllegalArgumentException("Packet ID for " + clazz.getName() + " does not exist.");
        }
        return id;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public static class Builder {
        private final TIntObjectMap<PacketFactory<? extends SynapsePacket>> packets = new TIntObjectHashMap<>();
        private final TObjectIntMap<Class<? extends SynapsePacket>> ids = new TObjectIntHashMap<>(64, 0.75f, -1);
        private int protocolVersion = 0;

        public Builder registerPacket(PacketFactory<? extends SynapsePacket> packet, @Nonnegative int id) {
            Preconditions.checkArgument(id >= 0, "id cannot be negative");
            if (packets.containsKey(id)) {
                throw new IllegalArgumentException("Packet id already registered");
            }
            Class<? extends SynapsePacket> packetClass = packet.getPacketClass();
            if (ids.containsKey(packetClass)) {
                throw new IllegalArgumentException("Packet class already registered");
            }

            packets.put(id, packet);
            ids.put(packetClass, id);
            return this;
        }

        public Builder protocolVersion(@Nonnegative int protocolVersion) {
            Preconditions.checkArgument(protocolVersion >= 0, "Protocol version must be positive");
            this.protocolVersion = protocolVersion;
            return this;
        }

        public SynapsePacketCodec build() {
            int largestId = -1;
            for (int id : packets.keys()) {
                if (id > largestId) {
                    largestId = id;
                }
            }
            Preconditions.checkArgument(largestId > -1, "Must have at least one packet registered");
            PacketFactory<? extends SynapsePacket>[] packets = new PacketFactory[largestId + 1];

            TIntObjectIterator<PacketFactory<? extends SynapsePacket>> iter = this.packets.iterator();

            while (iter.hasNext()) {
                iter.advance();
                packets[iter.key()] = iter.value();
            }
            return new SynapsePacketCodec(protocolVersion, packets, ids);
        }
    }
}
