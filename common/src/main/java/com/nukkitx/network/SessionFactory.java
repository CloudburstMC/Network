package com.nukkitx.network;

public interface SessionFactory<T> {

    T createSession(SessionConnection connection);
}
