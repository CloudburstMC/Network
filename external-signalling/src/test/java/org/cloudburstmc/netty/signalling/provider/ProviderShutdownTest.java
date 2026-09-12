package org.cloudburstmc.netty.signalling.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderShutdownTest {
    @Test
    void keepsEndpointUntilDrainCompletesAndCleansUpOnce() {
        CompletableFuture<Void> drain = new CompletableFuture<>();
        AtomicInteger stops = new AtomicInteger(), cleanups = new AtomicInteger();
        ProviderShutdown shutdown = new ProviderShutdown(() -> {
            stops.incrementAndGet();
            return drain;
        }, cleanups::incrementAndGet, ignored -> {});
        try {
            shutdown.close();
            shutdown.close();
            assertEquals(1, stops.get());
            // The provider drain is sent over the endpoint, so it must stay open until the drain completes
            assertEquals(0, cleanups.get());
            drain.complete(null);
            shutdown.close();
            assertEquals(1, cleanups.get());
        } finally {
            drain.complete(null);
        }
    }

    @Test
    void failedDrainStillReleasesEndpoint() {
        CompletableFuture<Void> drain = new CompletableFuture<>();
        AtomicInteger cleanups = new AtomicInteger();
        ProviderShutdown shutdown = new ProviderShutdown(() -> drain, cleanups::incrementAndGet, ignored -> {});
        shutdown.close();
        assertEquals(0, cleanups.get());
        drain.completeExceptionally(new IOException("Provider unavailable"));
        assertEquals(1, cleanups.get());
    }
}
