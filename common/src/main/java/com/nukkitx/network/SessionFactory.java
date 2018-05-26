package com.nukkitx.network;

public interface SessionFactory<T extends NetworkSession> {

    T createSession(SessionConnection connection);
}
