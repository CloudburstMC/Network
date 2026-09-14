package org.cloudburstmc.netty.signalling.admission;

import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.exception.NativeOperationException;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Access to native construction diagnostics, which released native libraries do not carry. Assertions on them are
 * skipped when the loaded library was not built with them, so the remaining checks still run.
 */
final class NativeDiagnostics {
    private NativeDiagnostics() {
    }

    static OptionalLong creationAttempts() {
        try {
            return OptionalLong.of(PeerConnection.nativeCreationAttempts());
        } catch (NativeOperationException unavailable) {
            return OptionalLong.empty();
        }
    }

    static void assertCreations(OptionalLong before, long expectedDelta) {
        if (!before.isPresent()) {
            return;
        }
        assertEquals(before.getAsLong() + expectedDelta, PeerConnection.nativeCreationAttempts(),
                "native peer construction count");
    }
}
