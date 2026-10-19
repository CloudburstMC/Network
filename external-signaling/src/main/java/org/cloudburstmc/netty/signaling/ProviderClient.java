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

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.admission.AdmissionKey;
import org.cloudburstmc.netty.signaling.diagnostic.*;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One asynchronous, serialized control lifecycle per backend, never one poller per player.
 */
public final class ProviderClient implements AutoCloseable {
    private static final String NEW_SERVICE = "new-service";
    private static final String ATTACH_INSTANCE = "attach-instance";
    private static final String ANONYMOUS_PROOF_OF_WORK = "anonymous-proof-of-work";
    private static final String BEARER_TOKEN = "bearer-token";
    private static final String RECOVERY_REQUIRED = "recovery_required";
    private static final String GENERATION_FENCED = "generation_fenced";

    public enum ControlTransport {
        HTTP,
        AUTO
    }

    private static final String CONNECTIVITY_EXTENSION = "dev.opencollab.nxs.connectivity";
    private static final long DIAGNOSTIC_VALIDITY_MILLIS = 300000;

    /**
     * What the client registers and runs with. The bearer token alone decides what the provider
     * registers it as.
     */
    public static final class Configuration {
        private final URI provider;
        private final String label;
        private final String authorizationToken;
        private final ControlTransport controlTransport;
        private final boolean diagnosticAdmission;
        private final String connectivityMethod;
        private final boolean assistedJoins;
        private final boolean deregisterOnShutdown;
        private final boolean gameOutcomes;
        private final Location location;

        private Configuration(Builder builder) {
            provider = Objects.requireNonNull(builder.provider);
            label = builder.label;
            authorizationToken = builder.authorizationToken;
            controlTransport = builder.controlTransport;
            diagnosticAdmission = builder.diagnosticAdmission;
            connectivityMethod = builder.connectivityMethod;
            assistedJoins = builder.assistedJoins;
            deregisterOnShutdown = builder.deregisterOnShutdown;
            gameOutcomes = builder.gameOutcomes;
            location = builder.location;
            if (assistedJoins && controlTransport != ControlTransport.AUTO) {
                throw new IllegalArgumentException("Assisted joins require WebSocket control");
            }
            if (!Set.of("defined", "discovered").contains(connectivityMethod)) {
                throw new IllegalArgumentException("Unknown connectivity method");
            }
            if (diagnosticAdmission && !"https".equals(provider.getScheme())) {
                throw new IllegalArgumentException("Diagnostics require HTTPS");
            }
            ProviderOrigin.canonicalize(provider);
            if (label != null && label.codePointCount(0, label.length()) > 128) {
                throw new IllegalArgumentException("Provider label exceeds 128 characters");
            }
            if (authorizationToken != null && authorizationToken.isBlank()) {
                throw new IllegalArgumentException("Blank bearer token");
            }
        }

        public static Builder builder(URI provider) {
            return new Builder(provider);
        }

        public URI provider() {
            return provider;
        }

        public String label() {
            return label;
        }

        public String authorizationToken() {
            return authorizationToken;
        }

        public ControlTransport controlTransport() {
            return controlTransport;
        }

        public boolean diagnosticAdmission() {
            return diagnosticAdmission;
        }

        public String connectivityMethod() {
            return connectivityMethod;
        }

        public boolean assistedJoins() {
            return assistedJoins;
        }

        public boolean deregisterOnShutdown() {
            return deregisterOnShutdown;
        }

        public boolean gameOutcomes() {
            return gameOutcomes;
        }

        public Location location() {
            return location;
        }

        String authorizationScheme() {
            return authorizationToken == null ? ANONYMOUS_PROOF_OF_WORK : BEARER_TOKEN;
        }

        @Override
        public String toString() {
            return "Configuration[provider=" + provider + ", authorizationScheme=" + authorizationScheme() + "]";
        }

        public static final class Builder {
            private final URI provider;
            private String label;
            private String authorizationToken;
            private ControlTransport controlTransport = ControlTransport.HTTP;
            private boolean diagnosticAdmission;
            private String connectivityMethod = "discovered";
            private boolean assistedJoins;
            private boolean deregisterOnShutdown;
            private boolean gameOutcomes;
            private Location location;

            private Builder(URI provider) {
                this.provider = provider;
            }

            /**
             * @param label The instance's initial name at the provider
             */
            public Builder label(String label) {
                this.label = label;
                return this;
            }

            /**
             * @param authorizationToken A bearer token, or null to register anonymously with proof of work
             */
            public Builder authorizationToken(String authorizationToken) {
                this.authorizationToken = authorizationToken;
                return this;
            }

            public Builder controlTransport(ControlTransport controlTransport) {
                this.controlTransport = Objects.requireNonNull(controlTransport);
                return this;
            }

            public Builder diagnosticAdmission(boolean diagnosticAdmission) {
                this.diagnosticAdmission = diagnosticAdmission;
                return this;
            }

            /**
             * @param connectivityMethod {@code defined} for configured endpoints, {@code discovered} otherwise
             */
            public Builder connectivityMethod(String connectivityMethod) {
                this.connectivityMethod = connectivityMethod;
                return this;
            }

            public Builder assistedJoins(boolean assistedJoins) {
                this.assistedJoins = assistedJoins;
                return this;
            }

            public Builder deregisterOnShutdown(boolean deregisterOnShutdown) {
                this.deregisterOnShutdown = deregisterOnShutdown;
                return this;
            }

            /**
             * Declares that the application reports every admitted player's join or rejection through
             * {@link ProviderClient#reportGameOutcome}.
             */
            public Builder gameOutcomes(boolean gameOutcomes) {
                this.gameOutcomes = gameOutcomes;
                return this;
            }

            /**
             * @param location Where the operator says the host runs, or null to let the provider estimate it
             */
            public Builder location(Location location) {
                this.location = location;
                return this;
            }

            public Configuration build() {
                return new Configuration(this);
            }
        }
    }

    /**
     * The assigned runtime identity. {@code serviceId} and {@code publicAddress} are both present or both null.
     */
    public record Registration(String registrationId, String instanceId, String keyId, long leaseGeneration,
                               String serviceId, String publicAddress) {
    }

    /** The game's verdict on an admitted player. */
    public enum GameOutcome {
        JOINED("ticket.game_joined"),
        REJECTED("ticket.game_rejected");

        private final String stage;

        GameOutcome(String stage) {
            this.stage = stage;
        }
    }

    /**
     * Sampled actual players on this runtime; keep counting existing players while not accepting new ones.
     */
    public record ConnectedPlayers(int count, long sampledAt) {
        public ConnectedPlayers {
            if (count < 0 || count > 1000000 || sampledAt < 0 || sampledAt > 9007199254740991L) {
                throw new IllegalArgumentException("Invalid player count sample");
            }
        }
    }

    /**
     * The host software and its version, as the provider should show them.
     */
    public record Software(String name, String version) {
        public Software {
            if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 64 || version == null
                    || version.isBlank() || version.codePointCount(0, version.length()) > 128) {
                throw new IllegalArgumentException("Invalid host software");
            }
        }
    }

    /**
     * Where the operator says the host runs: a country, coordinates or both. Coordinates come together.
     */
    public record Location(String country, String city, Double latitude, Double longitude) {
        public Location {
            if (country != null && !country.matches("[A-Z]{2}") || city != null && (city.isEmpty()
                    || city.codePointCount(0, city.length()) > 120 || city.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
                    || (latitude == null) != (longitude == null)
                    || latitude != null && !(Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180)
                    || country == null && latitude == null) {
                throw new IllegalArgumentException("Invalid location");
            }
        }

        /**
         * Reads the host configuration keys {@code country}, {@code city}, {@code latitude} and {@code longitude}.
         *
         * @return null when nothing is declared
         */
        public static Location of(Map<String, String> settings) {
            if (settings.isEmpty()) {
                return null;
            }
            if (!Set.of("country", "city", "latitude", "longitude").containsAll(settings.keySet())) {
                throw new IllegalArgumentException("Unknown location key");
            }
            String country = settings.get("country"), city = settings.get("city"),
                    latitude = settings.get("latitude"), longitude = settings.get("longitude");
            return new Location(country == null ? null : country.trim().toUpperCase(Locale.ROOT),
                    city == null ? null : city.trim(), latitude == null ? null : Double.valueOf(latitude.trim()),
                    longitude == null ? null : Double.valueOf(longitude.trim()));
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            if (country != null) {
                json.addProperty("country", country);
            }
            if (city != null) {
                json.addProperty("city", city);
            }
            if (latitude != null) {
                json.addProperty("latitude", latitude);
                json.addProperty("longitude", longitude);
            }
            return json;
        }
    }

    /**
     * A link the provider offers the host's operator. It may carry a one-time credential: show it, never open or
     * save it.
     */
    public record OperatorAction(URI url, String text, long expiresAt) {
        @Override
        public String toString() {
            return "OperatorAction[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * Capacity and connectedPlayers describe the same observation.
     */
    public record Health(boolean acceptingPlayers, int capacity, Software software, ConnectedPlayers connectedPlayers) {
        public Health {
            if (capacity < 0 || capacity > 1000000 || connectedPlayers == null) {
                throw new IllegalArgumentException("Invalid health");
            }
        }
    }

    public static final class ProviderException extends IOException {
        private final int status;
        private final String code;

        ProviderException(int status, String code) {
            super("Provider request failed: " + status + " " + code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        /** The provider's machine code; see the wire reference for the codes clients act on. */
        public String code() {
            return code;
        }
    }

    // Null is meaningful inside opaque extension data (for example, clearing a setting).
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final Configuration config;
    private final ProviderStateStore store;
    private final ProviderTransport transport;
    private final Supplier<ServerStatus> statusSupplier;
    private final Supplier<Health> healthSupplier;
    private final ProviderLog diagnostics;
    private final ConnectivityLog connectivity;
    private final String origin;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nethernet-provider");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10))
                    .build();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final ArrayBlockingQueue<JsonObject> gameOutcomeQueue = new ArrayBlockingQueue<>(256);
    private JsonObject state;
    private JsonObject discovery;
    private JsonObject registrationExtensions = new JsonObject();
    private JsonObject heartbeatExtensions = new JsonObject();
    private PrivateKey privateKey;
    private String profileRevision;
    private JsonObject lastProfile;
    /** The admission key of the last acknowledged profile, which is the provider's active epoch. */
    private String publishedKeyId;
    private ProviderTransport.HostProfileSnapshot publishedProfileSnapshot;
    private long publishedCandidateRevision, publishedCandidateVersion;
    private long observedCandidateRevision, observedCandidateVersion, nextCandidateRefresh;

    /** A delivered epoch, kept out of the persisted state until the transport has accepted it. */
    private JsonObject pendingAdmissionKey;
    private long intervalMs = 10000;
    private long nextHeartbeat;
    private long heartbeatSentAt;
    private boolean started;
    private boolean closed;
    private boolean scheduledCheckIns;
    private boolean fenced;
    private Boolean lastReportedGameOutcomes;
    private boolean locationReported;
    private OperatorAction operatorAction;
    private boolean operatorActionAvailable;
    private long nextOutcomes;
    private long nextStatusUpdate;
    private long minUpdateIntervalMs = 1000;
    private URI websocketEndpoint;
    private ProviderWebSocket websocket;
    private volatile String lastControlCarrier = "none";

    private record AssistedAuthority(
            String instance,
            long generation,
            ProviderTransport.HostProfileSnapshot profile,
            long deadlineNanos,
            long expiresAt) {}

    private volatile AssistedAuthority assistedAuthority;
    private volatile boolean assistedMode;
    private String hostState = "serving";
    private String installedKeyId;
    private List<AdmissionKey> installedAdmissionKeys = List.of();
    private ProviderTransport.HostProfileSnapshot diagnosticInstalledSnapshot;
    private long diagnosticInstalledDeadline, diagnosticInstalledExpiresAt;
    private JsonObject lastHeartbeat = new JsonObject();
    private ServerStatus lastReportedStatus;
    private Health lastReportedHealth;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private ScheduledFuture<?> timer;

    public ProviderClient(
            Configuration config,
            ProviderStateStore store,
            ProviderTransport transport,
            Supplier<ServerStatus> statusSupplier,
            Supplier<Health> healthSupplier,
            Consumer<ProviderDiagnostic> diagnostics) {
        this.config = config;
        this.store = store;
        this.transport = transport;
        this.statusSupplier = statusSupplier;
        this.healthSupplier = healthSupplier;
        this.diagnostics = new ProviderLog(diagnostics);
        this.connectivity = new ConnectivityLog(diagnostics, config.assistedJoins(), config.diagnosticAdmission());
        this.origin = ProviderOrigin.canonicalize(config.provider());
    }

    /** Carrier of the last successful signed operation, independent of advertised configuration. */
    public String lastControlCarrier() {
        return lastControlCarrier;
    }

    public CompletableFuture<Registration> start() {
        return submit(
                () -> {
                    if (started) {
                        throw new IllegalStateException("Already started");
                    }
                    discovery =
                            exchange(
                                    URI.create(
                                            origin + "/.well-known/nethernet-external-signaling"),
                                    "GET",
                                    null,
                                    false,
                                    null,
                                    null);
                    validateDiscovery();
                    transport
                            .configureStunServers(discoveryStunServers())
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);
                    configureWebSocket();
                    state = store.read();
                    if (state.has("provider")
                            && !origin.equals(state.get("provider").getAsString())) {
                        throw new IOException(
                                "State belongs to another provider; use a separate directory");
                    }
                    loadMachineKey();
                    if (state.has("registration") && !recoverExisting()) {
                        diagnostics.registrationLost(registration("instanceId"));
                        state = new JsonObject();
                        gameOutcomeQueue.clear();
                        loadMachineKey();
                    }
                    if (!state.has("registration")) {
                        enroll();
                    }
                    JsonObject registration = state.getAsJsonObject("registration");
                    if (!registration.get("provider").getAsString().equals(origin)) {
                        throw new IOException("Registration audience changed");
                    }
                    state.addProperty("protocol", ProviderCrypto.PROTOCOL);
                    state.addProperty(
                            "generation", registration.get("leaseGeneration").getAsLong());
                    state.addProperty("sequence", 0);
                    state.remove("cursor");
                    state.remove("pendingAdmissions");
                    state.remove("pendingWebSocketOperation");
                    save();
                    installKeys();
                    started = true;
                    heartbeat();
                    if (timer != null) {
                        timer.cancel(false);
                    }
                    timer =
                            executor.scheduleWithFixedDelay(
                                    () -> {
                                        if (closed) {
                                            return;
                                        }
                                        try {
                                            if (started
                                                    && (System.nanoTime() >= nextHeartbeat
                                                            || candidatesChanged()
                                                            || statusChanged())) {
                                                heartbeat();
                                            }
                                        } catch (Exception e) {
                                            nextHeartbeat =
                                                    System.nanoTime()
                                                            + TimeUnit.SECONDS.toNanos(10);
                                            nextStatusUpdate = nextHeartbeat;
                                            diagnostics.failed(ProviderLog.Operation.STATUS, safeFailure(e));
                                        }
                                        if (started && System.nanoTime() >= nextOutcomes) {
                                            try {
                                                flushEvents();
                                            } catch (Exception e) {
                                                nextOutcomes =
                                                        System.nanoTime()
                                                                + TimeUnit.SECONDS.toNanos(10);
                                                diagnostics.failed(ProviderLog.Operation.EVENTS, safeFailure(e));
                                            }
                                        }
                                    },
                                    1000,
                                    1000,
                                    TimeUnit.MILLISECONDS);
                    return registrationView();
                });
    }

    private List<ProviderTransport.StunServer> discoveryStunServers() throws IOException {
        try {
            if (!discovery.has("extensions")
                    || !discovery.getAsJsonObject("extensions").has(CONNECTIVITY_EXTENSION)) {
                return List.of();
            }
            var extension =
                    discovery.getAsJsonObject("extensions").getAsJsonObject(CONNECTIVITY_EXTENSION);
            if (extension.get("version").getAsInt() != 1) {
                return List.of();
            }
            var data = extension.getAsJsonObject("data");
            if (!data.has("stunServers")) {
                return List.of();
            }
            var values = data.getAsJsonArray("stunServers");
            if (values.size() > 2) {
                throw new IllegalArgumentException("STUN discovery bound");
            }
            var result = new ArrayList<ProviderTransport.StunServer>();
            for (var value : values) {
                var server = value.getAsJsonObject();
                result.add(
                        new ProviderTransport.StunServer(
                                server.get("host").getAsString(),
                                server.get("port").getAsBigDecimal().intValueExact()));
            }
            return List.copyOf(result);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid provider STUN discovery", invalid);
        }
    }

    private void validateDiscovery() throws IOException {
        ProviderContract.require("discovery", discovery);
        ProtocolExtensions.validate(discovery);
        if (!origin.equals(discovery.get("provider").getAsString())) {
            throw new IOException("Discovery provider mismatch");
        }
        for (String[] pair : List.of(new String[]{"protocols", ProviderCrypto.PROTOCOL},
                new String[]{"signatures", ProviderCrypto.SIGNATURE})) {
            if (!discovery.getAsJsonArray(pair[0]).contains(new JsonPrimitive(pair[1]))) {
                throw new IOException("Unsupported required provider capability: " + pair[0]);
            }
        }
        if (!discovery.getAsJsonObject("authorization").getAsJsonArray("schemes")
                .contains(new JsonPrimitive(config.authorizationScheme()))) {
            throw new IOException("Unsupported provider authorization");
        }
        intervalMs = discovery.getAsJsonObject("limits").get("heartbeatIntervalMs").getAsLong();
        if (intervalMs < 1000 || intervalMs > 30000) {
            throw new IOException("Unsupported heartbeat interval");
        }
    }

    private JsonObject recoveryRequest(String registrationId) {
        JsonObject recovery = new JsonObject();
        recovery.addProperty("registrationId", registrationId);
        recovery.addProperty("protocol", ProviderCrypto.PROTOCOL);
        return recovery;
    }

    /** A new logical instance gets a fresh machine key. */
    private void loadMachineKey() throws IOException, GeneralSecurityException {
        if (!state.has("privateKey")) {
            KeyPair pair = ProviderCrypto.generate();
            state.addProperty("provider", origin);
            state.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded()));
            state.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic()));
            save();
        }
        privateKey = ProviderCrypto.privateKey(state.get("privateKey").getAsString());
    }

    /** False when the provider has nothing to recover, so the saved registration is gone for good. */
    private boolean recoverExisting() throws Exception {
        return recover(registration("registrationId"), true);
    }

    /**
     * Recovers by registration ID, or by challenge ID after a lost completion. A recovery whose own
     * completion reply was lost is retried once with a fresh challenge.
     *
     * @return false when the provider has nothing to recover
     */
    private boolean recover(String id, boolean registrationId) throws Exception {
        for (int attempt = 0; ; attempt++) {
            JsonObject challenge = recoveryChallenge(id);
            if (challenge == null) {
                return false;
            }
            try {
                completeRecovery(challenge, registrationId ? id : null);
                return true;
            } catch (ProviderException e) {
                if (!RECOVERY_REQUIRED.equals(e.code()) || attempt > 0) {
                    throw e;
                }
            }
        }
    }

    private JsonObject recoveryChallenge(String registrationId) throws Exception {
        try {
            return unsigned("register", recoveryRequest(registrationId));
        } catch (ProviderException e) {
            if ("recovery_unavailable".equals(e.code())) {
                return null;
            }
            throw e;
        }
    }

    /** Member order is the wire format: Gson writes in insertion order and these bytes are signed. */
    private static JsonObject completion(JsonObject challenge, String nonce, String intent, PrivateKey key)
            throws Exception {
        JsonObject completion = new JsonObject();
        completion.addProperty("protocol", ProviderCrypto.PROTOCOL);
        completion.addProperty("challengeId", challenge.get("challengeId").getAsString());
        completion.addProperty("proofNonce", nonce);
        completion.addProperty("idempotencyKey", intent);
        completion.addProperty("signature", ProviderCrypto.sign(key, ProviderCrypto.proof(challenge, nonce, intent)));
        return completion;
    }

    /** Protocol and signature are schema constants, so {@code require} has already refused them. */
    private void requireBoundChallenge(JsonObject challenge, String failure) throws IOException {
        ProviderContract.require("challenge", challenge);
        if (!origin.equals(challenge.get("audience").getAsString())
                || !ProviderCrypto.contextDigest(challenge.getAsJsonObject("context"))
                        .equals(challenge.get("contextDigest").getAsString())) {
            throw new IOException(failure);
        }
    }

    /** @param registrationId The saved registration ID, or null after a lost completion */
    private void completeRecovery(JsonObject challenge, String registrationId) throws Exception {
        requireBoundChallenge(challenge, "Unbound recovery challenge");
        JsonObject context = challenge.getAsJsonObject("context");
        String bound = context.get("registrationId").getAsString();
        if (!"recover".equals(context.get("mode").getAsString()) || bound.isEmpty()
                || registrationId != null && !registrationId.equals(bound)
                || challenge.get("expiresAt").getAsLong() <= System.currentTimeMillis()
                || challenge.getAsJsonObject("pow").get("difficulty").getAsInt() != 0) {
            throw new IOException("Unbound recovery challenge");
        }
        String thumbprint = challenge.get("thumbprint").getAsString();
        boolean pending = state.has("pendingPublicKeyJwk") && ProviderCrypto.thumbprint(
                state.getAsJsonObject("pendingPublicKeyJwk")).equals(thumbprint);
        PrivateKey key = pending ? ProviderCrypto.privateKey(state.get("pendingPrivateKey").getAsString()) : privateKey;
        if (!pending && !ProviderCrypto.thumbprint(state.getAsJsonObject("publicKeyJwk")).equals(thumbprint)) {
            throw new IOException("Recovery key does not match durable state");
        }
        String intent = UUID.randomUUID().toString();
        JsonObject recovered = unsigned("complete", completion(challenge, "0", intent, key));
        validateRegistration(recovered);
        if (!bound.equals(recovered.get("registrationId").getAsString())) {
            throw new IOException("Recovered registration changed");
        }
        if (state.has("registration")) {
            for (String field : List.of("instanceId", "registrationId")) {
                if (!state.getAsJsonObject("registration").get(field).equals(recovered.get(field))) {
                    throw new IOException("Recovered instance identity changed");
                }
            }
        }
        registrationExtensions = ProtocolExtensions.copy(recovered);
        recovered.remove("extensions");
        recovered.remove("admissionKey");
        recovered.remove("operatorAction");
        state.add("registration", recovered);
        state.addProperty("generation", recovered.get("leaseGeneration").getAsLong());
        state.addProperty("sequence", 0);
        if (!state.has("ticketKeys")) {
            state.add("ticketKeys", new JsonArray());
        }
        state.remove("challenge");
        // Completion starts a fresh fenced generation; operational sequencing starts at zero.
        if (pending) {
            state.add("privateKey", state.remove("pendingPrivateKey"));
            state.add("publicKeyJwk", state.remove("pendingPublicKeyJwk"));
            privateKey = key;
        }
        save();
    }

    private void enroll() throws Exception {
        JsonObject challenge = null;
        if (state.has("challenge")) {
            JsonObject saved = state.getAsJsonObject("challenge");
            if (recover(saved.get("challengeId").getAsString(), false)) {
                return;
            }
            // Nothing was created, so an expired challenge is simply replaced
            if (saved.get("expiresAt").getAsLong() > System.currentTimeMillis()) {
                challenge = saved;
            }
        }
        if (challenge == null) {
            JsonObject request = new JsonObject();
            request.addProperty("protocol", ProviderCrypto.PROTOCOL);
            request.add("publicKeyJwk", state.get("publicKeyJwk"));
            if (config.label() != null) {
                request.addProperty("label", config.label());
            }
            challenge = unsigned("register", request, config.authorizationToken());
            state.add("challenge", challenge);
            save();
        }
        requireBoundChallenge(challenge, "Unbound registration challenge");
        if (!ProviderCrypto.thumbprint(state.getAsJsonObject("publicKeyJwk"))
                .equals(challenge.get("thumbprint").getAsString())) {
            throw new IOException("Unbound registration challenge");
        }
        JsonObject context = challenge.getAsJsonObject("context");
        if (!acceptsRegistrationMode(context.get("mode").getAsString())) {
            throw new IOException("Challenge registration context changed");
        }
        if (challenge.has("authorization") && !config.authorizationScheme()
                .equals(challenge.getAsJsonObject("authorization").get("scheme").getAsString())) {
            throw new IOException("Challenge authorization changed");
        }
        if (BEARER_TOKEN.equals(config.authorizationScheme()) && (!challenge.has("authorization")
                || challenge.getAsJsonObject("authorization").get("reference").getAsString().isBlank())) {
            throw new IOException("Bearer challenge omitted its authority reference");
        }
        String intent = UUID.randomUUID().toString(), nonce = null;
        int bits = challenge.getAsJsonObject("pow").get("difficulty").getAsInt();
        if (bits < 0 || bits > 24) {
            throw new IOException("Unsupported proof of work");
        }
        if (BEARER_TOKEN.equals(config.authorizationScheme()) && bits != 0) {
            throw new IOException("Bearer-authorized registration unexpectedly requires proof of work");
        }
        long deadline = challenge.get("expiresAt").getAsLong();
        for (long i = 0; System.currentTimeMillis() < deadline; i++) {
            String candidate = Long.toString(i);
            if (ProviderCrypto.meetsDifficulty(
                    ProviderCrypto.digest(ProviderCrypto.proof(challenge, candidate, intent)), bits)) {
                nonce = candidate;
                break;
            }
        }
        if (nonce == null) {
            throw new IOException("Challenge expired before proof completed");
        }
        JsonObject registration;
        try {
            registration = unsigned("complete", completion(challenge, nonce, intent, privateKey));
        } catch (ProviderException e) {
            // The completion committed but its reply was lost
            if (RECOVERY_REQUIRED.equals(e.code())) {
                if (recover(challenge.get("challengeId").getAsString(), false)) {
                    return;
                }
                // Consumed but unrecoverable, so the next start registers afresh
                state.remove("challenge");
                save();
            }
            throw e;
        }
        validateRegistration(registration);
        JsonElement action = registration.remove("operatorAction");
        operatorAction = action == null ? null : operatorAction(action);
        registrationExtensions = ProtocolExtensions.copy(registration);
        registration.remove("extensions");
        state.add("registration", registration);
        state.addProperty("generation", registration.get("leaseGeneration").getAsLong());
        state.addProperty("sequence", 0);
        state.add("ticketKeys", new JsonArray());
        state.remove("challenge");
        if (registration.has("admissionKey")) {
            pendingAdmissionKey = registration.remove("admissionKey").getAsJsonObject();
        }
        save();
    }

    /** Anonymous hosts can only create a service; a token may also attach to one. */
    private boolean acceptsRegistrationMode(String selected) {
        return NEW_SERVICE.equals(selected) || (config.authorizationToken() != null
                && ATTACH_INSTANCE.equals(selected));
    }

    private void validateRegistration(JsonObject registration) throws IOException {
        ProviderContract.require("registration", registration);
        boolean hasService = registration.has("serviceId"), hasAddress = registration.has("publicAddress");
        if (hasService != hasAddress) {
            throw new IOException("Incomplete public endpoint metadata");
        }
        if (hasService) {
            for (String field : List.of("serviceId", "publicAddress")) {
                JsonElement value = registration.get(field);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString()
                        .isBlank()) {
                    throw new IOException("Invalid public endpoint metadata");
                }
            }
        }
        if (!origin.equals(registration.get("provider").getAsString())) {
            throw new IOException("Registration provider changed");
        }
    }

    private void installKeys() throws Exception {
        JsonArray retained = new JsonArray();
        if (state.has("ticketKeys")) {
            for (JsonElement entry : state.getAsJsonArray("ticketKeys")) {
                JsonObject key = entry.getAsJsonObject();
                if (!key.has("retireAfter") || key.get("retireAfter").getAsLong() > System.currentTimeMillis()) {
                    retained.add(key);
                }
            }
        }
        if (pendingAdmissionKey != null) {
            JsonObject delivered = new JsonObject();
            delivered.add("keyId", pendingAdmissionKey.get("keyId"));
            delivered.add("secret", pendingAdmissionKey.get("secret"));
            retained.add(delivered);
        }
        if (retained.size() > 8) {
            throw new IOException("Too many admission key epochs");
        }
        if (retained.isEmpty()) {
            disableDiagnosticAdmission();
            installedAdmissionKeys = List.of();
            state.add("ticketKeys", retained);
            installedKeyId = null;
            if (!state.has("keyRequestId")) {
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
            }
            save();
            return;
        }
        // Reading the epochs is what rejects a malformed one, so it happens before anything is written
        List<AdmissionKey> keys = new ArrayList<>();
        for (JsonElement entry : retained) {
            JsonObject key = entry.getAsJsonObject();
            keys.add(new AdmissionKey(key.get("keyId").getAsString(), key.get("secret").getAsString(),
                    key.has("retireAfter") ? key.get("retireAfter").getAsLong() : Long.MAX_VALUE));
        }
        if (!keys.equals(installedAdmissionKeys)) {
            disableDiagnosticAdmission();
        }
        transport
                .installAdmissionKeys(List.copyOf(keys))
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        // Only an epoch set the transport accepted is recorded, so the next heartbeat cannot advertise
        // a key that was never installed, and a bad one cannot survive a restart
        state.add("ticketKeys", retained);
        pendingAdmissionKey = null;
        save();
        installedKeyId = keys.get(keys.size() - 1).keyId();
        installedAdmissionKeys = List.copyOf(keys);
    }

    /**
     * Wake local observation on join/leave/reload; unchanged snapshots never create network traffic.
     */
    public void requestStatusRefresh() {
        if (!closing.get() && refreshQueued.compareAndSet(false, true)) {
            executor.execute(
                    () -> {
                        refreshQueued.set(false);
                        if (!closed && started) {
                            try {
                                if (statusChanged()) {
                                    heartbeat();
                                }
                            } catch (Exception e) {
                                nextStatusUpdate = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                diagnostics.failed(ProviderLog.Operation.STATUS, safeFailure(e));
                            }
                        }
                    });
        }
    }

    private ServerStatus currentStatus() {
        return statusSupplier == null ? null : statusSupplier.get();
    }

    private boolean statusChanged() {
        if (System.nanoTime() < nextStatusUpdate) {
            return false;
        }
        if (!scheduledCheckIns) {
            return System.nanoTime() >= nextHeartbeat;
        }
        ServerStatus status = currentStatus();
        Health health = healthSupplier.get();
        return !Objects.equals(status, lastReportedStatus) || lastReportedHealth == null
                || health.acceptingPlayers() != lastReportedHealth.acceptingPlayers()
                || health.capacity() != lastReportedHealth.capacity()
                || health.connectedPlayers().count() != lastReportedHealth.connectedPlayers().count()
                || !Objects.equals(health.software(), lastReportedHealth.software());
    }

    /** Sample the cheap local counter; material replacement bypasses freshness-only coalescing. */
    private boolean candidatesChanged() throws Exception {
        if (System.nanoTime() < nextStatusUpdate || !hostState.equals("serving")) {
            return false;
        }
        long version = transport.candidatePublicationVersion();
        if (version == 0 || version == publishedCandidateVersion) {
            return false;
        }
        if (version != observedCandidateVersion) {
            var snapshot =
                    transport.captureHostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
            snapshot.requireCurrent();
            observedCandidateVersion = snapshot.publicationVersion();
            observedCandidateRevision = snapshot.candidateRevision();
        }
        if (publishedProfileSnapshot != null) {
            try {
                publishedProfileSnapshot.requireCurrent();
            } catch (RuntimeException changed) {
                return true;
            }
        }
        return observedCandidateRevision != publishedCandidateRevision
                || System.nanoTime() >= nextCandidateRefresh;
    }

    private void heartbeat() throws Exception {
        if (state.has("pendingWebSocketOperation")) {
            assistedAuthority = null;
            // An ambiguous send may have outlived its native mapping. Recover the existing
            // registration
            // before issuing fresh state; do not replay expired profile bytes or stall until
            // process restart.
            disableDiagnosticAdmission();
            if (websocket != null) {
                websocket.close();
            }
            recoverExisting();
            clearWebSocketPending();
            configureWebSocket();
            profileRevision = null;
            lastProfile = null;
            installKeys();
        }
        // Key delivery and local diagnostic installation can need an immediate second exchange.
        for (int exchange = 0; exchange < 3; exchange++) {
            try {
                JsonObject body = new JsonObject(), profile = null;
                ProviderTransport.HostProfileSnapshot profileSnapshot = null,
                        diagnosticSnapshot = null;
            if (installedKeyId != null && hostState.equals("serving")) {
                    try {
                        profileSnapshot =
                                transport
                                        .captureHostProfile()
                                        .toCompletableFuture()
                                        .get(10, TimeUnit.SECONDS);
                        profileSnapshot.requireCurrent();
                        profile = profileSnapshot.profile();
                        updateAssistedMode(profileSnapshot);
                        if (assistedMode) {
                            if (!transport.supportsAssistedJoins()) {
                                throw new IOException("Assisted transport unavailable");
                            }
                            var families = new JsonArray();
                            profileSnapshot.assistedFamilies().stream().sorted().forEach(families::add);
                            profile.getAsJsonObject("statelessAdmission").add("assistedFamilies", families);
                        }
                        if (config.diagnosticAdmission()) {
                            if (!transport.supportsDiagnosticAdmission()) {
                                throw new IOException("Diagnostic transport unavailable");
                            }
                            if (diagnosticProfile(profileSnapshot)) {
                                diagnosticSnapshot = profileSnapshot;
                            } else {
                                disableDiagnosticAdmission();
                            }
                        }
                    } catch (Exception unavailable) {
                        disableDiagnosticAdmission();
                        throw unavailable;
                }
                if (!profile.equals(lastProfile)) {
                    body.add("hostProfile", profile);
                } else if (profileRevision != null) {
                    body.addProperty("hostProfileRevision", profileRevision);
                }
            } else if (profileRevision != null) {
                body.addProperty("hostProfileRevision", profileRevision);
            }
            // The active epoch changes only with a matching profile
            if (installedKeyId != null && (body.has("hostProfile") || installedKeyId.equals(publishedKeyId))) {
                JsonArray installed = new JsonArray();
                for (JsonElement key : state.getAsJsonArray("ticketKeys")) {
                    installed.add(key.getAsJsonObject().get("keyId"));
                }
                body.add("installedAdmissionKeyIds", installed);
            }
            if (state.has("keyRequestId")) {
                body.add("admissionKeyRequestId", state.get("keyRequestId"));
            }
            Health health = healthSupplier.get();
                body.addProperty(
                        "acceptingPlayers",
                        health.acceptingPlayers()
                                && installedKeyId != null
                                && hostState.equals("serving"));
                JsonObject extensions = heartbeatExtensions.deepCopy();
                boolean diagnosticsAdvertised =
                        diagnosticSnapshot != null
                                && diagnosticInstallationCurrent(diagnosticSnapshot);
                if (profileSnapshot != null && profileSnapshot.candidateRevision() > 0) {
                    profileSnapshot.requireCurrent();
                    var data = new JsonObject();
                    data.addProperty("diagnostics", diagnosticsAdvertised);
                    data.addProperty("candidateRevision", profileSnapshot.candidateRevision());
                    JsonArray probes = profileSnapshot.probeCandidates();
                    if (!probes.equals(profile.getAsJsonArray("candidates"))) {
                        data.add("probeCandidates", probes);
                    }
                    boolean reflexive = profile.getAsJsonArray("candidates").asList().stream()
                            .anyMatch(item -> "srflx".equals(item.getAsJsonObject().get("type").getAsString()));
                    data.addProperty("method", reflexive ? "warm_stun" : config.connectivityMethod());
                    var extension = new JsonObject();
                    extension.addProperty("version", 1);
                    extension.addProperty("critical", false);
                    extension.add("data", data);
                    extensions.add(CONNECTIVITY_EXTENSION, extension);
                } else if (diagnosticSnapshot == null || diagnosticInstalledSnapshot != null) {
                    disableDiagnosticAdmission();
                }
                if (!extensions.isEmpty()) {
                    body.add("extensions", extensions);
                }
                ProtocolExtensions.validate(body);
            body.addProperty("capacity", health.capacity());
            body.add("connectedPlayers", JSON.toJsonTree(health.connectedPlayers()));
            heartbeatSentAt = System.currentTimeMillis();
            boolean gameOutcomes = config.gameOutcomes();
            if (body.has("hostProfile") || !Objects.equals(lastReportedGameOutcomes, gameOutcomes)) {
                body.addProperty("gameOutcomes", gameOutcomes ? "available" : "unavailable");
            }
            if (health.software() != null && (body.has("hostProfile") || lastReportedHealth == null
                    || !health.software().equals(lastReportedHealth.software()))) {
                body.add("software", JSON.toJsonTree(health.software()));
            }
            // Null clears a location an earlier configuration declared
            if (body.has("hostProfile") || !locationReported) {
                body.add("location", config.location() == null ? JsonNull.INSTANCE : config.location().toJson());
            }
            ServerStatus status = null;
            try {
                status = currentStatus();
                diagnostics.recovered(ProviderLog.Operation.SERVER_STATUS);
                if (status != null) {
                    body.add("serverStatus", JSON.toJsonTree(status));
                }
            } catch (RuntimeException failure) {
                diagnostics.failed(ProviderLog.Operation.SERVER_STATUS, safeFailure(failure));
            }
            long requestStarted = System.nanoTime();
                var captured = profileSnapshot;
                JsonObject response =
                        signed(
                                "heartbeat",
                                "POST",
                                body,
                                UUID.randomUUID().toString(),
                                captured == null ? () -> {} : captured::requireCurrent);
                long diagnosticSuccessNanos = System.nanoTime(),
                        diagnosticSuccessMillis = System.currentTimeMillis();
            ProtocolExtensions.validate(response);
                boolean again = false;
                if (profileSnapshot != null) {
                    try {
                        profileSnapshot.requireCurrent();
                    } catch (ProviderTransport.HostProfileSnapshotChangedException replaced) {
                        invalidatePublishedProfile();
                        // The operation committed. Keep its keys and lease, but grant no authority
                        // to stale endpoints.
                        profileSnapshot = null;
                        diagnosticSnapshot = null;
                        again = true;
                    } catch (RuntimeException unavailable) {
                        invalidatePublishedProfile();
                        throw unavailable;
                    }
                }
                if (config.assistedJoins()) {
                    assistedAuthority =
                            assistedMode
                                            && lastControlCarrier.equals("websocket")
                                            && profileSnapshot != null
                                            && health.acceptingPlayers()
                                            && hostState.equals("serving")
                                    ? new AssistedAuthority(
                                            registration("instanceId"),
                                            state.get("generation").getAsLong(),
                                            profileSnapshot,
                                            requestStarted + TimeUnit.MINUTES.toNanos(5),
                                            heartbeatSentAt + 300000)
                                    : null;
                }
            if (body.has("hostProfile")) {
                if (!response.has("hostProfileRevision") || response.get("hostProfileRevision").isJsonNull()) {
                    throw new IOException("Profile acknowledgement missing");
                }
                    publishedKeyId = profile.get("admissionKeyId").getAsString();
                    if (profileSnapshot != null) {
                        profileRevision = response.get("hostProfileRevision").getAsString();
                        lastProfile = profile.deepCopy();
                        state.addProperty("profilePublishedAt", System.currentTimeMillis());
                        save();
                    }
            } else if (profileRevision != null && !new JsonPrimitive(profileRevision).equals(response.get("hostProfileRevision"))) {
                // The provider no longer holds the published profile, so send it in full
                profileRevision = null;
                lastProfile = null;
                again |= profileSnapshot != null;
            }
            if (response.has("admissionKey")) {
                JsonObject key = response.remove("admissionKey").getAsJsonObject();
                if (!state.has("keyRequestId") || !response.has("admissionKeyRequest") ||
                        !state.get("keyRequestId").equals(response.getAsJsonObject("admissionKeyRequest").get("id")) ||
                        !key.get("keyId").equals(response.getAsJsonObject("admissionKeyRequest").get("keyId"))) {
                    throw new IOException("Unbound admission key response");
                }
                pendingAdmissionKey = key;
                JsonElement pendingRequest = state.remove("keyRequestId");
                try {
                    installKeys();
                } catch (Exception e) {
                    // A key the transport will not take must not outlive this heartbeat, in memory or on disk
                    pendingAdmissionKey = null;
                    if (pendingRequest != null) {
                        state.add("keyRequestId", pendingRequest);
                    }
                    throw e;
                }
                lastProfile = null;
                again = true;
            } else if (state.has("keyRequestId") && response.has("admissionKeyRequest") &&
                    state.get("keyRequestId").equals(response.getAsJsonObject("admissionKeyRequest").get("id"))) {
                // The provider confirms delivery but the one-time response was lost.
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
                save();
                again = true;
            }
            if (response.has("admissionKeyRetirements") && !response.getAsJsonArray("admissionKeyRetirements").isEmpty()) {
                for (JsonElement retirement : response.getAsJsonArray("admissionKeyRetirements")) {
                    for (JsonElement stored : state.getAsJsonArray("ticketKeys")) {
                        JsonObject retired = retirement.getAsJsonObject(), key = stored.getAsJsonObject();
                        if (retired.get("keyId").equals(key.get("keyId"))) {
                            key.addProperty("retireAfter", Math.min(
                                    key.has("retireAfter") ? key.get("retireAfter").getAsLong() : Long.MAX_VALUE,
                                    retired.get("retireAfter").getAsLong()));
                        }
                    }
                }
                save();
                installKeys();
            }
            if (response.has("checkIn")) {
                CheckInSchedule schedule = CheckInSchedule.parse(response);
                scheduledCheckIns = true;
                minUpdateIntervalMs = schedule.minUpdateIntervalMillis();
                nextHeartbeat = requestStarted + TimeUnit.MILLISECONDS.toNanos(schedule.afterMillis());
            } else {
                scheduledCheckIns = false;
                nextHeartbeat = requestStarted + TimeUnit.MILLISECONDS.toNanos(intervalMs);
            }
            nextStatusUpdate = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    scheduledCheckIns ? minUpdateIntervalMs : intervalMs);
            lastReportedStatus = status;
            lastReportedHealth = health;
            lastReportedGameOutcomes = gameOutcomes;
            locationReported |= body.has("location");
            JsonElement available = response.get("operatorActionAvailable");
            operatorActionAvailable = available != null && available.isJsonPrimitive()
                    && available.getAsJsonPrimitive().isBoolean() && available.getAsBoolean();
            lastHeartbeat = response.deepCopy();
                if (profileSnapshot != null) {
                    publishedProfileSnapshot = profileSnapshot;
                    publishedCandidateRevision = profileSnapshot.candidateRevision();
                    publishedCandidateVersion = profileSnapshot.publicationVersion();
                    nextCandidateRefresh = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    connectivity.published(profileSnapshot);
                }
                if (diagnosticSnapshot != null) {
                    configureDiagnosticAdmission(
                            diagnosticSnapshot,
                            response,
                            requestStarted,
                            diagnosticSuccessMillis,
                            diagnosticSuccessNanos);
                    if (!diagnosticsAdvertised) {
                        again = true;
                    }
                    long remaining = Math.max(0, diagnosticInstalledDeadline - System.nanoTime());
                    long lead = Math.min(TimeUnit.SECONDS.toNanos(60), remaining / 5);
                    nextHeartbeat =
                            Math.min(
                                    nextHeartbeat,
                                    Math.max(nextStatusUpdate, diagnosticInstalledDeadline - lead));
                }
                var ownedAssistance = assistedAuthority;
                if (ownedAssistance != null) {
                    long remaining =
                            Math.max(0, ownedAssistance.deadlineNanos() - System.nanoTime());
                    long lead = Math.min(TimeUnit.SECONDS.toNanos(60), remaining / 5);
                    nextHeartbeat =
                            Math.min(
                                    nextHeartbeat,
                                    Math.max(
                                            System.nanoTime(),
                                            ownedAssistance.deadlineNanos() - lead));
                }
                if (profileSnapshot != null) {
                    reportConnectivityFeedback(profileSnapshot, response);
                    profileSnapshot
                            .requireCurrent(); // A host policy transition promptly republishes,
                                               // even at the same candidate revision.
                    if (config.assistedJoins()) {
                        var latest =
                                transport
                                        .captureHostProfile()
                                        .toCompletableFuture()
                                        .get(10, TimeUnit.SECONDS);
                        latest.requireCurrent();
                        if (assistedNeeded(latest) != assistedMode) {
                            again = true;
                        }
                    }
                }
            if (!again) {
                    diagnostics.recovered(ProviderLog.Operation.STATUS);
                return;
            }
            } catch (ProviderTransport.HostProfileSnapshotChangedException replaced) {
                invalidatePublishedProfile();
                // An ambiguous carrier attempt must follow ordinary recovery before any fresh
                // operation.
                if (state.has("pendingWebSocketOperation")) {
                    break;
                }
            }
        }
        nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    }

    private void invalidatePublishedProfile() throws Exception {
        publishedProfileSnapshot = null;
        lastProfile = null;
        profileRevision = null;
        assistedAuthority = null;
        disableDiagnosticAdmission();
    }

    private boolean diagnosticProfile(ProviderTransport.HostProfileSnapshot snapshot) {
        JsonObject profile = snapshot.profile();
        if (snapshot.candidateRevision() < 1
                || !profile.has("statelessAdmission")
                || !profile.has("candidates")) {
            return false;
        }
        JsonArray candidates = snapshot.probeCandidates();
        if (candidates.isEmpty()
                        && (!config.assistedJoins() || snapshot.assistedFamilies().isEmpty())
                || candidates.size() > 32) {
            return false;
        }
        for (JsonElement item : candidates) {
            JsonObject candidate = item.getAsJsonObject();
            String type = candidate.get("type").getAsString();
            if (!Set.of("host", "srflx").contains(type)) {
                return false;
            }
            if (candidate.has("expiresAt")
                    && candidate.get("expiresAt").getAsBigDecimal().longValueExact()
                            <= System.currentTimeMillis()) {
                return false;
            }
        }
        return true;
    }

    private void reportConnectivityFeedback(
            ProviderTransport.HostProfileSnapshot snapshot, JsonObject response) {
        if (snapshot.candidateRevision() < 1 || !response.has("extensions")) {
            return;
        }
        try {
            JsonObject extensions = response.getAsJsonObject("extensions");
            if (!extensions.has(CONNECTIVITY_EXTENSION)) {
                return;
            }
            JsonObject extension = extensions.getAsJsonObject(CONNECTIVITY_EXTENSION);
            if (extension.get("version").getAsInt() != 1) {
                return;
            }
            JsonObject data = extension.getAsJsonObject("data");
            if (!data.has("checks")
                    || data.get("candidateRevision").getAsBigDecimal().longValueExact()
                            != snapshot.candidateRevision()) {
                return;
            }
            JsonArray checks = data.getAsJsonArray("checks");
            if (checks.size() > 18) {
                throw new IllegalArgumentException("Connectivity feedback bound");
            }
            var parsed = new ArrayList<ProviderTransport.ConnectivityCheck>();
            long now = System.currentTimeMillis();
            for (JsonElement item : checks) {
                var check = item.getAsJsonObject();
                var outcome =
                        switch (check.get("outcome").getAsString()) {
                            case "established" -> {
                                yield ProviderTransport.ConnectivityOutcome.ESTABLISHED;
                            }
                            case "not-established" -> {
                                yield ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED;
                            }
                            case "unknown" -> {
                                yield ProviderTransport.ConnectivityOutcome.UNKNOWN;
                            }
                            case "unavailable" -> {
                                yield ProviderTransport.ConnectivityOutcome.UNAVAILABLE;
                            }
                            default -> {
                                throw new IllegalArgumentException("Unknown connectivity outcome");
                            }
                        };
                java.net.InetSocketAddress target = null;
                if (check.has("target")) {
                    var endpoint = check.getAsJsonObject("target");
                    target =
                            new java.net.InetSocketAddress(
                                    org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(
                                            endpoint.get("address").getAsString()),
                                    endpoint.get("port").getAsBigDecimal().intValueExact());
                }
                var value =
                        new ProviderTransport.ConnectivityCheck(
                                check.get("region").getAsString(),
                                check.get("family").getAsBigDecimal().intValueExact(),
                                check.has("method") ? check.get("method").getAsString() : "defined",
                                target,
                                outcome,
                                check.get("checkedAt").getAsBigDecimal().longValueExact(),
                                check.get("expiresAt").getAsBigDecimal().longValueExact());
                if (value.checkedAt() <= now && value.expiresAt() > now) {
                    parsed.add(value);
                }
            }
            snapshot.requireCurrent();
            transport
                    .reportConnectivityChecks(snapshot.candidateRevision(), parsed)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            connectivity.checks(snapshot, parsed);
            diagnostics.recovered(ProviderLog.Operation.CONNECTIVITY);
        } catch (Exception unavailable) {
            // Optional observations must not undo a successful ordinary heartbeat or change host
            // serving state.
            diagnostics.failed(ProviderLog.Operation.CONNECTIVITY, safeFailure(unavailable));
        }
    }

    private void updateAssistedMode(ProviderTransport.HostProfileSnapshot snapshot)
            throws IOException {
        boolean needed = assistedNeeded(snapshot);
        if (needed == assistedMode) {
            return;
        }
        assistedMode = needed;
        assistedAuthority = null;
        lastProfile = null;
        configureWebSocket(); // Between operations: reconnect with or without the assisted-host
                              // address.
    }

    private boolean assistedNeeded(ProviderTransport.HostProfileSnapshot snapshot) {
        if (!config.assistedJoins()
                || websocketEndpoint == null
                || !transport.supportsAssistedJoins()) {
            return false;
        }
        snapshot.requireCurrent();
        return !snapshot.assistedFamilies().isEmpty();
    }

    private boolean diagnosticInstallationCurrent(ProviderTransport.HostProfileSnapshot snapshot) {
        if (diagnosticInstalledSnapshot == null
                || closing.get()
                || closed
                || diagnosticInstalledDeadline - System.nanoTime() <= 0
                || System.currentTimeMillis() >= diagnosticInstalledExpiresAt
                || diagnosticInstalledSnapshot.candidateRevision()
                        != snapshot.candidateRevision()) {
            return false;
        }
        try {
            diagnosticInstalledSnapshot.requireCurrent();
            snapshot.requireCurrent();
            return diagnosticInstalledSnapshot
                    .profile()
                    .get("statelessAdmission")
                    .equals(snapshot.profile().get("statelessAdmission"));
        } catch (RuntimeException replaced) {
            return false;
        }
    }

    private void disableDiagnosticAdmission() throws Exception {
        diagnosticInstalledSnapshot = null;
        diagnosticInstalledExpiresAt = 0;
        diagnosticInstalledDeadline = 0;
        if (config.diagnosticAdmission() && transport.supportsDiagnosticAdmission()) {
            transport.disableDiagnostics().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private void configureDiagnosticAdmission(
            ProviderTransport.HostProfileSnapshot snapshot,
            JsonObject response,
            long requestStartedNanos,
            long successMillis,
            long successNanos)
            throws Exception {
        try {
            if (closing.get() || closed || !hostState.equals("serving")) {
                throw new IOException("Diagnostic host no longer serving");
            }
            snapshot.requireCurrent();
            JsonObject profile = snapshot.profile();
            long expiresAt = successMillis + DIAGNOSTIC_VALIDITY_MILLIS;
            if (response.has("checkIn")) {
                // The lease counts from when the heartbeat was sent.
                long sent = successMillis - TimeUnit.NANOSECONDS.toMillis(successNanos - requestStartedNanos);
                expiresAt = Math.min(expiresAt, sent + CheckInSchedule.parse(response).leaseMillis());
            }
            long lifetime = expiresAt - successMillis;
            if (lifetime <= 0) {
                throw new IOException("Diagnostic heartbeat lease expired");
            }
            long deadline = successNanos + TimeUnit.MILLISECONDS.toNanos(lifetime);
            if (deadline - System.nanoTime() <= 0) {
                throw new IOException("Diagnostic heartbeat lease expired");
            }
            var context =
                    new DiagnosticAdmissionCodec.Context(
                            origin,
                            registration("instanceId"),
                            profile.getAsJsonObject("statelessAdmission")
                                    .get("incarnation")
                                    .getAsString(),
                            state.get("generation").getAsLong());
            var keys =
                    installedAdmissionKeys.stream()
                            .map(
                                    key ->
                                            new DiagnosticAdmissionCodec.Key(
                                                    key.keyId(),
                                                    key.secret(),
                                                    0,
                                                    Math.min(key.retireAfter(), 9007199254740991L)))
                            .toList();
            var endpointExpiries = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
            for (JsonElement item : snapshot.probeCandidates()) {
                JsonObject candidate = item.getAsJsonObject();
                var address =
                        org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(
                                candidate.get("address").getAsString());
                int family = address instanceof java.net.Inet6Address ? 6 : 4;
                var endpoint =
                        new DiagnosticHostPolicy.Endpoint(
                                family,
                                DiagnosticAdmissionCodec.address(family, address.getHostAddress()),
                                candidate.get("port").getAsBigDecimal().intValueExact(),
                                snapshot.candidateRevision());
                long endpointExpiry =
                        candidate.has("expiresAt")
                                ? Math.min(
                                        expiresAt,
                                        candidate
                                                .get("expiresAt")
                                                .getAsBigDecimal()
                                                .longValueExact())
                                : expiresAt;
                if (endpointExpiry <= System.currentTimeMillis()) {
                    throw new IOException("Diagnostic endpoint expired");
                }
                endpointExpiries.merge(endpoint, endpointExpiry, Math::min);
            }
            if (config.assistedJoins()) {
                for (int family : snapshot.assistedFamilies()) {
                    endpointExpiries.put(
                            DiagnosticHostPolicy.Endpoint.assisted(
                                    family, snapshot.candidateRevision()),
                            expiresAt);
                }
            }
            snapshot.requireCurrent();
            transport
                    .configureDiagnostics(
                            new DiagnosticHostPolicy(
                                    context,
                                    keys,
                                    endpointExpiries.keySet(),
                                    expiresAt,
                                    endpointExpiries),
                            deadline)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            snapshot.requireCurrent();
            if (closing.get() || closed || deadline - System.nanoTime() <= 0) {
                throw new IOException("Diagnostic installation expired");
            }
            diagnosticInstalledSnapshot = snapshot;
            diagnosticInstalledDeadline = deadline;
            diagnosticInstalledExpiresAt = expiresAt;
        } catch (Exception failure) {
            disableDiagnosticAdmission();
            throw failure;
        }
    }

    private void flushEvents() throws Exception {
        if (!state.has("pendingEvents")) {
            state.add("pendingEvents", new JsonArray());
        }
        JsonArray pending = state.getAsJsonArray("pendingEvents");
        int room = Math.min(100, 1000 - pending.size());
        List<JsonObject> fresh = new ArrayList<>(room > 0 ? transport.pollEvents(room) : List.of());
        if (fresh.size() > room) {
            throw new IOException("Transport returned more events than requested");
        }
        gameOutcomeQueue.drainTo(fresh, room - fresh.size());
        for (JsonObject event : fresh) {
            // Persist only the existing redacted telemetry fields, never native SDP or secret extensions.
            JsonObject safe = new JsonObject();
            for (String field :
                    List.of(
                            "stage",
                            "ticketId",
                            "occurredAt",
                            "reason",
                            "remoteAddress",
                            "remotePort")) {
                if (event.has(field)) {
                    safe.add(field, event.get(field));
                }
            }
            if (!safe.has("stage") || !safe.has("ticketId") || !safe.has("occurredAt")) {
                throw new IOException("Malformed transport event");
            }
            // Assisted failures were already reported by the control carrier
            if ("ticket.failed".equals(safe.get("stage").getAsString()) && !(safe.has("reason")
                    && safe.get("reason").getAsString().startsWith("assisted_"))) {
                diagnostics.playerConnectionFailed();
            }
            pending.add(safe);
        }
        if (pending.isEmpty()) {
            return;
        }
        save();
        int sent = Math.min(100, pending.size());
        JsonArray batch = new JsonArray();
        var connectivity =
                ProtocolExtensions.copy(discovery).getAsJsonObject(CONNECTIVITY_EXTENSION);
        boolean observedEndpoints =
                connectivity != null && connectivity.get("version").getAsInt() == 1;
        for (var event : pending.asList().subList(0, sent)) {
            var wire = event.getAsJsonObject().deepCopy();
            if (!observedEndpoints) {
                wire.remove("remoteAddress");
                wire.remove("remotePort");
            }
            batch.add(wire);
        }
        JsonObject body = new JsonObject();
        body.add("events", batch);
        try {
            signed("outcomes", "POST", body);
        } catch (ProviderException rejected) {
            // Any other 4xx had no effect and never will, so it must not block later reports
            if (fenced || rejected.status() / 100 != 4 || rejected.status() == 429) {
                throw rejected;
            }
            diagnostics.detail(ProviderLog.Operation.EVENTS, "Dropped " + sent + " rejected reports: " + rejected.code());
        }
        // Dropped by position, and only once the exchange is done, so no view spans the request
        pending.asList().subList(0, sent).clear();
        save();
        diagnostics.recovered(ProviderLog.Operation.EVENTS);
    }

    /**
     * Refresh through the ordinary heartbeat and return its readiness observation.
     */
    public CompletableFuture<JsonObject> readiness() {
        return submit(() -> {
            heartbeat();
            return lastHeartbeat.deepCopy();
        });
    }

    /**
     * Replace optional application observations and promptly send them when running.
     * An omitted extension is not an application-level instruction to clear an earlier observation.
     */
    public CompletableFuture<Void> updateHeartbeatExtensions(JsonObject extensions) {
        JsonObject document = new JsonObject();
        document.add("extensions", extensions.deepCopy());
        JsonObject validated = ProtocolExtensions.copy(document);
        if (validated.has(CONNECTIVITY_EXTENSION)) {
            throw new IllegalArgumentException("Connectivity extension is owned by this client");
        }
        return submit(
                () -> {
                    heartbeatExtensions = validated;
                    if (started) {
                        heartbeat();
                    }
                    return null;
                });
    }

    /**
     * Queues a game outcome for an admitted ticket without blocking. Requires
     * {@link Configuration.Builder#gameOutcomes}.
     *
     * @param ticketId The ticket ID from the authenticated admission carrier
     * @param reason A code of at most 128 characters, or null; never player identity or disconnect text
     * @return false when the client is stopping or the queue is full, so the outcome was dropped
     */
    public boolean reportGameOutcome(String ticketId, GameOutcome outcome, String reason) {
        if (!config.gameOutcomes()) {
            throw new IllegalStateException("Game outcomes are not enabled");
        }
        if (ticketId.isEmpty() || ticketId.length() > 256 || reason != null && reason.length() > 128) {
            throw new IllegalArgumentException("Invalid game outcome");
        }
        if (closing.get()) {
            return false;
        }
        JsonObject event = new JsonObject();
        event.addProperty("ticketId", ticketId);
        event.addProperty("stage", outcome.stage);
        event.addProperty("occurredAt", System.currentTimeMillis());
        if (reason != null) {
            event.addProperty("reason", reason);
        }
        return gameOutcomeQueue.offer(event);
    }

    /**
     * The provider's current link for the host's operator, requesting a fresh one when a heartbeat said it is offered.
     */
    public CompletableFuture<Optional<OperatorAction>> operatorAction() {
        return submit(() -> {
            if (operatorAction != null && operatorAction.expiresAt() > System.currentTimeMillis()) {
                return Optional.of(operatorAction);
            }
            operatorAction = null;
            if (!started || !operatorActionAvailable) {
                return Optional.empty();
            }
            requireResolvedOperation();
            JsonElement action = signed("operator-action", "POST", new JsonObject()).get("operatorAction");
            operatorAction = action == null ? null : operatorAction(action);
            return Optional.ofNullable(operatorAction);
        });
    }

    /** An invalid optional action is ignored rather than failing the operation that carried it. */
    private static OperatorAction operatorAction(JsonElement value) {
        try {
            JsonObject json = value.getAsJsonObject();
            String link = json.get("url").getAsString(), text = json.get("text").getAsString();
            long expiresAt = json.get("expiresAt").getAsLong();
            URI url = new URI(link);
            boolean loopback = "http".equals(url.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(url.getHost());
            if (link.length() > 2048 || url.getRawUserInfo() != null || url.getHost() == null
                    || !("https".equals(url.getScheme()) || loopback) || text.isEmpty()
                    || text.codePointCount(0, text.length()) > 256 || text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)
                    || expiresAt < 0) {
                return null;
            }
            return new OperatorAction(url, text, expiresAt);
        } catch (RuntimeException | URISyntaxException invalid) {
            return null;
        }
    }

    /** Opaque registration metadata; the application decides what it means. */
    public CompletableFuture<JsonObject> extensions() {
        return submit(() -> registrationExtensions.deepCopy());
    }

    /**
     * Explicit application request to an advertised extension operation, never automatic execution.
     */
    public CompletableFuture<JsonObject> extensionRequest(String namespace, String operation, String method,
                                                          JsonObject body) {
        return submit(
                () -> {
                    if (!Set.of("GET", "POST").contains(method)) {
                        throw new IOException("Unsupported extension method");
                    }
                    JsonObject extension =
                            ProtocolExtensions.copy(discovery).getAsJsonObject(namespace);
                    if (extension == null) {
                        throw new IOException("Extension unavailable");
                    }
                    JsonObject operations =
                            extension.getAsJsonObject("data").getAsJsonObject("operations");
                    if (operations == null || !operations.has(operation)) {
                        throw new IOException("Extension operation unavailable");
                    }
                    URI uri = trusted(URI.create(operations.get(operation).getAsString()));
                    requireResolvedOperation();
                    long sequence =
                            state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
                    state.addProperty("sequence", sequence);
                    save();
                    JsonObject response =
                            exchange(
                                    uri,
                                    method,
                                    body == null ? null : JSON.toJson(body),
                                    true,
                                    UUID.randomUUID().toString(),
                                    null);
                    ProtocolExtensions.validate(response);
                    return response;
                });
    }

    public CompletableFuture<Void> deregister() {
        return submit(
                () -> {
                    disableDiagnosticAdmission();
                    deregisterAndForget();
                    if (websocket != null) {
                        websocket.close();
                    }
                    transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    return null;
                });
    }

    public CompletableFuture<Registration> rotateAdmissionKey() {
        return submit(
                () -> {
                    requireResolvedOperation();
                    installKeys();
                    if (state.getAsJsonArray("ticketKeys").size() >= 8) {
                        throw new IOException(
                                "Wait for retiring admission epochs before rotating again");
                    }
                    state.addProperty("keyRequestId", UUID.randomUUID().toString());
                    save();
                    heartbeat();
                    return registrationView();
                });
    }

    public CompletableFuture<Registration> rotateMachineKey() {
        return submit(
                () -> {
                    requireResolvedOperation();
                    KeyPair replacement = ProviderCrypto.generate();
                    JsonObject jwk = ProviderCrypto.publicJwk(replacement.getPublic());
                    state.addProperty(
                            "pendingPrivateKey",
                            ProviderCrypto.base64(replacement.getPrivate().getEncoded()));
                    state.add("pendingPublicKeyJwk", jwk);
                    save();
                    String intent = UUID.randomUUID().toString();
                    JsonObject body = new JsonObject();
                    body.add("publicKeyJwk", jwk);
                    body.addProperty(
                            "proof",
                            ProviderCrypto.sign(
                                    replacement.getPrivate(),
                                    ProviderCrypto.array(
                                            ProviderCrypto.PROTOCOL,
                                            "rotate",
                                            origin,
                                            registration("instanceId"),
                                            registration("keyId"),
                                            ProviderCrypto.thumbprint(jwk),
                                            state.get("generation").getAsLong(),
                                            intent)));
                    JsonObject result = signed("rotate", "POST", body, intent);
                    String oldKey = registration("keyId");
                    state.add("privateKey", state.remove("pendingPrivateKey"));
                    state.add("publicKeyJwk", state.remove("pendingPublicKeyJwk"));
                    state.getAsJsonObject("registration")
                            .addProperty("keyId", result.get("keyId").getAsString());
                    save();
                    privateKey = replacement.getPrivate();
                    JsonObject retire = new JsonObject();
                    retire.addProperty("keyId", oldKey);
                    signed("retire", "POST", retire);
                    return registrationView();
                });
    }

    /**
     * Saved state that will not outlive this process could never recover the registration. Clears only the
     * registration state, not the DTLS identity files. Skipped when draining failed, as the provider is unreachable.
     */
    private void deregisterOnShutdown() {
        try {
            deregisterAndForget();
        } catch (Exception e) {
            diagnostics.failed(ProviderLog.Operation.DEREGISTER, safeFailure(e));
        }
    }

    /** A deregistered instance can never recover, so its saved registration goes too; DTLS identity files stay. */
    private void deregisterAndForget() throws Exception {
        signed("deregister", "POST", new JsonObject());
        started = false;
        state = new JsonObject();
        gameOutcomeQueue.clear();
        store.write(state);
    }

    public CompletableFuture<Void> drain() {
        return submit(() -> {
            drainAndReport();
            return null;
        });
    }

    private void drainAndReport() throws Exception {
        disableDiagnosticAdmission();
        if (!hostState.equals("closed")) {
            transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS);
            hostState = "draining";
        }
        heartbeat();
    }

    private JsonObject unsigned(String op, JsonObject body) throws Exception {
        return unsigned(op, body, null);
    }

    private JsonObject unsigned(String op, JsonObject body, String bearerToken) throws Exception {
        return exchange(operation(op), "POST", JSON.toJson(body), false, null, bearerToken);
    }

    private JsonObject signed(String op, String method, JsonObject body) throws Exception {
        return signed(op, method, body, UUID.randomUUID().toString());
    }

    private JsonObject signed(String op, String method, JsonObject body, String intent) throws Exception {
        return signed(op, method, body, intent, () -> {});
    }

    private JsonObject signed(
            String op, String method, JsonObject body, String intent, Runnable requireCurrent)
            throws Exception {
        requireResolvedOperation();
        long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
        state.addProperty("sequence", sequence);
        save();
        URI uri = operation(op);
        return exchange(
                uri,
                method,
                body == null ? null : JSON.toJson(body),
                true,
                intent,
                null,
                op.equals("outcomes") ? 3 : 15,
                op.equals("outcomes") ? 1 : 3,
                requireCurrent);
    }

    private URI operation(String op) throws IOException {
        return trusted(URI.create(origin + "/v1/nxs/" + op));
    }

    private URI trusted(URI uri) throws IOException {
        URI authority = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        if (!ProviderOrigin.canonicalize(authority).equals(origin) || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IOException("Untrusted provider operation");
        }
        return uri;
    }

    private JsonObject exchange(URI uri, String method, String body, boolean signed, String intent,
                                String bearerToken) throws Exception {
        return exchange(uri, method, body, signed, intent, bearerToken, 15, 3);
    }

    private JsonObject exchange(URI uri, String method, String body, boolean signed, String intent, String bearerToken,
                                int timeoutSeconds, int attempts) throws Exception {
        return exchange(
                uri, method, body, signed, intent, bearerToken, timeoutSeconds, attempts, () -> {});
    }

    private JsonObject exchange(
            URI uri,
            String method,
            String body,
            boolean signed,
            String intent,
            String bearerToken,
            int timeoutSeconds,
            int attempts,
            Runnable requireCurrent)
            throws Exception {
        trusted(uri);
        if (signed) {
            requireResolvedOperation();
        }
        String raw = body == null ? "" : body;
        boolean ambiguous = false;
        for (int attempt = 0; attempt < attempts; attempt++) {
            requireCurrent.run();
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("accept", "application/json").method(method,
                            body == null ? HttpRequest.BodyPublishers.noBody() :
                                    HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                b.header("content-type", "application/json");
            }
            if (bearerToken != null) {
                b.header("authorization", "Bearer " + bearerToken);
            }
            if (signed) {
                long now = System.currentTimeMillis();
                long generation = state.get("generation").getAsLong();
                long sequence = state.get("sequence").getAsLong();
                String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                b.header("nxs-instance-id", registration("instanceId"))
                        .header("nxs-key-id", registration("keyId"))
                        .header("nxs-timestamp", Long.toString(now))
                        .header("nxs-signature-version", ProviderCrypto.SIGNATURE)
                        .header("nxs-generation", Long.toString(generation))
                        .header("nxs-sequence", Long.toString(sequence))
                        .header("idempotency-key", intent)
                        .header("nxs-signature", ProviderCrypto.sign(privateKey,
                                ProviderCrypto.request(origin, method, path, now, registration("instanceId"),
                                        registration("keyId"), intent, generation, sequence, raw)));
            }
            HttpRequest request = b.build();
            ProviderWebSocket.Reply response = null;
            String wsOperation = signed ? websocketOperation(uri) : null;
            if (wsOperation != null) {
                try {
                    response =
                            websocket.exchange(
                                    wsOperation,
                                    request,
                                    raw,
                                    websocketUpgradeHeaders(),
                                    timeoutSeconds,
                                    () -> {
                                        requireCurrent.run();
                                        state.addProperty("pendingWebSocketOperation", intent);
                                        try {
                                            save();
                                        } catch (IOException failure) {
                                            throw new UncheckedIOException(failure);
                                        }
                                        try {
                                            requireCurrent.run();
                                        } catch (RuntimeException replaced) {
                                            clearWebSocketPending(); // Callback failed before
                                            // handing any frame to the
                                            // carrier.
                                            throw replaced;
                                        }
                                    },
                                    requireCurrent);
                } catch (IOException | ExecutionException | TimeoutException failure) {
                    // Retry the SAME signed operation over HTTPS; neither intent nor body is
                    // replaced.
                    ambiguous |= state.has("pendingWebSocketOperation");
                    diagnostics.failed(ProviderLog.Operation.WEBSOCKET, safeFailure(failure));
                }
            }
            boolean usedWebSocket = response != null;
            CompletableFuture<HttpResponse<byte[]>> responseFuture = null;
            try {
                if (response == null) {
                    requireCurrent.run();
                    responseFuture =
                            http.sendAsync(request, info -> new LimitedBodySubscriber(65536));
                    HttpResponse<byte[]> httpResponse =
                            responseFuture.get(timeoutSeconds + 1, TimeUnit.SECONDS);
                    response =
                            new ProviderWebSocket.Reply(
                                    httpResponse.statusCode(),
                                    httpResponse.headers(),
                                    new String(httpResponse.body(), StandardCharsets.UTF_8));
                }
            } catch (ExecutionException | TimeoutException failure) {
                if (responseFuture != null) {
                    responseFuture.cancel(true);
                }
                ambiguous = true;
                if (attempt == attempts - 1) {
                    throw new IOException("Provider transport unavailable", failure);
                }
                Thread.sleep((250L << attempt) + ThreadLocalRandom.current().nextLong(100));
                continue;
            }
            String text = response.body();
            int status = response.status();
            String code = "request_rejected";
            if (status / 100 != 2) {
                try {
                    JsonObject error = JsonParser.parseString(text).getAsJsonObject();
                    if (error.has("code")
                            && error.get("code").getAsString().matches("[a-z0-9_]{1,80}")) {
                        code = error.get("code").getAsString();
                    }
                } catch (RuntimeException ignored) {
                }
            }
            // An error response had no effect. A previous ambiguous attempt still needs recovery.
            if (!ambiguous && status / 100 == 4) {
                clearWebSocketPending();
            }
            if (status >= 500) {
                ambiguous = true;
            }
            if ((status == 429 || status == 503 || status == 502 || status == 504)
                    && attempt < attempts - 1) {
                long delay = 250L << attempt;
                try {
                    delay = Math.max(delay, response.headers().firstValueAsLong("retry-after").orElse(0) * 1000);
                } catch (NumberFormatException ignored) {
                }
                if (delay > 10000) {
                    throw new ProviderException(status, "retry_later");
                }
                Thread.sleep(delay + ThreadLocalRandom.current().nextLong(100));
                continue;
            }
            if (status / 100 != 2) {
                if (signed && GENERATION_FENCED.equals(code)) {
                    fenced();
                }
                throw new ProviderException(status, code);
            }
            JsonObject result = JsonParser.parseString(text).getAsJsonObject();
            if (signed) {
                lastControlCarrier = usedWebSocket ? "websocket" : "http";
            }
            if (signed) {
                clearWebSocketPending();
            }
            if (usedWebSocket) {
                diagnostics.recovered(ProviderLog.Operation.WEBSOCKET);
            }
            return result;
        }
        throw new IOException("Provider retry limit exceeded");
    }

    private void requireResolvedOperation() throws IOException {
        if (state.has("pendingWebSocketOperation")) {
            throw new IOException(
                    "Unresolved provider operation; ordinary registration recovery required");
        }
    }

    private void clearWebSocketPending() throws IOException {
        if (state == null || !state.has("pendingWebSocketOperation")) {
            return;
        }
        JsonElement pending = state.remove("pendingWebSocketOperation");
        try {
            save();
        } catch (IOException failure) {
            state.add("pendingWebSocketOperation", pending);
            throw failure;
        }
    }

    private void configureWebSocket() throws IOException {
        if (websocket != null) {
            websocket.close();
        }
        websocket = null;
        websocketEndpoint = null;
        if (config.controlTransport() != ControlTransport.AUTO || !discovery.has("extensions")) {
            return;
        }
        JsonObject extensions = discovery.getAsJsonObject("extensions");
        if (!extensions.has("dev.opencollab.nxs.websocket")) {
            return;
        }
        JsonObject extension = extensions.getAsJsonObject("dev.opencollab.nxs.websocket");
        if (extension.get("version").getAsInt() != 1) {
            return;
        }
        try {
            URI httpEndpoint = trusted(URI.create(origin + "/v1/nxs/control"));
            URI endpoint = URI.create((origin.startsWith("https:") ? "wss" : "ws") + "://"
                    + httpEndpoint.getRawAuthority() + httpEndpoint.getRawPath());
            websocketEndpoint = endpoint;
            websocket =
                    new ProviderWebSocket(
                            http,
                            endpoint,
                            config.assistedJoins() ? this::assistedJoin : null,
                            diagnostics::assistedJoinFailed);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid provider WebSocket capability", failure);
        }
    }

    private String websocketOperation(URI uri) throws IOException {
        if (websocket == null) {
            return null;
        }
        for (String name : List.of("heartbeat", "outcomes", "rotate", "retire", "deregister")) {
            if (uri.equals(operation(name))) {
                return name;
            }
        }
        return null;
    }

    private Map<String, String> websocketUpgradeHeaders() throws GeneralSecurityException {
        long now = System.currentTimeMillis(),
                generation = state.get("generation").getAsLong(),
                sequence = state.get("sequence").getAsLong();
        String intent = UUID.randomUUID().toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("nxs-instance-id", registration("instanceId"));
        headers.put("nxs-key-id", registration("keyId"));
        headers.put("nxs-timestamp", Long.toString(now));
        headers.put("nxs-signature-version", ProviderCrypto.SIGNATURE);
        headers.put("nxs-generation", Long.toString(generation));
        headers.put("nxs-sequence", Long.toString(sequence));
        headers.put("idempotency-key", intent);
        headers.put(
                "nxs-signature",
                ProviderCrypto.sign(
                        privateKey,
                        ProviderCrypto.request(
                                origin,
                                "GET",
                                websocketEndpoint.getRawPath(),
                                now,
                                registration("instanceId"),
                                registration("keyId"),
                                intent,
                                generation,
                                sequence,
                                "")));
        if (assistedMode) {
            headers.put("nxs-assisted", "1");
        }
        return headers;
    }

    private CompletionStage<ProviderWebSocket.AssistedAnswer> assistedJoin(
            org.cloudburstmc.netty.signaling.control.AssistedJoin join) {
        AssistedAuthority captured = assistedAuthority;
        if (captured == null) {
            return CompletableFuture.failedFuture(
                    new IOException("Assisted authority unavailable"));
        }
        Runnable guard =
                () -> {
                    AssistedAuthority current = assistedAuthority;
                    if (!assistedMode
                            || closing.get()
                            || current == null
                            || !captured.instance().equals(current.instance())
                            || captured.generation() != current.generation()
                            || !captured.instance().equals(join.instanceId())
                            || captured.generation() != join.generation()
                            || System.nanoTime() >= captured.deadlineNanos()
                            || System.currentTimeMillis() >= captured.expiresAt()) {
                        throw new IllegalStateException("Assisted authority expired");
                    }
                    captured.profile().requireCurrent();
                    var profile = captured.profile().profile();
                    if (!profile.getAsJsonObject("statelessAdmission")
                                    .get("incarnation")
                                    .getAsString()
                                    .equals(join.incarnation())
                            || !profile.get("dtlsFingerprint")
                                    .getAsString()
                                    .equalsIgnoreCase(join.hostFingerprint())
                            || !profile.get("admissionKeyId").getAsString().equals(join.keyId())
                            || join.expiresAt() > captured.expiresAt()) {
                        throw new IllegalStateException("Assisted binding mismatch");
                    }
                };
        guard.run();
        return transport
                .assistedJoin(join, guard)
                .thenApply(
                        answer -> {
                            guard.run();
                            return new ProviderWebSocket.AssistedAnswer(answer, guard);
                        });
    }

    private String registration(String field) {
        return state.getAsJsonObject("registration").get(field).getAsString();
    }

    private Registration registrationView() {
        JsonObject r = state.getAsJsonObject("registration");
        return new Registration(r.get("registrationId").getAsString(), r.get("instanceId").getAsString(),
                r.get("keyId").getAsString(), r.get("leaseGeneration").getAsLong(),
                r.has("serviceId") ? r.get("serviceId").getAsString() : null,
                r.has("publicAddress") ? r.get("publicAddress").getAsString() : null);
    }

    private void save() throws IOException {
        try {
            store.write(state);
        } catch (IOException failure) {
            diagnostics.failed(ProviderLog.Operation.STORAGE, safeFailure(failure));
            stop();
            throw failure;
        }
    }

    private static String safeFailure(Throwable e) {
        // A future's wrapper says nothing about what failed
        while ((e instanceof ExecutionException || e instanceof CompletionException) && e.getCause() != null) {
            e = e.getCause();
        }
        return e instanceof ProviderException ? e.getMessage() : e.getClass().getSimpleName();
    }

    private <T> CompletableFuture<T> submit(Callable<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                if (closed) {
                    throw new IOException("Provider is closed");
                }
                f.complete(fn.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /** Another process now owns this registration, so stop without draining or deregistering it. */
    private void fenced() {
        if (!fenced) {
            fenced = true;
            started = false;
            diagnostics.replaced();
            stop();
        }
    }

    public CompletionStage<Void> stop() {
        if (!closing.compareAndSet(false, true)) {
            return stopped;
        }
        executor.execute(
                () -> {
                    try {
                        if (started) {
                            drainAndReport();
                            try {
                                flushEvents();
                            } catch (Exception e) {
                                diagnostics.failed(ProviderLog.Operation.EVENTS, safeFailure(e));
                            }
                            if (config.deregisterOnShutdown()) {
                                deregisterOnShutdown();
                            }
                        }
                    } catch (Exception e) {
                        diagnostics.failed(ProviderLog.Operation.DRAIN, safeFailure(e));
                    } finally {
                        closed = true;
                        started = false;
                        if (websocket != null) {
                            websocket.close();
                        }
                        if (timer != null) {
                            timer.cancel(false);
                        }
                        try {
                            transport.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            diagnostics.failed(ProviderLog.Operation.TRANSPORT_CLOSE, safeFailure(e));
                        }
                        try {
                            store.close();
                        } catch (Exception e) {
                            diagnostics.failed(ProviderLog.Operation.STATE_CLOSE, safeFailure(e));
                        }
                        executor.shutdown();
                        stopped.complete(null);
                    }
                });
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }
}
