package com.nukkitx.network;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

public interface NetworkClient<T extends NetworkSession<U>, U extends SessionConnection<?>> {

    CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress) throws Exception;

    CompletableFuture<T> connect(@Nonnull InetSocketAddress remoteAddress, @Nullable InetSocketAddress localAddress) throws Exception;

    void close();
}
