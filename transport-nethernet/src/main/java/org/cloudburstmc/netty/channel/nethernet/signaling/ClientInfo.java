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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * What a client tells the server about itself in the query of its signaling requests. The server
 * reads it from both the status check and the join, and passes null for a request that carries none
 * or a malformed one. The client reports it unchecked, so it is a hint rather than anything to trust.
 *
 * @param version  The game version, such as {@code 1.26.60.29}
 * @param protocol The network protocol version
 * @param platform The <a href="https://mojang.github.io/bedrock-protocol-docs/latest/types/build-platform/">build
 *                 platform</a>, such as {@code 8} for Win32
 */
public record ClientInfo(String version, int protocol, int platform) {

    /** Room for {@code 1.100.100.100} and then some. */
    static final int MAX_VERSION_LENGTH = 16;

    /** Adds this to a status check's query. */
    void addTo(StringJoiner query) {
        query.add("version=" + URLEncoder.encode(this.version, StandardCharsets.UTF_8));
        query.add("protocol=" + this.protocol);
        query.add("platform=" + this.platform);
    }

    /**
     * Reads it back from a request's query, or null when any of it is missing or malformed. A
     * version longer than {@link #MAX_VERSION_LENGTH} or other than digits and dots is malformed.
     */
    static @Nullable ClientInfo fromQuery(Map<String, List<String>> query) {
        String version = parameter(query, "version");
        String protocol = parameter(query, "protocol");
        String platform = parameter(query, "platform");
        if (version == null || protocol == null || platform == null || !isVersion(version)) {
            return null;
        }
        try {
            return new ClientInfo(version, Integer.parseInt(protocol), Integer.parseInt(platform));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable String parameter(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static boolean isVersion(String version) {
        if (version.isEmpty() || version.length() > MAX_VERSION_LENGTH) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }
}
