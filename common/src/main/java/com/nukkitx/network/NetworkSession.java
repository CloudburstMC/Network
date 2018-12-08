package com.nukkitx.network;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.Optional;

public interface NetworkSession<T extends SessionConnection<?>> {

    void close();

    void onTimeout();

    default Optional<InetSocketAddress> getRemoteAddress() {
        return getConnection().getRemoteAddress();
    }

    @Nonnull
    T getConnection();
}
