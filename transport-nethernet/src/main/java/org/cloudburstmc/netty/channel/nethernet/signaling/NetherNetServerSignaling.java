package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetServerStatus;
import org.cloudburstmc.netty.util.nethernet.ClientIdentity;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Objects;

public interface NetherNetServerSignaling extends NetherNetSignaling {
    /**
     * Binds the signaling medium to listen for incoming connections (Server mode).
     *
     * @param localAddress The local address to bind to.
     * @throws ConnectException
     */
    void bind(SocketAddress localAddress) throws ConnectException;

    /**
     * Handler for new connections.
     *
     * @param handler Functional interface receiving (ConnectionID, RemoteNetworkID, Payload)
     */
    void setNewConnectionHandler(NewConnectionHandler handler);

    /**
     * Sets the advertisement data for the discovery mechanism (e.g. LAN Pong).
     *
     * @param pongData The Pong advertisement data.
     */
    void setAdvertisementData(PongData pongData);

    /**
     * Functional interface for new connection handling.
     */
    @FunctionalInterface
    interface NewConnectionHandler {
        /**
         * Called when a new connection is initiated by a remote peer.
         *
         * @param connectionId     The unique connection ID for this session.
         * @param remoteNetworkId  The Network ID of the remote peer.
         * @param payload          The initial signaling payload from the remote peer.
         */
        void onConnect(long connectionId, String remoteNetworkId, String payload);
    }

    /**
     * Returns the ICE servers (STUN/TURN) obtained from the signaling handshake.
     * Returns empty list if none available or not applicable.
     */
    default List<IceServerInfo> getIceServers() {
        return java.util.Collections.emptyList();
    }

    /**
     * Whether connections offered by this signaling require full ICE answers:
     * a single answer containing every gathered candidate, with no trickle
     * candidate signals in either direction. Request/response signaling
     * (HTTP) returns true because the whole SDP exchange must fit one round
     * trip; message based signaling keeps the default trickle behavior.
     */
    default boolean fullIceAnswers() {
        return false;
    }

    /**
     * The remote peer's transport address for an in flight connection, when
     * the signaling medium knows it (an HTTP front end sees the request's
     * source address). Null when unknown; the channel then uses a placeholder
     * until ICE nominates a candidate pair.
     */
    default InetSocketAddress remoteAddressOf(long connectionId) {
        return null;
    }

    /** Returns the validated offer identity, or null when this signaling path did not validate one. */
    default ClientIdentity clientIdentityOf(long connectionId) { return null; }

    /** Whether a request/response exchange is still awaiting negotiation; message-based signaling defaults to true. */
    default boolean isConnectionPending(long connectionId) { return true; }

    /**
     * LAN v6 advertisement fields. Authentication flags describe the consumer's
     * admission policy; setting them does not enable authentication or nonce checks.
     *
     * @param serverName      The name of the server.
     * @param levelName       The name of the level/world.
     * @param gameType        The game type (e.g. Survival, Creative).
     * @param playerCount     The current number of players.
     * @param maxPlayerCount  The maximum number of players allowed.
     * @param isEditorWorld   Whether the world is an editor world.
     * @param isHardcore      Whether the world is in hardcore mode.
     * @param transportLayer  The transport layer identifier (e.g. NetherNet).
     * @param connectionType  The connection type identifier (e.g. LAN, Online).
     * @param acceptsOnlineAuth whether online-authenticated players may join
     * @param acceptsSelfSignedAuth whether self-signed identities may join
     * @param nonce the host's nonce, which clients echo in their Login data
     */
    public record PongData(String serverName, String levelName, int gameType, int playerCount, int maxPlayerCount,
            boolean isEditorWorld, boolean isHardcore, int transportLayer, int connectionType,
            boolean acceptsOnlineAuth, boolean acceptsSelfSignedAuth, String nonce) {
        private static final String DEFAULT_NONCE = NetherNetServerStatus.randomNonce();

        public PongData {
            Objects.requireNonNull(serverName, "serverName");
            Objects.requireNonNull(levelName, "levelName");
            Objects.requireNonNull(nonce, "nonce");
        }

        /**
         * Preserves the original constructor. Both identity types are advertised
         * as accepted, and the default nonce is generated once per process.
         */
        public PongData(String serverName, String levelName, int gameType, int playerCount, int maxPlayerCount,
                        boolean isEditorWorld, boolean isHardcore, int transportLayer, int connectionType) {
            this(serverName, levelName, gameType, playerCount, maxPlayerCount, isEditorWorld, isHardcore,
                    transportLayer, connectionType, true, true, DEFAULT_NONCE);
        }

        public static class Builder {
            private String serverName = "Server";
            private String levelName = "World";
            private int gameType = 0; // Default to Survival
            private int playerCount = 0;
            private int maxPlayerCount = 10;
            private boolean isEditorWorld = false;
            private boolean isHardcore = false;
            private int transportLayer = 2; // Default to NetherNet
            private int connectionType = 4; // Default to LAN
            private boolean acceptsOnlineAuth = true;
            private boolean acceptsSelfSignedAuth = true;
            private String nonce = DEFAULT_NONCE;

            public Builder setServerName(String serverName) {
                this.serverName = serverName;
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

            public Builder setAcceptsOnlineAuth(boolean acceptsOnlineAuth) {
                this.acceptsOnlineAuth = acceptsOnlineAuth;
                return this;
            }

            public Builder setAcceptsSelfSignedAuth(boolean acceptsSelfSignedAuth) {
                this.acceptsSelfSignedAuth = acceptsSelfSignedAuth;
                return this;
            }

            /** Supply the consumer's shared nonce when advertising through multiple endpoints. */
            public Builder setNonce(String nonce) {
                this.nonce = Objects.requireNonNull(nonce, "nonce");
                return this;
            }

            public PongData build() {
                return new PongData(serverName, levelName, gameType, playerCount, maxPlayerCount,
                    isEditorWorld, isHardcore, transportLayer, connectionType,
                    acceptsOnlineAuth, acceptsSelfSignedAuth, nonce);
            }
        }
    }
}
