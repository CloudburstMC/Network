package com.nukkitx.network;

import com.nukkitx.network.util.DisconnectReason;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.Optional;

public interface NetworkSession<T extends SessionConnection<?>> {

    void close();

    void onDisconnect(DisconnectReason reason);

    default Optional<InetSocketAddress> getRemoteAddress() {
        return getConnection().getRemoteAddress();
    }

    @Nonnull
    T getConnection();
}
