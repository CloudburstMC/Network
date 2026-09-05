package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoggingCompatibilityTest {
    @Test
    void slf4jAndNettyLogsReachTheConfiguredBackend() {
        String name = getClass().getName();
        Logger backend = Logger.getLogger(name);
        Level previousLevel = backend.getLevel();
        boolean previousParentHandlers = backend.getUseParentHandlers();
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        backend.setLevel(Level.ALL);
        backend.setUseParentHandlers(false);
        backend.addHandler(capture);
        try {
            LoggerFactory.getLogger(name).atInfo().addArgument("message").log("SLF4J {}");
            InternalLoggerFactory.getInstance(name).info("Netty {}", "message");
            assertEquals(List.of("SLF4J message", "Netty message"), messages);
        } finally {
            backend.removeHandler(capture);
            backend.setLevel(previousLevel);
            backend.setUseParentHandlers(previousParentHandlers);
        }
    }
}
