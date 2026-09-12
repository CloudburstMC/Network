package org.cloudburstmc.netty.signalling.provider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the JVM alive briefly while the provider sends its best-effort drain.
 */
public final class ProviderShutdown implements AutoCloseable {
    private final Supplier<? extends CompletionStage<Void>> stop;
    private final Runnable cleanup;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final Thread hook;

    public ProviderShutdown(Supplier<? extends CompletionStage<Void>> stop, Consumer<String> diagnostics) {
        this(stop, () -> {
        }, diagnostics);
    }

    public ProviderShutdown(Supplier<? extends CompletionStage<Void>> stop, Runnable cleanup, Consumer<String> diagnostics) {
        this.stop = stop;
        this.cleanup = cleanup;
        hook = new Thread(() -> {
            close();
            try {
                stopped.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                diagnostics.accept("Provider shutdown did not complete within its shutdown window.");
            }
        }, "nethernet-provider-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /**
     * Normal extension shutdown stays asynchronous, including on a Netty event loop.
     */
    @Override
    public void close() {
        if (!requested.compareAndSet(false, true)) return;
        try {
            stop.get().whenComplete((ignored, failure) -> finish(failure));
        } catch (RuntimeException failure) {
            finish(failure);
        }
    }

    private void finish(Throwable failure) {
        // The transport's signed drain owns the endpoint until stop completes.
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        if (failure == null) stopped.complete(null);
        else stopped.completeExceptionally(failure);
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) { /* The JVM is already awaiting this hook. */ }
    }
}
