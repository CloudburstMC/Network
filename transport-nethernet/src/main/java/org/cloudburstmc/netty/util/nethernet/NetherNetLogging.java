package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Controls how much of libdatachannel's own logging reaches your logs.
 * <p>
 * The native logger runs at its most verbose level and the binding maps that straight onto SLF4J, so a
 * connection emits a couple of dozen lines at INFO about ICE, DTLS and SCTP internals. Neither is
 * configurable, leaving the level of {@value #NATIVE_LOGGER} as the only place to filter.
 */
public final class NetherNetLogging {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetLogging.class);

    /** The SLF4J logger libdatachannel routes its native output through. */
    public static final String NATIVE_LOGGER = "tel.schich.libdatachannel.LibDataChannel";

    private NetherNetLogging() {
    }

    /**
     * Sets the level of the libdatachannel logger. SLF4J has no level API, so this is applied through
     * Log4j2 or Logback; with any other backend it does nothing and you should configure it yourself.
     *
     * @param level One of OFF, ERROR, WARN, INFO, DEBUG, TRACE or ALL. WARN is a good default.
     * @return true if the level was applied, false if the backend was not recognised.
     */
    public static boolean setNativeLogLevel(String level) {
        if (level == null || level.isBlank()) {
            return false;
        }

        String normalised = level.trim().toUpperCase();

        if (applyLog4j2(normalised) || applyLogback(normalised)) {
            log.debug("Set {} to {}", NATIVE_LOGGER, normalised);
            return true;
        }

        log.debug("Could not set {} to {}, no supported logging backend found", NATIVE_LOGGER, normalised);
        return false;
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
