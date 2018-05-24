package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.packet.OpenConnectionRequest1Packet;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.net.InetSocketAddress;
import java.util.StringJoiner;

public interface RakNetEventListener {

    Action onConnectionRequest(InetSocketAddress address, OpenConnectionRequest1Packet packet);

    Advertisement onQuery(InetSocketAddress address);

    enum Action {
        CONTINUE,
        INCOMPATIBLE_VERSION,
        NO_INCOMING_CONNECTIONS,
        BANNED,
    }

    @RequiredArgsConstructor
    @Getter
    class Advertisement {
        private final String game;
        private final String motd;
        private final int protocolVersion;
        private final String version;
        private final int playerCount;
        private final int maximumPlayerCount;
        private final String subMotd;
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
