package com.nukkitx.network;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

public interface NetworkClient<T extends NetworkSession<U>, U extends SessionConnection<?>> {

    CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress) throws Exception;

    void close();
}
