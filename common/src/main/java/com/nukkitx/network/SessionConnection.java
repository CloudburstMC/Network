package com.nukkitx.network;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.Optional;

public interface SessionConnection<T extends NetworkPacket> {

    Optional<InetSocketAddress> getRemoteAddress();

    void close();

    void sendPacket(@Nonnull T packet);

    boolean isClosed();

    void onTick();
}
