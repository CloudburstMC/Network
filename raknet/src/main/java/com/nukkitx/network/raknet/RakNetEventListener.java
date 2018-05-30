package com.nukkitx.network.raknet;

import lombok.NonNull;
import lombok.Value;

import java.net.InetSocketAddress;
import java.util.StringJoiner;

public interface RakNetEventListener {

    Action onConnectionRequest(InetSocketAddress address);

    Advertisement onQuery(InetSocketAddress address);

    enum Action {
        CONTINUE,
        NO_INCOMING_CONNECTIONS,
        BANNED,
    }

    @Value
    class Advertisement {
        @NonNull
        private final String game;
        @NonNull
        private final String motd;
        private final int protocolVersion;
        @NonNull
        private final String version;
        private final int playerCount;
        private final int maximumPlayerCount;
        @NonNull
        private final String subMotd;
        @NonNull
        private final String gamemode;

        public String getAdvertisment(RakNetServer server) {
            StringJoiner joiner = new StringJoiner(";")
                    .add(game)
                    .add(motd)
                    .add(Integer.toString(protocolVersion))
                    .add(version)
                    .add(Integer.toString(playerCount))
                    .add(Integer.toString(maximumPlayerCount))
                    .add(Long.toString(server.getId()))
                    .add(subMotd)
                    .add(gamemode);
            return joiner.toString();
        }
    }
}
