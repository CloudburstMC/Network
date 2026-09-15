package org.cloudburstmc.netty.signaling.control;

import java.nio.charset.StandardCharsets;

/** Unit-only typed result factory; these tests exercise application ownership, not carrier authentication. */
public final class ControlledApplicationResultFixture {
    public static ControlOperationResult delivered(String body, Runnable current) {
        var receipt = new ControlLifecycleCodec.Receipt(1, "A".repeat(43), "heartbeat", "fixture_host", 1, 1, "fixture_intent_0123456789", "committed", 1000L, 1L, null);
        return ControlOperationResult.delivered(ControlResultCodec.create(receipt, body.getBytes(StandardCharsets.UTF_8)), current);
    }
}
