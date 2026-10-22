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
        log.failed(STATUS, "ConnectException");
        log.failed(STATUS, "ConnectException");
        log.failed(WEBSOCKET, "ConnectException");
        log.recovered(STATUS);
        log.recovered(STATUS);
        log.failed(WEBSOCKET, "ConnectException");
        log.recovered(WEBSOCKET);
        log.failed(STATUS, "ConnectException");
        assertEquals(
                List.of(WARN, DEBUG, WARN, INFO, DEBUG, INFO, WARN),
                messages.stream().map(ProviderDiagnostic::level).toList());
        assertEquals(
                List.of(
                        STATUS.message("ConnectException"),
                        STATUS.message("ConnectException"),
                        WEBSOCKET.message("ConnectException"),
                        STATUS.recovery,
                        WEBSOCKET.message("ConnectException"),
                        WEBSOCKET.recovery,
                        STATUS.message("ConnectException")),
                messages.stream().map(ProviderDiagnostic::message).toList());
    }

    @Test
    void newClientsDoNotInheritSuppressedWarnings() {
        var messages = new ArrayList<ProviderDiagnostic>();
        new ProviderLog(messages::add).failed(STATUS, "ConnectException");
        new ProviderLog(messages::add).failed(STATUS, "ConnectException");
        assertEquals(
                List.of(WARN, WARN), messages.stream().map(ProviderDiagnostic::level).toList());
    }

    @Test
    void theWarningSaysWhy() {
        var messages = new ArrayList<ProviderDiagnostic>();
        new ProviderLog(messages::add).failed(STATUS, "HttpConnectTimeoutException");
        new ProviderLog(messages::add).failed(ProviderLog.Operation.DRAIN, "ConnectException");
        assertEquals(
                List.of(
                        "Cannot update the signaling service: HttpConnectTimeoutException. Retrying automatically.",
                        "Could not tell the signaling service that the server is stopping: ConnectException."),
                messages.stream().map(ProviderDiagnostic::message).toList());
    }
}
