package com.nukkitx.network;

import java.net.InetSocketAddress;
import java.util.Optional;

public interface NetworkSession<T extends NetworkPacket> {

    void disconnect();

    default Optional<InetSocketAddress> getRemoteAddress() {
        return getConnection().getRemoteAddress();
    }

    SessionConnection<T> getConnection();

    void onTick();

    void touch();
}
