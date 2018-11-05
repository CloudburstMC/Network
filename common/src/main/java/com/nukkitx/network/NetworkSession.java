package com.nukkitx.network;

import java.net.InetSocketAddress;
import java.util.Optional;

public interface NetworkSession<T extends SessionConnection<?>> {

    void disconnect();

    void onTimeout();

    default Optional<InetSocketAddress> getRemoteAddress() {
        return getConnection().getRemoteAddress();
    }

    T getConnection();
}
