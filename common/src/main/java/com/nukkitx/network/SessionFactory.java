package com.nukkitx.network;

@FunctionalInterface
public interface SessionFactory<T extends NetworkSession, U extends SessionConnection<?>> {

    T createSession(U connection);
}
