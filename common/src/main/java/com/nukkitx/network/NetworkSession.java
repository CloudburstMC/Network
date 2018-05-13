package com.nukkitx.network;

import java.net.InetSocketAddress;
import java.util.Optional;

public interface NetworkSession {

    void disconnect();

    boolean isConnected();

    default Optional<InetSocketAddress> getRemoteAddress() {
        return getConnection().getRemoteAddress();
    }

    SessionConnection getConnection();

    void onTick();

    void touch();
}
