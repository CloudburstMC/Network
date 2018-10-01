package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.PacketCodec;
import com.nukkitx.network.PacketFactory;
import com.nukkitx.network.raknet.packet.*;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectByteMap;
import gnu.trove.map.hash.TObjectByteHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.util.Arrays;

@SuppressWarnings("unchecked")
public class RakNetPacketRegistry<T extends NetworkSession> implements PacketCodec<RakNetPacket> {
    private static final RakNetPacketRegistry DEFAULT = new RakNetPacketRegistry();

    static {
        DEFAULT.registerPacket(ConnectedPingPacket::new, 0x00);
        DEFAULT.registerPacket(UnconnectedPingPacket::new, 0x01);
        DEFAULT.registerPacket(ConnectedPongPacket::new, 0x03);
        DEFAULT.registerPacket(OpenConnectionRequest1Packet::new, 0x05);
        DEFAULT.registerPacket(OpenConnectionReply1Packet::new, 0x06);
        DEFAULT.registerPacket(OpenConnectionRequest2Packet::new, 0x07);
        DEFAULT.registerPacket(OpenConnectionReply2Packet::new, 0x08);
        DEFAULT.registerPacket(ConnectionRequestPacket::new, 0x09);
        DEFAULT.registerPacket(ConnectionRequestAcceptedPacket::new, 0x10);
        DEFAULT.registerPacket(NewIncomingConnectionPacket::new, 0x13);
        DEFAULT.registerPacket(NoFreeIncomingConnectionsPacket::new, 0x14);
        DEFAULT.registerPacket(DisconnectNotificationPacket::new, 0x15);
        DEFAULT.registerPacket(ConnectionBannedPacket::new, 0x17);
        DEFAULT.registerPacket(IncompatibleProtocolVersion::new, 0x19);
        DEFAULT.registerPacket(IpRecentlyConnectedPacket::new, 0x1a);
        DEFAULT.registerPacket(UnconnectedPongPacket::new, 0x1c);
        DEFAULT.registerPacket(NakPacket::new, 0xa0);
        DEFAULT.registerPacket(AckPacket::new, 0xc0);
    }

    private final PacketFactory<RakNetPacket>[] factories;
    private final TObjectByteMap<Class<? extends RakNetPacket>> idMapping = new TObjectByteHashMap<>(64, 0.75f, (byte) -1);

    private RakNetPacketRegistry() {
        factories = (PacketFactory<RakNetPacket>[]) new PacketFactory[256];
    }

    RakNetPacketRegistry(TIntObjectMap<PacketFactory<CustomRakNetPacket<T>>> packets) {
        factories = Arrays.copyOf(DEFAULT.factories, DEFAULT.factories.length);
        idMapping.putAll(DEFAULT.idMapping);
        packets.forEachEntry((i, factory) -> {
            registerPacket((PacketFactory) factory, i);
            return true;
        });
    }

    private void registerPacket(PacketFactory<RakNetPacket> factory, int id) {
        if (factories.length > id && factories[id] == null) {
            factories[id] = factory;
            idMapping.put(factory.getPacketClass(), (byte) id);
        } else {
            throw new IllegalArgumentException("Invalid packet ID");
        }
    }

    @Override
    public RakNetPacket tryDecode(ByteBuf byteBuf) {
        short id = byteBuf.readUnsignedByte();
        RakNetPacket packet = factories[id].newInstance();
        packet.decode(byteBuf);

        if (byteBuf.isReadable()) {
            throw new RuntimeException(packet.getClass().getSimpleName() + " still has " + byteBuf.readableBytes() + " bytes to read!");
        }
        return packet;
    }

    @Override
    public ByteBuf tryEncode(RakNetPacket packet) {
        ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer();
        byteBuf.writeByte(getId(packet));
        packet.encode(byteBuf);
        return byteBuf;
    }

    public int getId(RakNetPacket packet) {
        Class<? extends RakNetPacket> clazz = packet.getClass();
        byte id = idMapping.get(clazz);
        if (id == -1) {
            throw new IllegalArgumentException("Packet ID for " + clazz.getName() + " does not exist.");
        }
        return id;
    }
}
