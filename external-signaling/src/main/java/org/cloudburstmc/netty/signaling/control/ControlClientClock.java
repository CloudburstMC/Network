package org.cloudburstmc.netty.signaling.control;

import java.util.function.LongSupplier;

/** Shared nondecreasing provider-aligned clock for proof, authority, operation and reconnect deadlines. */
@FunctionalInterface
public interface ControlClientClock {
    long nowMillis();

    static ControlClientClock system() { return monotonic(System::currentTimeMillis, System::nanoTime); }

    static ControlClientClock monotonic(LongSupplier wallMillis, LongSupplier elapsedNanos) {
        return new ControlClientClock() {
            private final long start = elapsedNanos.getAsLong();
            private long offset = wallMillis.getAsLong();
            private long highestElapsed;
            @Override public synchronized long nowMillis() {
                highestElapsed = Math.max(highestElapsed, (elapsedNanos.getAsLong() - start) / 1_000_000);
                offset = Math.max(offset, wallMillis.getAsLong() - highestElapsed);
                long result = offset + highestElapsed;
                ControlJson.safe(result, false);
                return result;
            }
        };
    }
}
