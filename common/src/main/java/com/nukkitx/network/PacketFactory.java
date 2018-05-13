package com.nukkitx.network;

@FunctionalInterface
public interface PacketFactory<T extends NetworkPacket> {
    T newInstance();
}
