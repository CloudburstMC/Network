package org.cloudburstmc.netty.channel.nethernet;

import com.google.gson.JsonObject;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The server information answered on {@code GET /v1/join}: the NetherNet era
 * equivalent of RakNet's unconnected pong, and a mirror of vanilla's
 * {@code NetherNetServerLocator::ServerData}.
 *
 * The endpoint was a pure capability check until vanilla 1.26.50 started
 * answering it with this document. Older clients ignore the response body, as
 * the NetherNet onboarding guide still specifies, so sending one is safe for
 * every existing client.
 *
 * The schema has fourteen members, of which vanilla emits seven: every member
 * carries a serialization context tag, and the document built for this
 * endpoint selects one context, so the other seven are populated and then
 * dropped by that filter. The seven that survive happen to be a one to one
 * match with the legacy RakNet pong, minus the fields NetherNet makes
 * meaningless (the edition literal, the server GUID, and the two ports).
 *
 * That split is one build's behaviour, observed on the first server version to
 * answer this endpoint at all and before any client read the payload, so this
 * type does not encode it. Every member is optional and independent: setting
 * one emits it, leaving it unset omits it, and the caller decides what to
 * describe. Optionality also keeps an explicit {@code false} distinguishable
 * from an absent flag, which vanilla relies on for the extended booleans.
 *
 * Members are emitted in schema registration order, so populating exactly the
 * seven vanilla sends reproduces its response byte identically.
 *
 * Instances are immutable; build one with {@link #builder()}.
 */
public final class NetherNetServerStatus {

    /** {@code Social::ConnectionType.LANWebRTCSignaling}, the value a NetherNet dedicated server reports. */
    public static final int CONNECTION_LAN_WEBRTC_SIGNALING = 4;

    /** {@code GameType} values, as carried by the {@code gameType} member. */
    public static final int GAME_TYPE_SURVIVAL = 0;
    public static final int GAME_TYPE_CREATIVE = 1;
    public static final int GAME_TYPE_ADVENTURE = 2;

    // Registration order. The seven vanilla currently emits are name through
    // gameType; the rest are populated but filtered out of its response.
    private final Integer dataVersion;
    private final String name;
    private final Integer protocol;
    private final String version;
    private final String level;
    private final Integer players;
    private final Integer maxPlayers;
    private final Integer gameType;
    private final Boolean editor;
    private final Boolean hardcore;
    private final Boolean onlineAuth;
    private final Boolean selfSignedAuth;
    private final String nonce;
    private final Integer connection;

    private NetherNetServerStatus(Builder builder) {
        this.dataVersion = builder.dataVersion;
        this.name = builder.name;
        this.protocol = builder.protocol;
        this.version = builder.version;
        this.level = builder.level;
        this.players = builder.players;
        this.maxPlayers = builder.maxPlayers;
        this.gameType = builder.gameType;
        this.editor = builder.editor;
        this.hardcore = builder.hardcore;
        this.onlineAuth = builder.onlineAuth;
        this.selfSignedAuth = builder.selfSignedAuth;
        this.nonce = builder.nonce;
        this.connection = builder.connection;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A fresh value for the {@code nonce} member: a uint64 rendered as
     * unpadded lowercase hexadecimal, so one to sixteen characters. Vanilla
     * generates one per process start rather than per request.
     */
    public static String randomNonce() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    /**
     * Serializes this status as the endpoint's JSON document: schema
     * registration order, unset members omitted.
     */
    public String toJson() {
        JsonObject json = new JsonObject();
        if (dataVersion != null) {
            json.addProperty("dataVersion", dataVersion);
        }
        if (name != null) {
            json.addProperty("name", name);
        }
        if (protocol != null) {
            json.addProperty("protocol", protocol);
        }
        if (version != null) {
            json.addProperty("version", version);
        }
        if (level != null) {
            json.addProperty("level", level);
        }
        if (players != null) {
            json.addProperty("players", players);
        }
        if (maxPlayers != null) {
            json.addProperty("maxPlayers", maxPlayers);
        }
        if (gameType != null) {
            json.addProperty("gameType", gameType);
        }
        if (editor != null) {
            json.addProperty("editor", editor);
        }
        if (hardcore != null) {
            json.addProperty("hardcore", hardcore);
        }
        if (onlineAuth != null) {
            json.addProperty("onlineAuth", onlineAuth);
        }
        if (selfSignedAuth != null) {
            json.addProperty("selfSignedAuth", selfSignedAuth);
        }
        if (nonce != null) {
            json.addProperty("nonce", nonce);
        }
        if (connection != null) {
            json.addProperty("connection", connection);
        }
        return json.toString();
    }

    public Integer dataVersion() {
        return dataVersion;
    }

    public String name() {
        return name;
    }

    public Integer protocol() {
        return protocol;
    }

    public String version() {
        return version;
    }

    public String level() {
        return level;
    }

    public Integer players() {
        return players;
    }

    public Integer maxPlayers() {
        return maxPlayers;
    }

    public Integer gameType() {
        return gameType;
    }

    public Boolean editor() {
        return editor;
    }

    public Boolean hardcore() {
        return hardcore;
    }

    public Boolean onlineAuth() {
        return onlineAuth;
    }

    public Boolean selfSignedAuth() {
        return selfSignedAuth;
    }

    public String nonce() {
        return nonce;
    }

    public Integer connection() {
        return connection;
    }

    /**
     * Builds a {@link NetherNetServerStatus}. Every member defaults to unset,
     * and an unset member is omitted from the emitted document.
     */
    public static final class Builder {

        private Integer dataVersion;
        private String name;
        private Integer protocol;
        private String version;
        private String level;
        private Integer players;
        private Integer maxPlayers;
        private Integer gameType;
        private Boolean editor;
        private Boolean hardcore;
        private Boolean onlineAuth;
        private Boolean selfSignedAuth;
        private String nonce;
        private Integer connection;

        private Builder() {
        }

        /**
         * Schema version the document claims conformance to. An assertion
         * about the payload rather than a description of the server.
         */
        public Builder dataVersion(Integer dataVersion) {
            this.dataVersion = dataVersion;
            return this;
        }

        /** Server display name; the RakNet pong's MOTD. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /** Bedrock protocol version. */
        public Builder protocol(Integer protocol) {
            this.protocol = protocol;
            return this;
        }

        /** Bedrock version string, {@code major.minor.patch}. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** World display name; the RakNet pong's level name. */
        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder players(Integer players) {
            this.players = players;
            return this;
        }

        public Builder maxPlayers(Integer maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        /** One of the {@code GAME_TYPE_} constants. */
        public Builder gameType(Integer gameType) {
            this.gameType = gameType;
            return this;
        }

        /**
         * Whether the world is an Editor project, which is not the same thing
         * as the server having been launched in editor mode.
         */
        public Builder editor(Boolean editor) {
            this.editor = editor;
            return this;
        }

        /** Whether the world is hardcore; vanilla reads it from the level's own flag. */
        public Builder hardcore(Boolean hardcore) {
            this.hardcore = hardcore;
            return this;
        }

        /** Whether the server accepts Microsoft authenticated identities. */
        public Builder onlineAuth(Boolean onlineAuth) {
            this.onlineAuth = onlineAuth;
            return this;
        }

        /**
         * Whether the server additionally accepts self signed identities. In
         * vanilla this is online mode inverted, and is independent of
         * {@link #onlineAuth(Boolean)} rather than its opposite.
         */
        public Builder selfSignedAuth(Boolean selfSignedAuth) {
            this.selfSignedAuth = selfSignedAuth;
            return this;
        }

        /** See {@link #randomNonce()}; must not be zero padded. */
        public Builder nonce(String nonce) {
            this.nonce = nonce;
            return this;
        }

        /** {@code Social::ConnectionType}; see {@link #CONNECTION_LAN_WEBRTC_SIGNALING}. */
        public Builder connection(Integer connection) {
            this.connection = connection;
            return this;
        }

        public NetherNetServerStatus build() {
            return new NetherNetServerStatus(this);
        }
    }
}
