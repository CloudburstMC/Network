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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

/** Reads {@code host}, {@code host:port} and {@code [IPv6]:port} without looking anything up. */
final class HostPort {
    private HostPort() {
    }

    /**
     * @param value       The text to read
     * @param defaultPort The port when the text names none
     * @return The host and port, unresolved, or null for anything else, such as userinfo, a path or
     *         an unbracketed IPv6 address
     */
    static @Nullable InetSocketAddress parse(String value, int defaultPort) {
        String host = value;
        String port = null;
        boolean bracketed = value.startsWith("[");
        if (bracketed) {
            int close = value.indexOf(']');
            if (close < 0 || (close + 1 < value.length() && value.charAt(close + 1) != ':')) {
                return null;
            }
            host = value.substring(1, close);
            port = close + 1 < value.length() ? value.substring(close + 2) : null;
        } else {
            int colon = value.indexOf(':');
            if (colon >= 0) {
                host = value.substring(0, colon);
                port = value.substring(colon + 1);
            }
        }
        if (host.isEmpty() || host.length() > 255 || !host.matches(bracketed ? "[0-9a-fA-F:.]+" : "[A-Za-z0-9.-]+")) {
            return null;
        }
        if (port == null) {
            return InetSocketAddress.createUnresolved(host, defaultPort);
        }
        if (!port.matches("[0-9]{1,5}")) {
            return null;
        }
        int number = Integer.parseInt(port);
        return number < 1 || number > 65535 ? null : InetSocketAddress.createUnresolved(host, number);
    }
}
