package org.cloudburstmc.netty.signaling;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

import static org.cloudburstmc.netty.signaling.ProviderDiagnostic.Level.*;
import static org.cloudburstmc.netty.signaling.ProviderLog.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderLogTest {
    @Test
    void retriesStayQuietUntilTheSameOperationRecovers() {
        var messages = new ArrayList<ProviderDiagnostic>();
        var log = new ProviderLog(messages::add);
        log.recovered(STATUS);
        log.failed(STATUS);
        log.failed(STATUS);
        log.failed(WEBSOCKET);
        log.recovered(STATUS);
        log.recovered(STATUS);
        log.failed(WEBSOCKET);
        log.recovered(WEBSOCKET);
        log.failed(STATUS);
        assertEquals(
                List.of(WARN, DEBUG, WARN, INFO, DEBUG, INFO, WARN),
                messages.stream().map(ProviderDiagnostic::level).toList());
        assertEquals(
                List.of(
                        STATUS.failure,
                        STATUS.failure,
                        WEBSOCKET.failure,
                        STATUS.recovery,
                        WEBSOCKET.failure,
                        WEBSOCKET.recovery,
                        STATUS.failure),
                messages.stream().map(ProviderDiagnostic::message).toList());
    }

    @Test
    void newClientsDoNotInheritSuppressedWarnings() {
        var messages = new ArrayList<ProviderDiagnostic>();
        new ProviderLog(messages::add).failed(STATUS);
        new ProviderLog(messages::add).failed(STATUS);
        assertEquals(
                List.of(WARN, WARN), messages.stream().map(ProviderDiagnostic::level).toList());
    }
}
