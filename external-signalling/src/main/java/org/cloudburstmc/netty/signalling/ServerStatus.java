package org.cloudburstmc.netty.signalling;

/** Complete atomic snapshot. Advertised maxPlayers is independent of routing capacity. */
public record ServerStatus(String name, int protocol, String version, String level, int players, int maxPlayers, int gameType) {
    public ServerStatus {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 128 || version == null || version.isEmpty() || version.length() > 64 ||
                level == null || level.codePointCount(0, level.length()) > 128 || protocol < 1 || players < 0 || players > 1_000_000 || maxPlayers < 0 || maxPlayers > 1_000_000 || gameType < 0 || gameType > 2)
            throw new IllegalArgumentException("Invalid complete server status snapshot");
        for (String value : java.util.List.of(name, version, level)) if (value.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.SURROGATE)) throw new IllegalArgumentException("Invalid status text");
    }
}
