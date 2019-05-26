package com.nukkitx.network;

import java.util.concurrent.CompletableFuture;

public interface NetworkInterface {

    CompletableFuture<Void> bind();

    void close();

    boolean isRunning();

    boolean isClosed();
}
