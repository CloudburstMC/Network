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
    void aLongFailureWarnsAgainEveryHalfHour() {
        var messages = new ArrayList<ProviderDiagnostic>();
        long[] now = {0};
        var log = new ProviderLog(messages::add, () -> now[0]);
        log.failed(STATUS, "ConnectException");
        now[0] = ProviderLog.REMINDER_NANOS - 1;
        log.failed(STATUS, "ConnectException");
        now[0] = ProviderLog.REMINDER_NANOS + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        log.failed(STATUS, "ConnectException");
        log.failed(STATUS, "ConnectException");
        log.recovered(STATUS);
        assertEquals(
                List.of(WARN, DEBUG, WARN, DEBUG, INFO), messages.stream().map(ProviderDiagnostic::level).toList());
        assertEquals(STATUS.message("ConnectException") + " Failing for 35 minutes.", messages.get(2).message());
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
