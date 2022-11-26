package com.nukkitx.network.raknet;

import com.nukkitx.network.util.Bootstraps;
import com.nukkitx.network.util.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

import javax.annotation.Nonnegative;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ParametersAreNonnullByDefault
public abstract class RakNet implements AutoCloseable {
    protected final long guid = ThreadLocalRandom.current().nextLong();
    protected int protocolVersion = RakNetConstants.RAKNET_PROTOCOL_VERSION;

    private final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final Bootstrap bootstrap;
    private ScheduledFuture<?> tickFuture;

    protected final Map<String, Consumer<Throwable>> exceptionHandlers = new HashMap<>();
    protected RakMetrics metrics;

    RakNet(EventLoopGroup eventLoopGroup) {
        this.bootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT);
        this.bootstrap.group(eventLoopGroup);
        Bootstraps.setupBootstrap(this.bootstrap, true);
        this.bootstrap.option(RakNetChannelOption.IP_DONT_FRAG, 1);
    }

    public CompletableFuture<Void> bind() {
        Preconditions.checkState(this.running.compareAndSet(false, true), "RakNet has already been started");
        CompletableFuture<Void> future = this.bindInternal();

        future.whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                // Failed to start. Set running to false
                this.running.compareAndSet(true, false);
                return;
            }

            this.closed.set(false);
            this.tickFuture = this.nextEventLoop().scheduleAtFixedRate(this::onTick, 0, 10, TimeUnit.MILLISECONDS);
        });
        return future;
    }

    public void close() {
        this.close(false);
    }

    public void close(boolean force) {
        this.closed.set(true);
        if (this.tickFuture != null) {
            this.tickFuture.cancel(false);
        }
    }

    protected abstract CompletableFuture<Void> bindInternal();

    protected abstract void onTick();

    public boolean isRunning() {
        return this.running.get();
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    public Bootstrap getBootstrap() {
        return this.bootstrap;
    }

    @Nonnegative
    public int getProtocolVersion() {
        return this.protocolVersion;
    }

    public void setProtocolVersion(@Nonnegative int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public abstract InetSocketAddress getBindAddress();

    public long getGuid() {
        return this.guid;
    }

    protected EventLoopGroup getEventLoopGroup() {
        return this.bootstrap.config().group();
    }

    protected EventLoop nextEventLoop() {
        return this.getEventLoopGroup().next();
    }

    public void setMetrics(RakMetrics metrics) {
        this.metrics = metrics;
    }

    public RakMetrics getMetrics() {
        return this.metrics;
    }

    public void addExceptionHandler(String handlerId, Consumer<Throwable> handler) {
        Objects.requireNonNull(handlerId, "handlerId is empty");
        Objects.requireNonNull(handler, "clientExceptionHandler (handler is null)");
        this.exceptionHandlers.put(handlerId, handler);
    }

    public void removeExceptionHandler(String handlerId) {
        this.exceptionHandlers.remove(handlerId);
    }

    public void clearExceptionHandlers() {
        this.exceptionHandlers.clear();
    }

    public Collection<Consumer<Throwable>> getExceptionHandlers() {
        return this.exceptionHandlers.values();
    }
}
