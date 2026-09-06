package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.LibDataChannel;
import java.util.Locale;

/** Controls native logging before messages cross into Java, and configures supported Java backends. */
public final class NetherNetLogging {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetLogging.class);

    /** The SLF4J logger libdatachannel routes its native output through. */
    public static final String NATIVE_LOGGER = "tel.schich.libdatachannel.LibDataChannel";

    private NetherNetLogging() {
    }

    /**
     * Sets the native threshold and, when available, the Log4j2 or Logback logger level.
     *
     * @param level One of OFF, ERROR, WARN, INFO, DEBUG, TRACE or ALL. WARN is a good default.
     * @return true if the native threshold was set, false if the level was invalid.
     */
    public static boolean setNativeLogLevel(String level) {
        if (level == null || level.isBlank()) {
            return false;
        }

        String normalised = level.trim().toUpperCase(Locale.ROOT);
        LibDataChannel.LogLevel nativeLevel = switch (normalised) {
            case "OFF" -> LibDataChannel.LogLevel.NONE;
            case "ERROR" -> LibDataChannel.LogLevel.ERROR;
            case "WARN" -> LibDataChannel.LogLevel.WARNING;
            case "INFO" -> LibDataChannel.LogLevel.INFO;
            case "DEBUG" -> LibDataChannel.LogLevel.DEBUG;
            case "TRACE", "ALL" -> LibDataChannel.LogLevel.VERBOSE;
            default -> null;
        };
        if (nativeLevel == null) return false;
        LibDataChannel.setLogLevel(nativeLevel);
        if (!applyLog4j2(normalised)) applyLogback(normalised);
        log.debug("Set native transport log level to {}", normalised);
        return true;
    }

    private static boolean applyLog4j2(String level) {
        try {
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");

            Object parsed = levelClass.getMethod("toLevel", String.class, levelClass)
                    .invoke(null, level, levelClass.getField("WARN").get(null));

            configurator.getMethod("setLevel", String.class, levelClass)
                    .invoke(null, NATIVE_LOGGER, parsed);
            return true;
        } catch (Throwable t) {
            // Not on Log4j2, or it resolved a different logger context than ours
            return false;
        }
    }

    private static boolean applyLogback(String level) {
        try {
            Object logger = Class.forName("org.slf4j.LoggerFactory")
                    .getMethod("getLogger", String.class)
                    .invoke(null, NATIVE_LOGGER);

            Class<?> logbackLogger = Class.forName("ch.qos.logback.classic.Logger");
            if (!logbackLogger.isInstance(logger)) {
                return false;
            }

            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Object parsed = levelClass.getMethod("toLevel", String.class).invoke(null, level);

            logbackLogger.getMethod("setLevel", levelClass).invoke(logger, parsed);
            return true;
        } catch (Throwable t) {
            // Not on Logback
            return false;
        }
    }
}
