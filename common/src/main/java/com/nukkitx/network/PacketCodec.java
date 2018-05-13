package com.nukkitx.network;

import io.netty.buffer.ByteBuf;

public interface PacketCodec<T extends NetworkPacket> {

    T tryDecode(ByteBuf byteBuf);

    ByteBuf tryEncode(T packet);

    byte getId(T packet);

    void registerPacket(PacketFactory<T> packet, int id);
}
