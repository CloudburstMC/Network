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

package org.cloudburstmc.netty.signaling;

import java.util.List;

/**
 * Complete atomic snapshot. Advertised maxPlayers is independent of routing capacity.
 */
public record ServerStatus(String name, String level, Integer players, int maxPlayers, int gameType) {
    /** Use the heartbeat's actual player count for the listing. */
    public ServerStatus(String name, String level, int maxPlayers, int gameType) {
        this(name, level, null, maxPlayers, gameType);
    }

    /** Temporary source adapter; advertised protocol and version belong to the provider. */
    @Deprecated(forRemoval = true)
    public ServerStatus(String name, int protocol, String version, String level, int players, int maxPlayers, int gameType) {
        this(name, level, players, maxPlayers, gameType);
    }

    public ServerStatus {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 128
                || level == null || level.codePointCount(0, level.length()) > 128
                || players != null && (players < 0 || players > 1_000_000)
                || maxPlayers < 0 || maxPlayers > 1_000_000 || gameType < 0 || gameType > 2) {
            throw new IllegalArgumentException("Invalid complete server status snapshot");
        }
        for (String value : List.of(name, level)) {
            if (value.codePoints().anyMatch(
                    c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.SURROGATE)) {
                throw new IllegalArgumentException("Invalid status text");
            }
        }
    }
}
