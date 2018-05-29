package com.nukkitx.network;

public interface SessionFactory<T extends NetworkSession, U extends NetworkPacket> {

    T createSession(SessionConnection<U> connection);
}
