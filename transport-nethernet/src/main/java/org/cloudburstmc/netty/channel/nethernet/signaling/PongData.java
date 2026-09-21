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

import com.google.gson.JsonObject;

/**
 * Data structure for Pong advertisement data.
 *
 * @param serverName     The name of the server.
 * @param protocol       The Bedrock protocol version the server speaks.
 * @param version        The Bedrock version string the server reports.
 * @param levelName      The name of the level/world.
 * @param gameType       The game type (e.g. Survival, Creative).
 * @param playerCount    The current number of players.
 * @param maxPlayerCount The maximum number of players allowed.
 * @param isEditorWorld  Whether the world is an editor world.
 * @param isHardcore     Whether the world is in hardcore mode.
 * @param transportLayer The transport layer identifier (e.g. NetherNet).
 * @param connectionType The connection type identifier (e.g. LAN, Online).
 */
public record PongData(String serverName, int protocol, String version, String levelName, int gameType,
                       int playerCount, int maxPlayerCount, boolean isEditorWorld, boolean isHardcore,
                       int transportLayer,
                       int connectionType, int dataVersion, boolean onlineAuth, boolean selfSignedAuth,
                       String nonce) {

    public static final PongData DEFAULT = new Builder().build();

    /**
     * The document a client reads from {@code GET /v1/join}, in the order the schema lists.
     */
    public String toJson() {
        JsonObject info = new JsonObject();
        info.addProperty("dataVersion", dataVersion());
        info.addProperty("name", serverName());
        info.addProperty("protocol", protocol());
        info.addProperty("version", version());
        info.addProperty("level", levelName());
        info.addProperty("players", playerCount());
        info.addProperty("maxPlayers", maxPlayerCount());
        info.addProperty("gameType", gameType());
        info.addProperty("editor", isEditorWorld());
        info.addProperty("hardcore", isHardcore());
        info.addProperty("onlineAuth", onlineAuth());
        info.addProperty("selfSignedAuth", selfSignedAuth());
        info.addProperty("nonce", nonce());
        info.addProperty("transportLayer", transportLayer());
        info.addProperty("connection", connectionType());
        return info.toString();
    }

    /**
     * The defaults are placeholders, protocol 2187 and version 1.26.50 among them; a host
     * sets what it speaks.
     */
    public static class Builder {
        private String serverName = "Server";
        private int protocol = 2187;
        private String version = "1.26.50";
        private String levelName = "World";
        private int gameType = 0; // Default to Survival
        private int playerCount = 0;
        private int maxPlayerCount = 10;
        private boolean isEditorWorld = false;
        private boolean isHardcore = false;
        private int transportLayer = 2; // Default to NetherNet. 2 = NetherNet, 4 = RakNet
        private int connectionType = 4; // Default to LANWebRTCSignaling
        private int dataVersion = 7;
        private boolean onlineAuth = true;
        private boolean selfSignedAuth = false;
        // Random per host, as a dedicated server does
        private String nonce = String.format("%016x", new java.security.SecureRandom().nextLong());

        public Builder setServerName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder setProtocol(int protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder setVersion(String version) {
            this.version = version;
            return this;
        }

        public Builder setLevelName(String levelName) {
            this.levelName = levelName;
            return this;
        }

        public Builder setGameType(int gameType) {
            this.gameType = gameType;
            return this;
        }

        public Builder setPlayerCount(int playerCount) {
            this.playerCount = playerCount;
            return this;
        }

        public Builder setMaxPlayerCount(int maxPlayerCount) {
            this.maxPlayerCount = maxPlayerCount;
            return this;
        }

        public Builder setIsEditorWorld(boolean isEditorWorld) {
            this.isEditorWorld = isEditorWorld;
            return this;
        }

        public Builder setIsHardcore(boolean isHardcore) {
            this.isHardcore = isHardcore;
            return this;
        }

        public Builder setTransportLayer(int transportLayer) {
            this.transportLayer = transportLayer;
            return this;
        }

        public Builder setConnectionType(int connectionType) {
            this.connectionType = connectionType;
            return this;
        }

        public Builder setDataVersion(int dataVersion) {
            this.dataVersion = dataVersion;
            return this;
        }

        /**
         * Whether the host authenticates players against the auth service. A host that does not
         * should also set {@link #setSelfSignedAuth}.
         */
        public Builder setOnlineAuth(boolean onlineAuth) {
            this.onlineAuth = onlineAuth;
            return this;
        }

        public Builder setSelfSignedAuth(boolean selfSignedAuth) {
            this.selfSignedAuth = selfSignedAuth;
            return this;
        }

        public Builder setNonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        public PongData build() {
            return new PongData(serverName, protocol, version, levelName, gameType, playerCount,
                    maxPlayerCount, isEditorWorld, isHardcore, transportLayer, connectionType,
                    dataVersion, onlineAuth, selfSignedAuth, nonce);
        }
    }
}
