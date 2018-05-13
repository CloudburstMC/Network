package com.nukkitx.network;

import java.net.InetSocketAddress;
import java.util.Collection;

public interface SessionManager<T extends NetworkSession> {

    boolean add(InetSocketAddress address, T session);

    boolean remove(T session);

    T get(InetSocketAddress address);

    Collection<T> all();

    int getCount();

    void onTick();
}
