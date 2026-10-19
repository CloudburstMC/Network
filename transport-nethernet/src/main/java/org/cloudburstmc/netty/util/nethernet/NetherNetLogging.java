/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import tel.schich.libdatachannel.LibDataChannel;

import java.util.List;
import java.util.Locale;

/**
 * Controls native logging before messages cross into Java, and configures supported Java backends.
 */
public final class NetherNetLogging {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetLogging.class);

    /**
     * The SLF4J logger libdatachannel routes its native output through.
     */
    public static final String NATIVE_LOGGER = "tel.schich.libdatachannel.LibDataChannel";

    /** NetherNet's own loggers: the channels, their utilities, and external signaling. */
    private static final List<String> JAVA_LOGGERS = javaLoggers();

    private NetherNetLogging() {
    }

    /** Derived from this class, so a relocated build still names its own packages. */
    static List<String> javaLoggers() {
        String util = NetherNetLogging.class.getPackageName();
        String base = util.substring(0, util.length() - ".util.nethernet".length());
        return List.of(base + ".channel.nethernet", util, base + ".signaling");
    }

    /**
     * Sets the native threshold, as {@link #setNativeLogLevel} does, and NetherNet's own loggers to
     * the same level, on Log4j2 or Logback. DEBUG then also shows what signaling, STUN discovery and
     * the channels do, which an operator can turn on to send a log.
     *
     * @param level One of OFF, ERROR, WARN, INFO, DEBUG, TRACE or ALL
     * @return true if the native threshold was set, false if the level was invalid.
     */
    public static boolean setLogLevel(String level) {
        if (!setNativeLogLevel(level)) {
            return false;
        }
        String normalised = level.trim().toUpperCase(Locale.ROOT);
        boolean applied = false;
        for (String logger : JAVA_LOGGERS) {
            applied |= applyLog4j2(logger, normalised) || applyLogback(logger, normalised);
        }
        if (!applied) {
            log.info("NetherNet's own logs keep the platform's level, as its logging is neither Log4j2 nor Logback");
        }
        return true;
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
        if (nativeLevel == null) {
            return false;
        }
        LibDataChannel.setLogLevel(nativeLevel);
        if (!applyLog4j2(NATIVE_LOGGER, normalised)) {
            applyLogback(NATIVE_LOGGER, normalised);
        }
        log.debug("Set native transport log level to {}", normalised);
        return true;
    }

    private static boolean applyLog4j2(String logger, String level) {
        try {
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Class<?> configurator = Class.forName("org.apache.logging.log4j.core.config.Configurator");

            Object parsed = levelClass.getMethod("toLevel", String.class, levelClass)
                    .invoke(null, level, levelClass.getField("WARN").get(null));

            configurator.getMethod("setLevel", String.class, levelClass)
                    .invoke(null, logger, parsed);
            return true;
        } catch (Throwable t) {
            // Not on Log4j2, or it resolved a different logger context than ours
            return false;
        }
    }

    private static boolean applyLogback(String name, String level) {
        try {
            Object logger = Class.forName("org.slf4j.LoggerFactory")
                    .getMethod("getLogger", String.class)
                    .invoke(null, name);

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
