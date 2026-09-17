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
import org.cloudburstmc.netty.signaling.diagnostic.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One asynchronous, serialized control lifecycle per backend, never one poller per player.
 */
public final class ProviderClient implements AutoCloseable {
    public static final String AUTOMATIC = "automatic";
    public static final String NEW_SERVICE = "new-service";
    public static final String ATTACH_INSTANCE = "attach-instance";
    public static final String ANONYMOUS_PROOF_OF_WORK = "anonymous-proof-of-work";
    public static final String BEARER_TOKEN = "bearer-token";

    public enum ControlTransport { HTTP, AUTO }
    private static final String CONNECTIVITY_EXTENSION = "org.nethernet.connectivity";
    private static final long DIAGNOSTIC_VALIDITY_MILLIS = 300000;

    public record Configuration(URI provider, String profile, String label, String registrationMode,
                                String authorizationScheme,
                                String authorizationToken, String region, String pool, Map<String, String> tags,
                                ControlTransport controlTransport, boolean diagnosticAdmission, String connectivityMethod, boolean assistedJoins) {
        public Configuration {
            Objects.requireNonNull(controlTransport);
            if (assistedJoins && controlTransport != ControlTransport.AUTO) throw new IllegalArgumentException("Assisted joins require WebSocket control");
            if (!Set.of("defined", "discovered").contains(connectivityMethod)) throw new IllegalArgumentException("Unknown connectivity method");
            if (diagnosticAdmission && !"https".equals(provider.getScheme())) throw new IllegalArgumentException("Diagnostics require HTTPS");
            Objects.requireNonNull(provider);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(registrationMode);
            Objects.requireNonNull(authorizationScheme);
            if (!"nxs-admission-v1".equals(profile)) {
                throw new IllegalArgumentException("Unsupported operational profile");
            }

            ProviderCrypto.origin(provider);
            if (region != null && (!region.matches("[A-Za-z0-9_-]{1,32}") || pool == null || !pool.matches(
                    "[A-Za-z0-9_-]{1,64}"))) {
                throw new IllegalArgumentException("Invalid placement");
            }

            tags = tags == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tags));
            if (!Set.of(AUTOMATIC, NEW_SERVICE, ATTACH_INSTANCE).contains(registrationMode)) {
                throw new IllegalArgumentException("Invalid provider registration mode");
            }

            if (!Set.of(ANONYMOUS_PROOF_OF_WORK, BEARER_TOKEN).contains(authorizationScheme)) {
                throw new IllegalArgumentException("Invalid provider authorization scheme");
            }

            if ((BEARER_TOKEN.equals(authorizationScheme)) != (authorizationToken != null
                    && !authorizationToken.isBlank())) {
                throw new IllegalArgumentException("Bearer authorization requires exactly one token");
            }

            if (ANONYMOUS_PROOF_OF_WORK.equals(authorizationScheme) && !Set.of(AUTOMATIC, NEW_SERVICE)
                    .contains(registrationMode)) {
                throw new IllegalArgumentException("Anonymous proof of work can only create a service");
            }

            if (ATTACH_INSTANCE.equals(registrationMode) && (region == null || region.isBlank() || pool == null
                    || pool.isBlank())) {
                throw new IllegalArgumentException("Attached instances require region and pool");
            }

            if ((region == null) != (pool == null) || (!tags.isEmpty() && region == null)) {
                throw new IllegalArgumentException("Provider placement requires region and pool together");
            }

            if (tags.size() > 16 || tags.entrySet().stream().anyMatch(
                    e -> !e.getKey().matches("[A-Za-z0-9_.-]{1,32}") || e.getValue() == null || !e.getValue()
                            .equals(e.getValue().trim()) || e.getValue().isEmpty() || e.getValue().length() > 64
                            || e.getValue().codePoints().anyMatch(c -> c < 32 || c == 127))) {
                throw new IllegalArgumentException("Invalid provider placement tags");
            }
        }

        public Configuration(URI provider, String profile, String label, String registrationMode,
                             String authorizationScheme, String authorizationToken, String region, String pool,
                             Map<String, String> tags, ControlTransport controlTransport, boolean diagnosticAdmission, String connectivityMethod) {
            this(provider, profile, label, registrationMode, authorizationScheme, authorizationToken, region, pool,
                    tags, controlTransport, diagnosticAdmission, connectivityMethod, false);
        }

        public Configuration(URI provider, String profile, String label, String registrationMode,
                             String authorizationScheme, String authorizationToken, String region, String pool,
                             Map<String, String> tags, ControlTransport controlTransport) {
            this(provider, profile, label, registrationMode, authorizationScheme, authorizationToken, region, pool,
                    tags, controlTransport, false, "discovered");
        }

        public Configuration(URI provider, String profile, String label, String registrationMode,
                             String authorizationScheme, String authorizationToken, String region, String pool,
                             Map<String, String> tags) {
            this(provider, profile, label, registrationMode, authorizationScheme, authorizationToken,
                    region, pool, tags, ControlTransport.HTTP);
        }

        public Configuration(URI provider, String profile, String label) {
            this(provider, profile, label, NEW_SERVICE, ANONYMOUS_PROOF_OF_WORK, null, null, null, Map.of());
        }

        @Override
        public String toString() {
            return "Configuration[provider=" + provider + ", profile=" + profile + ", registrationMode="
                    + registrationMode + ", authorizationScheme=" + authorizationScheme + "]";
        }
    }

    /**
     * Sampled actual players on this runtime; keep counting existing players while draining.
     */
    public record PlayerCount(int connectedPlayers, long sampledAt) {
        public PlayerCount {
            if (connectedPlayers < 0 || connectedPlayers > 1000000 || sampledAt < 0 || sampledAt > 9007199254740991L) {
                throw new IllegalArgumentException("Invalid player count sample");
            }
        }
    }

    /**
     * Capacity and playerCount describe the same observation. Public server status is independent.
     */
    public record Health(boolean healthy, boolean acceptingPlayers, int capacity, double load, String protocolVersion, String build,
                         PlayerCount playerCount) {
        public Health {
            if (capacity < 0 || capacity > 1000000 || !Double.isFinite(load) || load < 0 || load > 1
                    || protocolVersion == null) {
                throw new IllegalArgumentException("Invalid health");
            }
        }

        /**
         * Hosts without actual player telemetry report unknown, never a synthetic zero.
         */
        public Health(boolean healthy, boolean acceptingPlayers, int capacity, double load, String protocolVersion, String build) {
            this(healthy, acceptingPlayers, capacity, load, protocolVersion, build, null);
        }
    }

    public static final class ProviderException extends IOException {
        private final int status;

        ProviderException(int status, String code) {
            super("Provider request failed: " + status + " " + code);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    // Null is meaningful inside opaque extension data (for example, clearing a setting).
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final Configuration config;
    private final ProviderStateStore store;
    private final ProviderTransport transport;
    private final Supplier<ServerStatus> statusSupplier;
    private final Supplier<Health> healthSupplier;
    private final Consumer<String> diagnostics;
    private final String origin;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nethernet-provider");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10))
                    .build();
    private final AtomicReference<ServerStatus> explicitStatus = new AtomicReference<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private JsonObject state;
    private JsonObject discovery;
    private JsonObject registrationExtensions = new JsonObject();
    private JsonObject heartbeatExtensions = new JsonObject();
    private PrivateKey privateKey;
    private String profileRevision;
    private JsonObject lastProfile;
    private long publishedCandidateRevision, publishedCandidateVersion;
    private long observedCandidateRevision, observedCandidateVersion, nextCandidateRefresh;

    /** A delivered epoch, kept out of the persisted state until the transport has accepted it. */
    private JsonObject pendingTicketKey;
    private long intervalMs = 10000;
    private long nextHeartbeat;
    private long snapshotClock;
    private boolean started;
    private boolean closed;
    private boolean scheduledCheckIns;
    private long nextOutcomes;
    private long nextStatusUpdate;
    private long minUpdateIntervalMs = 1000;
    private URI websocketEndpoint;
    private ProviderWebSocket websocket;
    private volatile String lastControlCarrier = "none";
    private record AssistedAuthority(String instance, long generation, ProviderTransport.HostProfileSnapshot profile,
                                     long deadlineNanos, long expiresAt) { }
    private volatile AssistedAuthority assistedAuthority;
    private volatile boolean assistedMode;
    private final AssistedFallbackChoice assistedFallbackChoice = new AssistedFallbackChoice();
    private String hostState = "serving";
    private String installedKeyId;
    private List<ProviderTransport.TicketKey> installedTicketKeys = List.of();
    private ProviderTransport.HostProfileSnapshot diagnosticInstalledSnapshot;
    private long diagnosticInstalledDeadline, diagnosticInstalledExpiresAt;
    private JsonObject lastHeartbeat = new JsonObject();
    private ServerStatus lastReportedStatus;
    private Health lastReportedHealth;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private ScheduledFuture<?> timer;

    public ProviderClient(Configuration config, ProviderStateStore store, ProviderTransport transport,
                          Supplier<ServerStatus> statusSupplier, Supplier<Health> healthSupplier,
                          Consumer<String> diagnostics) {
        this.config = config;
        this.store = store;
        this.transport = transport;
        this.statusSupplier = statusSupplier;
        this.healthSupplier = healthSupplier;
        this.diagnostics = diagnostics;
        this.origin = ProviderCrypto.origin(config.provider());
    }

    /** Carrier of the last successful signed operation, independent of advertised configuration. */
    public String lastControlCarrier() { return lastControlCarrier; }

    public CompletableFuture<JsonObject> start() {
        return submit(() -> {
            if (store.read().has("controlMode") || java.nio.file.Files.exists(store.directory().resolve("control-session/provider-state.json")))
                throw new IOException("Experimental control state requires explicit conversion before startup");
            if (started) {
                throw new IllegalStateException("Already started");
            }
            discovery = exchange(URI.create(origin + "/.well-known/nethernet-external-signaling"), "GET", null, false,
                    null, null);
            validateDiscovery();
            configureWebSocket();
            state = store.read();
            if (state.has("provider") && !origin.equals(state.get("provider").getAsString())) {
                throw new IOException("State belongs to another provider; use a separate directory");
            }
            if (!state.has("privateKey")) {
                KeyPair pair = ProviderCrypto.generate();
                state.addProperty("provider", origin);
                state.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded()));
                state.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic()));
                save();
            }
            privateKey = ProviderCrypto.privateKey(state.get("privateKey").getAsString());
            if (!state.has("registration")) {
                enroll();
            } else {
                recoverExisting();
            }
            JsonObject registration = state.getAsJsonObject("registration");
            if (!registration.get("provider").getAsString().equals(origin)) {
                throw new IOException("Registration audience changed");
            }
            state.addProperty("protocol", ProviderCrypto.PROTOCOL);
            state.addProperty("profile", config.profile());
            state.addProperty("generation", registration.get("leaseGeneration").getAsLong());
            state.addProperty("sequence", 0);
            state.remove("cursor");
            state.remove("pendingAdmissions");
            state.remove("pendingWebSocketOperation");
            save();
            installKeys();
            started = true;
            heartbeat();
            timer = executor.scheduleWithFixedDelay(() -> {
                if (closed) {
                    return;
                }
                try {
                    if (started && (System.nanoTime() >= nextHeartbeat || candidatesChanged() || statusChanged())) {
                        heartbeat();
                    }
                } catch (Exception e) {
                    nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    nextStatusUpdate = nextHeartbeat;
                    diagnostics.accept("provider_status_unavailable: " + safeFailure(e));
                }
                if (System.nanoTime() >= nextOutcomes) {
                    try {
                        flushEvents();
                    } catch (Exception e) {
                        nextOutcomes = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        diagnostics.accept("provider_events_unavailable: " + safeFailure(e));
                    }
                }
            }, 1000, 1000, TimeUnit.MILLISECONDS);
            return redactedRegistration();
        });
    }

    private void validateDiscovery() throws IOException {
        ProviderContract.require("discovery", discovery);
        ProtocolExtensions.validate(discovery);
        if (!origin.equals(discovery.get("controlOrigin").getAsString()) || !origin.equals(
                discovery.get("provider").getAsString())) {
            throw new IOException("Discovery provider mismatch");
        }
        for (String[] pair : List.of(new String[]{"protocols", ProviderCrypto.PROTOCOL},
                new String[]{"signatures", ProviderCrypto.SIGNATURE}, new String[]{"profiles", config.profile()},
                new String[]{"modes", config.registrationMode()})) {
            if (!discovery.getAsJsonArray(pair[0]).contains(new JsonPrimitive(pair[1]))) {
                throw new IOException("Unsupported required provider capability: " + pair[0]);
            }
        }
        boolean authorizationSupported = false;
        for (JsonElement item : discovery.getAsJsonObject("authorization").getAsJsonArray("schemes")) {
            JsonObject scheme = item.getAsJsonObject();
            if (config.authorizationScheme().equals(scheme.get("scheme").getAsString()) && scheme.getAsJsonArray(
                    "modes").contains(new JsonPrimitive(config.registrationMode()))) {
                authorizationSupported = true;
            }
        }
        if (!authorizationSupported || !"Authorization".equals(
                discovery.getAsJsonObject("authorization").get("header").getAsString())) {
            throw new IOException("Unsupported provider authorization");
        }
        for (String op : ProviderContract.operations()) {
            if (!discovery.getAsJsonObject("operations").has(op)) {
                throw new IOException("Missing required operation: " + op);
            }
        }
        for (var op : discovery.getAsJsonObject("operations").entrySet()) {
            trusted(URI.create(op.getValue().getAsString()));
        }
        intervalMs = discovery.getAsJsonObject("limits").get("heartbeatIntervalMs").getAsLong();
        if (intervalMs < 1000 || intervalMs > 30000) {
            throw new IOException("Unsupported heartbeat interval");
        }
        JsonObject limits = discovery.getAsJsonObject("limits");
        if (limits.get("maxBodyBytes").getAsLong() < 1 || limits.get("maxBodyBytes").getAsLong() > 65536
                || limits.get("clockSkewMs").getAsLong() < 0 || limits.get("clockSkewMs").getAsLong() > 60000) {
            throw new IOException("Unsupported provider limits");
        }
    }

    private JsonObject recoveryRequest(String registrationId) {
        JsonObject recovery = new JsonObject();
        recovery.addProperty("registrationId", registrationId);
        recovery.addProperty("protocol", ProviderCrypto.PROTOCOL);
        recovery.addProperty("profile", config.profile());
        return recovery;
    }

    private void recoverExisting() throws Exception {
        String registrationId = registration("registrationId");
        completeRecovery(unsigned("register", recoveryRequest(registrationId)), registrationId);
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

    private void completeRecovery(JsonObject challenge, String registrationId) throws Exception {
        requireBoundChallenge(challenge, "Unbound recovery challenge");
        JsonObject context = challenge.getAsJsonObject("context");
        if (!"recover".equals(context.get("mode").getAsString())
                || !config.profile().equals(context.get("profile").getAsString()) || !registrationId.equals(
                context.get("registrationId").getAsString())
                || challenge.get("expiresAt").getAsLong() <= System.currentTimeMillis()
                || !"sha256-leading-zero-bits-v0".equals(
                challenge.getAsJsonObject("pow").get("algorithm").getAsString())
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
        if (!registrationId.equals(recovered.get("registrationId").getAsString())) {
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
        recovered.remove("ticketKey");
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
        JsonObject challenge;
        if (state.has("challenge")) {
            String registrationId = state.getAsJsonObject("challenge").get("challengeId").getAsString();
            JsonObject recoveredChallenge = null;
            try {
                recoveredChallenge = unsigned("register", recoveryRequest(registrationId));
            } catch (ProviderException e) {
                if (e.status != 403) {
                    throw e;
                }
            }
            if (recoveredChallenge != null) {
                completeRecovery(recoveredChallenge, registrationId);
                return;
            }
            challenge = state.getAsJsonObject("challenge");
        } else {
            JsonObject request = new JsonObject();
            request.addProperty("protocol", ProviderCrypto.PROTOCOL);
            request.addProperty("mode", config.registrationMode());
            request.addProperty("profile", config.profile());
            request.add("publicKeyJwk", state.get("publicKeyJwk"));
            if (config.label() != null) {
                request.addProperty("label", config.label());
            }
            JsonObject authorization = new JsonObject();
            authorization.addProperty("scheme", config.authorizationScheme());
            request.add("authorization", authorization);
            if (config.region() != null) {
                JsonObject p = new JsonObject();
                p.addProperty("region", config.region());
                p.addProperty("pool", config.pool());
                if (!config.tags().isEmpty()) {
                    p.add("tags", JSON.toJsonTree(config.tags()));
                }
                request.add("placement", p);
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
        if (!config.profile().equals(context.get("profile").getAsString()) || !acceptsRegistrationMode(
                context.get("mode").getAsString())) {
            throw new IOException("Challenge registration context changed");
        }
        String expectedTagsDigest = ProviderCrypto.tagsDigest(config.tags());
        if (config.region() == null) {
            if (!context.get("region").getAsString().isEmpty() || !context.get("pool").getAsString().isEmpty()
                    || context.has("tagsDigest")) {
                throw new IOException("Challenge placement changed");
            }
        } else if (!config.region().equals(context.get("region").getAsString()) || !config.pool()
                .equals(context.get("pool").getAsString()) ||
                (expectedTagsDigest == null ? context.has("tagsDigest") :
                        !context.has("tagsDigest") || !expectedTagsDigest.equals(
                                context.get("tagsDigest").getAsString()))) {
            throw new IOException("Challenge placement changed");
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
        if (!"sha256-leading-zero-bits-v0".equals(challenge.getAsJsonObject("pow").get("algorithm").getAsString())
                || bits < 0 || bits > 24) {
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
        JsonObject registration = unsigned("complete", completion(challenge, nonce, intent, privateKey));
        validateRegistration(registration);
        registrationExtensions = ProtocolExtensions.copy(registration);
        registration.remove("extensions");
        state.add("registration", registration);
        state.addProperty("generation", registration.get("leaseGeneration").getAsLong());
        state.addProperty("sequence", 0);
        state.add("ticketKeys", new JsonArray());
        state.remove("challenge");
        if (registration.has("ticketKey")) {
            pendingTicketKey = registration.remove("ticketKey").getAsJsonObject();
        }
        save();
    }

    private boolean acceptsRegistrationMode(String selected) {
        if (!AUTOMATIC.equals(config.registrationMode())) {
            return config.registrationMode().equals(selected);
        }
        return NEW_SERVICE.equals(selected) || (BEARER_TOKEN.equals(config.authorizationScheme())
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
        if (!origin.equals(registration.get("provider").getAsString()) || !config.profile()
                .equals(registration.get("profile").getAsString())) {
            throw new IOException("Registration provider or profile changed");
        }
        JsonObject placement = registration.getAsJsonObject("placement");
        String expectedRegion = config.region() == null ? "" : config.region(), expectedPool =
                config.pool() == null ? "" : config.pool();
        if (!expectedRegion.equals(placement.get("region").getAsString()) || !expectedPool.equals(
                placement.get("pool").getAsString())) {
            throw new IOException("Registration placement changed");
        }
        JsonObject expectedTags = JSON.toJsonTree(config.tags()).getAsJsonObject();
        JsonObject actualTags = placement.has("tags") ? placement.getAsJsonObject("tags") : new JsonObject();
        if (!expectedTags.equals(actualTags)) {
            throw new IOException("Registration placement tags changed");
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
        if (pendingTicketKey != null) {
            retained.add(pendingTicketKey);
        }
        if (retained.size() > 8) {
            throw new IOException("Too many admission key epochs");
        }
        if (retained.isEmpty()) {
            disableDiagnosticAdmission();
            installedTicketKeys = List.of();
            state.add("ticketKeys", retained);
            installedKeyId = null;
            if (!state.has("keyRequestId")) {
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
            }
            save();
            return;
        }
        // Reading the epochs is what rejects a malformed one, so it happens before anything is written
        List<ProviderTransport.TicketKey> keys = new ArrayList<>();
        for (JsonElement entry : retained) {
            JsonObject key = entry.getAsJsonObject();
            keys.add(new ProviderTransport.TicketKey(key.get("keyId").getAsString(), key.get("secret").getAsString(),
                    key.has("notBefore") ? key.get("notBefore").getAsLong() : 0,
                    key.has("retireAfter") ? key.get("retireAfter").getAsLong() : Long.MAX_VALUE));
        }
        if (!keys.equals(installedTicketKeys)) disableDiagnosticAdmission();
        transport.installTicketKeys(List.copyOf(keys)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Only an epoch set the transport accepted is recorded, so the next heartbeat cannot advertise
        // a key that was never installed, and a bad one cannot survive a restart
        state.add("ticketKeys", retained);
        pendingTicketKey = null;
        save();
        installedKeyId = keys.get(keys.size() - 1).keyId();
        installedTicketKeys = List.copyOf(keys);
    }

    /**
     * A full immutable snapshot. Callers may update every one of the seven fields.
     */
    public void setServerStatus(ServerStatus status) {
        explicitStatus.set(Objects.requireNonNull(status));
        requestStatusRefresh();
    }

    /**
     * Wake local observation on join/leave/reload; unchanged snapshots never create network traffic.
     */
    public void requestStatusRefresh() {
        if (!closing.get() && refreshQueued.compareAndSet(false, true)) {
            executor.execute(() -> {
                refreshQueued.set(false);
                if (!closed && started) {
                    try {
                        if (statusChanged()) {
                            heartbeat();
                        }
                    } catch (Exception e) {
                        nextStatusUpdate = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        diagnostics.accept("provider_status_unavailable: " + safeFailure(e));
                    }
                }
            });
        }
    }

    private ServerStatus currentStatus() {
        ServerStatus s = explicitStatus.get();
        return s == null && statusSupplier != null ? statusSupplier.get() : s;
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
                || health.healthy() != lastReportedHealth.healthy()
                || health.acceptingPlayers() != lastReportedHealth.acceptingPlayers()
                || health.capacity() != lastReportedHealth.capacity()
                || !Objects.equals(connectedPlayers(health), connectedPlayers(lastReportedHealth))
                || !Objects.equals(health.protocolVersion(), lastReportedHealth.protocolVersion()) || !Objects.equals(
                health.build(), lastReportedHealth.build());
    }

    private static Integer connectedPlayers(Health health) {
        return health.playerCount() == null ? null : health.playerCount().connectedPlayers();
    }

    /** Sample the cheap local counter; material replacement bypasses freshness-only coalescing. */
    private boolean candidatesChanged() throws Exception {
        if (System.nanoTime() < nextStatusUpdate || !hostState.equals("serving")) return false;
        long version = transport.candidatePublicationVersion();
        if (version == 0 || version == publishedCandidateVersion) return false;
        if (version != observedCandidateVersion) {
            var snapshot = transport.captureHostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
            snapshot.requireCurrent();
            observedCandidateVersion = snapshot.publicationVersion();
            observedCandidateRevision = snapshot.candidateRevision();
        }
        return observedCandidateRevision != publishedCandidateRevision || System.nanoTime() >= nextCandidateRefresh;
    }

    private void heartbeat() throws Exception {
        if (state.has("pendingWebSocketOperation")) {
            assistedAuthority = null;
            // An ambiguous send may have outlived its native mapping. Recover the existing registration
            // before issuing fresh state; do not replay expired profile bytes or stall until process restart.
            disableDiagnosticAdmission();
            if (websocket != null) websocket.close();
            recoverExisting();
            clearWebSocketPending();
            configureWebSocket();
            profileRevision = null;
            lastProfile = null;
            installKeys();
        }
        // Key delivery and local diagnostic installation can need an immediate second exchange.
        for (int exchange = 0; exchange < 3; exchange++) try {
            JsonObject body = new JsonObject(), profile = null;
            ProviderTransport.HostProfileSnapshot profileSnapshot = null, diagnosticSnapshot = null;
            if (installedKeyId != null && hostState.equals("serving")) {
                try {
                    profileSnapshot = transport.captureHostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    profileSnapshot.requireCurrent();
                    profile = profileSnapshot.profile();
                    updateAssistedMode(profileSnapshot);
                    if (assistedMode) {
                        if (!transport.supportsAssistedJoins()) throw new IOException("Assisted transport unavailable");
                        profile.getAsJsonObject("statelessAdmission").addProperty("assisted", "nethernet.websocket-assisted.v1");
                    }
                    if (config.diagnosticAdmission()) {
                        if (!transport.supportsDiagnosticAdmission()) throw new IOException("Diagnostic transport unavailable");
                        if (diagnosticProfile(profileSnapshot)) diagnosticSnapshot = profileSnapshot;
                        else disableDiagnosticAdmission();
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
            if (installedKeyId != null) {
                JsonArray installed = new JsonArray();
                for (JsonElement key : state.getAsJsonArray("ticketKeys")) {
                    installed.add(key.getAsJsonObject().get("keyId"));
                }
                body.add("installedKeyIds", installed);
            }
            if (state.has("keyRequestId")) {
                body.add("keyRequestId", state.get("keyRequestId"));
            }
            Health health = healthSupplier.get();
            body.addProperty("healthy", health.healthy());
            body.addProperty("acceptingPlayers", health.acceptingPlayers() && installedKeyId != null && hostState.equals("serving"));
            JsonObject extensions = heartbeatExtensions.deepCopy();
            boolean diagnosticsAdvertised = diagnosticSnapshot != null && diagnosticInstallationCurrent(diagnosticSnapshot);
            if (diagnosticsAdvertised || assistedMode && profileSnapshot != null && profileSnapshot.candidateRevision() > 0) {
                profileSnapshot.requireCurrent();
                var data = new JsonObject();
                data.addProperty("diagnostics", diagnosticsAdvertised);
                data.addProperty("candidateRevision", profileSnapshot.candidateRevision());
                data.addProperty("method", assistedMode ? "per_join" : profile.getAsJsonArray("candidates").asList().stream()
                        .anyMatch(item -> "srflx".equals(item.getAsJsonObject().get("type").getAsString()))
                        ? "warm_stun" : config.connectivityMethod());
                var extension = new JsonObject();
                extension.addProperty("version", 1);
                extension.addProperty("critical", false);
                extension.add("data", data);
                extensions.add(CONNECTIVITY_EXTENSION, extension);
            } else if (diagnosticSnapshot == null || diagnosticInstalledSnapshot != null) disableDiagnosticAdmission();
            if (!extensions.isEmpty()) body.add("extensions", extensions);
            ProtocolExtensions.validate(body);
            body.addProperty("capacity", health.capacity());
            body.addProperty("load", health.load());
            if (health.playerCount() != null) {
                body.add("playerCount", JSON.toJsonTree(health.playerCount()));
            }
            body.addProperty("protocolVersion", health.protocolVersion());
            if (health.build() != null) body.addProperty("build", health.build());
            if (config.region() != null) {
                body.addProperty("region", config.region());
            }
            snapshotClock = Math.max(System.currentTimeMillis(), snapshotClock + 1);
            body.addProperty("clockUnixMillis", snapshotClock);
            body.addProperty("checkInVersion", 1);
            body.addProperty("state", hostState);
            body.addProperty("gameOutcomes", transport.supportsGameOutcomes() ? "available" : "unavailable");
            ServerStatus status = null;
            try {
                status = currentStatus();
                if (status != null) {
                    body.add("serverStatus", JSON.toJsonTree(status));
                }
            } catch (RuntimeException failure) {
                diagnostics.accept("status_refresh_failed");
            }
            long requestStarted = System.nanoTime();
            var captured = profileSnapshot;
            JsonObject response = signed("heartbeat", "POST", body, UUID.randomUUID().toString(),
                    captured == null ? () -> { } : captured::requireCurrent);
            long diagnosticSuccessNanos = System.nanoTime(), diagnosticSuccessMillis = System.currentTimeMillis();
            ProtocolExtensions.validate(response);
            boolean again = false;
            if (profileSnapshot != null) {
                try { profileSnapshot.requireCurrent(); }
                catch (ProviderTransport.HostProfileSnapshotChangedException replaced) {
                    invalidatePublishedProfile();
                    // The operation committed. Keep its keys and lease, but grant no authority to stale endpoints.
                    profileSnapshot = null;
                    diagnosticSnapshot = null;
                    again = true;
                } catch (RuntimeException unavailable) {
                    invalidatePublishedProfile();
                    throw unavailable;
                }
            }
            if (config.assistedJoins()) {
                assistedAuthority = assistedMode && lastControlCarrier.equals("websocket") && profileSnapshot != null && health.healthy() && health.acceptingPlayers() && hostState.equals("serving")
                        ? new AssistedAuthority(registration("instanceId"), state.get("generation").getAsLong(), profileSnapshot,
                            requestStarted + TimeUnit.MINUTES.toNanos(5), snapshotClock + 300000) : null;
            }
            if (body.has("hostProfile")) {
                if (!response.has("hostProfileRevision") || response.get("hostProfileRevision").isJsonNull()) {
                    throw new IOException("Profile acknowledgement missing");
                }
                if (profileSnapshot != null) {
                    profileRevision = response.get("hostProfileRevision").getAsString();
                    lastProfile = profile.deepCopy();
                    state.addProperty("profilePublishedAt", System.currentTimeMillis());
                    save();
                }
            }
            if (response.has("ticketKey")) {
                JsonObject key = response.remove("ticketKey").getAsJsonObject();
                if (!state.has("keyRequestId") || !response.has("keyRequest") ||
                        !state.get("keyRequestId").equals(response.getAsJsonObject("keyRequest").get("id")) ||
                        !key.get("keyId").equals(response.getAsJsonObject("keyRequest").get("keyId"))) {
                    throw new IOException("Unbound admission key response");
                }
                pendingTicketKey = key;
                JsonElement pendingRequest = state.remove("keyRequestId");
                try {
                    installKeys();
                } catch (Exception e) {
                    // A key the transport will not take must not outlive this heartbeat, in memory or on disk
                    pendingTicketKey = null;
                    if (pendingRequest != null) {
                        state.add("keyRequestId", pendingRequest);
                    }
                    throw e;
                }
                lastProfile = null;
                again = true;
            } else if (state.has("keyRequestId") && response.has("keyRequest") &&
                    state.get("keyRequestId").equals(response.getAsJsonObject("keyRequest").get("id"))) {
                // The provider confirms delivery but the one-time response was lost.
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
                save();
                again = true;
            }
            if (response.has("retirements") && !response.getAsJsonArray("retirements").isEmpty()) {
                for (JsonElement retirement : response.getAsJsonArray("retirements")) {
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
                long received = Instant.parse(response.get("receivedAt").getAsString()).toEpochMilli();
                long remaining = Math.min(schedule.afterMillis(), Math.max(0,
                        response.getAsJsonObject("checkIn").get("nextCheckInAt").getAsLong() - Math.max(received,
                                System.currentTimeMillis())));
                nextHeartbeat = Math.min(requestStarted + TimeUnit.MILLISECONDS.toNanos(schedule.afterMillis()),
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining));
            } else {
                scheduledCheckIns = false;
                nextHeartbeat = requestStarted + TimeUnit.MILLISECONDS.toNanos(intervalMs);
            }
            nextStatusUpdate = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                    scheduledCheckIns ? minUpdateIntervalMs : intervalMs);
            lastReportedStatus = status;
            lastReportedHealth = health;
            lastHeartbeat = response.deepCopy();
            if (profileSnapshot != null) {
                publishedCandidateRevision = profileSnapshot.candidateRevision();
                publishedCandidateVersion = profileSnapshot.publicationVersion();
                nextCandidateRefresh = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            }
            if (diagnosticSnapshot != null) {
                configureDiagnosticAdmission(diagnosticSnapshot, response, diagnosticSuccessMillis, diagnosticSuccessNanos);
                if (!diagnosticsAdvertised) again = true;
                long remaining = Math.max(0, diagnosticInstalledDeadline - System.nanoTime());
                long lead = Math.min(TimeUnit.SECONDS.toNanos(60), remaining / 5);
                nextHeartbeat = Math.min(nextHeartbeat, Math.max(nextStatusUpdate, diagnosticInstalledDeadline - lead));
            }
            var ownedAssistance = assistedAuthority;
            if (ownedAssistance != null) {
                long remaining = Math.max(0, ownedAssistance.deadlineNanos() - System.nanoTime());
                long lead = Math.min(TimeUnit.SECONDS.toNanos(60), remaining / 5);
                nextHeartbeat = Math.min(nextHeartbeat, Math.max(System.nanoTime(), ownedAssistance.deadlineNanos() - lead));
            }
            if (profileSnapshot != null) {
                reportConnectivityFeedback(profileSnapshot, response);
                if (config.assistedJoins()) {
                    var latest = transport.captureHostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    latest.requireCurrent();
                    if (assistedNeeded(latest) != assistedMode) again = true;
                }
            }
            // Serving state belongs to this host. Provider routing decisions never command its listener.
            if (!again) {
                return;
            }
        } catch (ProviderTransport.HostProfileSnapshotChangedException replaced) {
            invalidatePublishedProfile();
            // An ambiguous carrier attempt must follow ordinary recovery before any fresh operation.
            if (state.has("pendingWebSocketOperation")) break;
        }
        nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    }

    private void invalidatePublishedProfile() throws Exception {
        lastProfile = null;
        profileRevision = null;
        assistedAuthority = null;
        disableDiagnosticAdmission();
    }

    private static boolean diagnosticProfile(ProviderTransport.HostProfileSnapshot snapshot) {
        JsonObject profile = snapshot.profile();
        if (snapshot.candidateRevision() < 1 || !profile.has("statelessAdmission") || !profile.has("candidates")) return false;
        JsonArray candidates = profile.getAsJsonArray("candidates");
        if (candidates.isEmpty() || candidates.size() > 32) return false;
        for (JsonElement item : candidates) {
            JsonObject candidate = item.getAsJsonObject();
            String type = candidate.get("type").getAsString();
            if (!"udp".equals(candidate.get("protocol").getAsString()) || !Set.of("host", "srflx").contains(type)) return false;
            if (type.equals("srflx") && (!candidate.has("expiresAt")
                    || candidate.get("expiresAt").getAsBigDecimal().longValueExact() <= System.currentTimeMillis())) return false;
        }
        return true;
    }

    private void reportConnectivityFeedback(ProviderTransport.HostProfileSnapshot snapshot, JsonObject response) {
        if (snapshot.candidateRevision() < 1 || !response.has("extensions")) return;
        try {
            JsonObject extensions = response.getAsJsonObject("extensions");
            if (!extensions.has(CONNECTIVITY_EXTENSION)) return;
            JsonObject extension = extensions.getAsJsonObject(CONNECTIVITY_EXTENSION);
            if (extension.get("version").getAsInt() != 1) return;
            JsonObject data = extension.getAsJsonObject("data");
            if (!data.has("checks") || data.get("candidateRevision").getAsBigDecimal().longValueExact() != snapshot.candidateRevision()) return;
            JsonArray checks = data.getAsJsonArray("checks");
            if (checks.size() > 6) throw new IllegalArgumentException("Connectivity feedback bound");
            var parsed = new ArrayList<ProviderTransport.ConnectivityCheck>();
            long now = System.currentTimeMillis();
            for (JsonElement item : checks) {
                var check = item.getAsJsonObject();
                var outcome = switch (check.get("outcome").getAsString()) {
                    case "established" -> ProviderTransport.ConnectivityOutcome.ESTABLISHED;
                    case "not-established" -> ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED;
                    case "unknown" -> ProviderTransport.ConnectivityOutcome.UNKNOWN;
                    default -> throw new IllegalArgumentException("Unknown connectivity outcome");
                };
                var value = new ProviderTransport.ConnectivityCheck(check.get("family").getAsBigDecimal().intValueExact(), outcome,
                        check.get("checkedAt").getAsBigDecimal().longValueExact(), check.get("expiresAt").getAsBigDecimal().longValueExact());
                if (value.checkedAt() <= now && value.expiresAt() > now) parsed.add(value);
            }
            snapshot.requireCurrent();
            if (config.assistedJoins()) assistedFallbackChoice.report(registration("instanceId"),
                    state.get("generation").getAsLong(), snapshot, parsed, now);
            transport.reportConnectivityChecks(snapshot.candidateRevision(), parsed).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception unavailable) {
            // Optional observations must not undo a successful ordinary heartbeat or change host serving state.
            diagnostics.accept("provider_connectivity_feedback_unavailable");
        }
    }

    private void updateAssistedMode(ProviderTransport.HostProfileSnapshot snapshot) throws IOException {
        boolean needed = assistedNeeded(snapshot);
        if (needed == assistedMode) return;
        assistedMode = needed;
        assistedAuthority = null;
        lastProfile = null;
        configureWebSocket(); // Between operations: reconnect with or without the assisted-host address.
    }

    private boolean assistedNeeded(ProviderTransport.HostProfileSnapshot snapshot) {
        if (!config.assistedJoins() || websocketEndpoint == null || !transport.supportsAssistedJoins()) return false;
        return assistedFallbackChoice.needed(registration("instanceId"), state.get("generation").getAsLong(),
                snapshot, transport.assistedFallbackReadyFamilies(), System.currentTimeMillis());
    }

    /** Local connectivity choice only. Every join still needs its separate, bounded AssistedAuthority. */
    static final class AssistedFallbackChoice {
        private record Binding(String instance, long generation, String incarnation, String fingerprint, long revision) { }
        private Binding binding;
        private final Map<Integer, Long> failedAt = new HashMap<>();
        private final Map<Integer, Long> establishedAt = new HashMap<>();

        private JsonObject bind(String instance, long generation, ProviderTransport.HostProfileSnapshot snapshot) {
            try { snapshot.requireCurrent(); }
            catch (RuntimeException unavailable) {
                binding = null; failedAt.clear(); establishedAt.clear();
                throw unavailable;
            }
            var profile = snapshot.profile();
            var current = new Binding(instance, generation,
                    profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString(),
                    profile.get("dtlsFingerprint").getAsString(), snapshot.candidateRevision());
            if (!current.equals(binding)) {
                failedAt.clear(); establishedAt.clear(); binding = current;
            }
            return profile;
        }

        void report(String instance, long generation, ProviderTransport.HostProfileSnapshot snapshot,
                    List<ProviderTransport.ConnectivityCheck> checks, long now) {
            bind(instance, generation, snapshot);
            if (snapshot.candidateRevision() < 1) return;
            for (int family : List.of(4, 6)) {
                var fresh = checks.stream().filter(check -> check.family() == family
                        && check.checkedAt() <= now && check.expiresAt() > now).toList();
                var established = fresh.stream().filter(check -> check.outcome() == ProviderTransport.ConnectivityOutcome.ESTABLISHED)
                        .mapToLong(ProviderTransport.ConnectivityCheck::checkedAt).max();
                if (established.isPresent()) {
                    // An older regional success cannot undo a subsequently selected failure.
                    // Equal timestamps do not establish recovery from an existing fallback.
                    if (established.getAsLong() > failedAt.getOrDefault(family, -1L)) failedAt.remove(family);
                    establishedAt.merge(family, established.getAsLong(), Math::max);
                } else {
                    fresh.stream().filter(check -> check.outcome() == ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED
                                    && check.checkedAt() > establishedAt.getOrDefault(family, -1L))
                            .mapToLong(ProviderTransport.ConnectivityCheck::checkedAt).max()
                            .ifPresent(checkedAt -> failedAt.merge(family, checkedAt, Math::max));
                }
                // Missing, unknown or expired observations cannot undo an already selected fallback.
            }
        }

        boolean needed(String instance, long generation, ProviderTransport.HostProfileSnapshot snapshot,
                       Set<Integer> ready, long now) {
            // A refreshed same-mapping capture may be current after the old observation lease expired.
            return assistedFallbackNeeded(bind(instance, generation, snapshot), ready, failedAt.keySet(), now);
        }
    }

    private static boolean assistedFallbackNeeded(JsonObject profile, Set<Integer> ready,
                                                  Set<Integer> failedFamilies, long now) {
        for (int family : ready) {
            boolean publicCandidate = false;
            for (var item : profile.getAsJsonArray("candidates")) {
                var candidate = item.getAsJsonObject();
                if (candidate.has("expiresAt") && candidate.get("expiresAt").getAsLong() <= now) continue;
                try {
                    var address = org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(candidate.get("address").getAsString());
                    if ((address instanceof java.net.Inet4Address ? 4 : 6) == family
                            && org.cloudburstmc.netty.util.nethernet.EndpointAddress.scope(address)
                            == org.cloudburstmc.netty.util.nethernet.EndpointAddress.Scope.PUBLIC) publicCandidate = true;
                } catch (java.net.UnknownHostException malformed) { return false; }
            }
            if (!publicCandidate) return true;
            if (failedFamilies.contains(family)) return true;
        }
        return false;
    }

    private boolean diagnosticInstallationCurrent(ProviderTransport.HostProfileSnapshot snapshot) {
        if (diagnosticInstalledSnapshot == null || closing.get() || closed
                || diagnosticInstalledDeadline - System.nanoTime() <= 0 || System.currentTimeMillis() >= diagnosticInstalledExpiresAt
                || diagnosticInstalledSnapshot.candidateRevision() != snapshot.candidateRevision()) return false;
        try {
            diagnosticInstalledSnapshot.requireCurrent();
            snapshot.requireCurrent();
            return diagnosticInstalledSnapshot.profile().get("statelessAdmission").equals(snapshot.profile().get("statelessAdmission"));
        } catch (RuntimeException replaced) { return false; }
    }

    private void disableDiagnosticAdmission() throws Exception {
        diagnosticInstalledSnapshot = null;
        diagnosticInstalledDeadline = diagnosticInstalledExpiresAt = 0;
        if (config.diagnosticAdmission() && transport.supportsDiagnosticAdmission())
            transport.disableDiagnostics().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private void configureDiagnosticAdmission(ProviderTransport.HostProfileSnapshot snapshot, JsonObject response,
                                               long successMillis, long successNanos) throws Exception {
        try {
            if (closing.get() || closed || !hostState.equals("serving")) throw new IOException("Diagnostic host no longer serving");
            snapshot.requireCurrent();
            JsonObject profile = snapshot.profile();
            long expiresAt = successMillis + DIAGNOSTIC_VALIDITY_MILLIS;
            if (response.has("checkIn")) expiresAt = Math.min(expiresAt,
                    response.getAsJsonObject("checkIn").get("leaseExpiresAt").getAsLong());
            long lifetime = expiresAt - successMillis;
            if (lifetime <= 0) throw new IOException("Diagnostic heartbeat lease expired");
            long deadline = successNanos + TimeUnit.MILLISECONDS.toNanos(lifetime);
            if (deadline - System.nanoTime() <= 0) throw new IOException("Diagnostic heartbeat lease expired");
            var context = new DiagnosticAdmissionCodec.Context(origin, registration("instanceId"),
                    profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString(), state.get("generation").getAsLong());
            var keys = installedTicketKeys.stream().map(key -> new DiagnosticAdmissionCodec.Key(key.keyId(), key.secret(),
                    key.notBefore(), Math.min(key.retireAfter(), 9007199254740991L))).toList();
            var endpointExpiries = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
            for (JsonElement item : profile.getAsJsonArray("candidates")) {
                JsonObject candidate = item.getAsJsonObject();
                var address = org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(candidate.get("address").getAsString());
                int family = address instanceof java.net.Inet6Address ? 6 : 4;
                var endpoint = new DiagnosticHostPolicy.Endpoint(family, DiagnosticAdmissionCodec.address(family, address.getHostAddress()),
                        candidate.get("port").getAsBigDecimal().intValueExact(), snapshot.candidateRevision());
                long endpointExpiry = "srflx".equals(candidate.get("type").getAsString())
                        ? Math.min(expiresAt, candidate.get("expiresAt").getAsBigDecimal().longValueExact()) : expiresAt;
                if (endpointExpiry <= System.currentTimeMillis()) throw new IOException("Diagnostic endpoint expired");
                endpointExpiries.merge(endpoint, endpointExpiry, Math::min);
            }
            snapshot.requireCurrent();
            transport.configureDiagnostics(new DiagnosticHostPolicy(context, keys, endpointExpiries.keySet(), expiresAt, endpointExpiries), deadline)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            snapshot.requireCurrent();
            if (closing.get() || closed || deadline - System.nanoTime() <= 0) throw new IOException("Diagnostic installation expired");
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
        List<JsonObject> fresh = transport.pollEvents();
        if (fresh.size() > 100 || pending.size() + fresh.size() > 1000) {
            throw new IOException("Transport event queue exceeds limit");
        }
        for (JsonObject event : fresh) {
            // Persist only the existing redacted telemetry fields, never native SDP or secret extensions.
            JsonObject safe = new JsonObject();
            for (String field : List.of("stage", "ticketId", "occurredAt", "reason", "remoteAddress", "remotePort")) {
                if (event.has(field)) {
                    safe.add(field, event.get(field));
                }
            }
            if (!safe.has("stage") || !safe.has("ticketId") || !safe.has("occurredAt")) {
                throw new IOException("Malformed transport event");
            }
            pending.add(safe);
        }
        if (pending.isEmpty()) {
            return;
        }
        save();
        int sent = Math.min(100, pending.size());
        JsonArray batch = new JsonArray();
        pending.asList().subList(0, sent).forEach(batch::add);
        JsonObject body = new JsonObject();
        body.add("events", batch);
        signed("outcomes", "POST", body);
        // Dropped by position, and only once the exchange is done, so no view spans the request
        pending.asList().subList(0, sent).clear();
        save();
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
        if (validated.has(CONNECTIVITY_EXTENSION)) throw new IllegalArgumentException("Connectivity extension is owned by this client");
        return submit(() -> {
            heartbeatExtensions = validated;
            if (started) heartbeat();
            return null;
        });
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
        return submit(() -> {
            if (!Set.of("GET", "POST").contains(method)) {
                throw new IOException("Unsupported extension method");
            }
            JsonObject extension = ProtocolExtensions.copy(discovery).getAsJsonObject(namespace);
            if (extension == null) {
                throw new IOException("Extension unavailable");
            }
            JsonObject operations = extension.getAsJsonObject("data").getAsJsonObject("operations");
            if (operations == null || !operations.has(operation)) {
                throw new IOException("Extension operation unavailable");
            }
            URI uri = trusted(URI.create(operations.get(operation).getAsString()));
            requireResolvedOperation();
            long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
            state.addProperty("sequence", sequence);
            save();
            JsonObject response =
                    exchange(uri, method, body == null ? null : JSON.toJson(body), true, UUID.randomUUID().toString(),
                            null);
            ProtocolExtensions.validate(response);
            return response;
        });
    }

    public CompletableFuture<Void> deregister() {
        return submit(() -> {
            disableDiagnosticAdmission();
            signed("deregister", "POST", new JsonObject());
            if (websocket != null) websocket.close();
            transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS);
            started = false;
            return null;
        });
    }

    public CompletableFuture<JsonObject> rotateTicketKey() {
        return submit(() -> {
            requireResolvedOperation();
            installKeys();
            if (state.getAsJsonArray("ticketKeys").size() >= 8) {
                throw new IOException("Wait for retiring admission epochs before rotating again");
            }
            state.addProperty("keyRequestId", UUID.randomUUID().toString());
            save();
            heartbeat();
            return redactedRegistration();
        });
    }

    public CompletableFuture<JsonObject> rotateMachineKey() {
        return submit(() -> {
            requireResolvedOperation();
            KeyPair replacement = ProviderCrypto.generate();
            JsonObject jwk = ProviderCrypto.publicJwk(replacement.getPublic());
            state.addProperty("pendingPrivateKey", ProviderCrypto.base64(replacement.getPrivate().getEncoded()));
            state.add("pendingPublicKeyJwk", jwk);
            save();
            String intent = UUID.randomUUID().toString();
            JsonObject body = new JsonObject();
            body.add("publicKeyJwk", jwk);
            body.addProperty("proof", ProviderCrypto.sign(replacement.getPrivate(),
                    ProviderCrypto.array(ProviderCrypto.PROTOCOL, "rotate", origin, registration("instanceId"),
                            registration("keyId"), ProviderCrypto.thumbprint(jwk), state.get("generation").getAsLong(),
                            intent)));
            JsonObject result = signed("rotate", "POST", body, intent);
            String oldKey = registration("keyId");
            state.add("privateKey", state.remove("pendingPrivateKey"));
            state.add("publicKeyJwk", state.remove("pendingPublicKeyJwk"));
            state.getAsJsonObject("registration").addProperty("keyId", result.get("keyId").getAsString());
            save();
            privateKey = replacement.getPrivate();
            JsonObject retire = new JsonObject();
            retire.addProperty("keyId", oldKey);
            signed("retire", "POST", retire);
            return result;
        });
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
        return signed(op, method, body, intent, () -> { });
    }

    private JsonObject signed(String op, String method, JsonObject body, String intent, Runnable requireCurrent) throws Exception {
        requireResolvedOperation();
        long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
        state.addProperty("sequence", sequence);
        save();
        URI uri = operation(op);
        return exchange(uri, method, body == null ? null : JSON.toJson(body), true, intent, null,
                op.equals("outcomes") ? 3 : 15, op.equals("outcomes") ? 1 : 3, requireCurrent);
    }

    private URI operation(String op) throws IOException {
        if (!discovery.getAsJsonObject("operations").has(op)) {
            throw new IOException("Missing provider operation: " + op);
        }
        return trusted(URI.create(discovery.getAsJsonObject("operations").get(op).getAsString()));
    }

    private URI trusted(URI uri) throws IOException {
        URI authority = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        if (!ProviderCrypto.origin(authority).equals(origin) || uri.getUserInfo() != null
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
        return exchange(uri, method, body, signed, intent, bearerToken, timeoutSeconds, attempts, () -> { });
    }

    private JsonObject exchange(URI uri, String method, String body, boolean signed, String intent, String bearerToken,
                                int timeoutSeconds, int attempts, Runnable requireCurrent) throws Exception {
        trusted(uri);
        if (signed) requireResolvedOperation();
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
                    response = websocket.exchange(wsOperation, request, raw, websocketUpgradeHeaders(), timeoutSeconds,
                            () -> {
                                requireCurrent.run();
                                state.addProperty("pendingWebSocketOperation", intent);
                                try { save(); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                                try { requireCurrent.run(); }
                                catch (RuntimeException replaced) {
                                    clearWebSocketPending(); // Callback failed before handing any frame to the carrier.
                                    throw replaced;
                                }
                            }, requireCurrent);
                } catch (IOException | ExecutionException | TimeoutException failure) {
                    // Retry the SAME signed operation over HTTPS; neither intent nor body is replaced.
                    ambiguous |= state.has("pendingWebSocketOperation");
                    diagnostics.accept("provider_websocket_unavailable");
                }
            }
            boolean usedWebSocket = response != null;
            CompletableFuture<HttpResponse<byte[]>> responseFuture = null;
            try {
                if (response == null) {
                    requireCurrent.run();
                    responseFuture = http.sendAsync(request, info -> new LimitedBodySubscriber(65536));
                    HttpResponse<byte[]> httpResponse = responseFuture.get(timeoutSeconds + 1, TimeUnit.SECONDS);
                    response = new ProviderWebSocket.Reply(httpResponse.statusCode(), httpResponse.headers(),
                            new String(httpResponse.body(), StandardCharsets.UTF_8));
                }
            } catch (ExecutionException | TimeoutException failure) {
                if (responseFuture != null) responseFuture.cancel(true);
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
                    if (error.has("code") && error.get("code").getAsString().matches("[a-z0-9_]{1,80}"))
                        code = error.get("code").getAsString();
                } catch (RuntimeException ignored) { }
            }
            // These responses reject before mutation. A previous ambiguous attempt still needs recovery.
            if (!ambiguous && (status == 429 || (status / 100 == 4
                    && Set.of("check_in_profile_required", "profile_key_not_installed", "invalid_heartbeat",
                    "invalid_host_profile", "placement_forbidden", "invalid_host_location",
                    "stateless_profile_required").contains(code))))
                clearWebSocketPending();
            if (status >= 500) ambiguous = true;
            if ((status == 429 || status == 503 || status == 502 || status == 504) && attempt < attempts - 1) {
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
                throw new ProviderException(status, code);
            }
            JsonObject result = JsonParser.parseString(text).getAsJsonObject();
            if (signed) lastControlCarrier = usedWebSocket ? "websocket" : "http";
            if (signed) clearWebSocketPending();
            return result;
        }
        throw new IOException("Provider retry limit exceeded");
    }

    private void requireResolvedOperation() throws IOException {
        if (state.has("pendingWebSocketOperation"))
            throw new IOException("Unresolved provider operation; ordinary registration recovery required");
    }

    private void clearWebSocketPending() throws IOException {
        if (state == null || !state.has("pendingWebSocketOperation")) return;
        JsonElement pending = state.remove("pendingWebSocketOperation");
        try { save(); } catch (IOException failure) {
            state.add("pendingWebSocketOperation", pending);
            throw failure;
        }
    }

    private void configureWebSocket() throws IOException {
        if (websocket != null) websocket.close();
        websocket = null;
        websocketEndpoint = null;
        if (config.controlTransport() != ControlTransport.AUTO || !discovery.has("extensions")) return;
        JsonObject extensions = discovery.getAsJsonObject("extensions");
        if (!extensions.has("org.nethernet.websocket")) return;
        JsonObject extension = extensions.getAsJsonObject("org.nethernet.websocket");
        if (extension.get("version").getAsInt() != 1) return;
        JsonObject data = extension.getAsJsonObject("data");
        try {
            URI endpoint = URI.create(data.get("url").getAsString());
            String scheme = origin.startsWith("https:") ? "wss" : "ws";
            URI httpEndpoint = URI.create((scheme.equals("wss") ? "https" : "http") + "://" + endpoint.getRawAuthority()
                    + endpoint.getRawPath());
            if (!scheme.equals(endpoint.getScheme()) || endpoint.getRawQuery() != null
                    || endpoint.getFragment() != null || !"/v1/nxs/control".equals(endpoint.getRawPath())
                    || !ProviderCrypto.PROTOCOL.equals(data.get("subprotocol").getAsString()))
                throw new IOException("Unsupported provider WebSocket capability");
            trusted(httpEndpoint);
            websocketEndpoint = endpoint;
            websocket = new ProviderWebSocket(http, endpoint, config.assistedJoins() ? this::assistedJoin : null);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid provider WebSocket capability", failure);
        }
    }

    private String websocketOperation(URI uri) throws IOException {
        if (websocket == null) return null;
        for (String name : List.of("heartbeat", "outcomes", "rotate", "retire", "deregister")) {
            // This capability carries the standard endpoints, never silently rewrites a signature target.
            if (uri.equals(operation(name)) && uri.getRawQuery() == null
                    && uri.getRawPath().equals("/v1/nxs/" + name)) return name;
        }
        return null;
    }

    private Map<String, String> websocketUpgradeHeaders() throws GeneralSecurityException {
        long now = System.currentTimeMillis(), generation = state.get("generation").getAsLong(),
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
        headers.put("nxs-signature", ProviderCrypto.sign(privateKey, ProviderCrypto.request(origin, "GET",
                websocketEndpoint.getRawPath(), now, registration("instanceId"), registration("keyId"), intent,
                generation, sequence, "")));
        if (assistedMode) headers.put("nxs-assisted", "1");
        return headers;
    }

    private CompletionStage<ProviderWebSocket.AssistedAnswer> assistedJoin(org.cloudburstmc.netty.signaling.control.AssistedJoin join) {
        AssistedAuthority captured = assistedAuthority;
        if (captured == null) return CompletableFuture.failedFuture(new IOException("Assisted authority unavailable"));
        Runnable guard = () -> {
            AssistedAuthority current = assistedAuthority;
            if (!assistedMode || closing.get() || current == null || !captured.instance().equals(current.instance()) || captured.generation() != current.generation()
                    || !captured.instance().equals(join.instanceId()) || captured.generation() != join.generation()
                    || System.nanoTime() >= captured.deadlineNanos() || System.currentTimeMillis() >= captured.expiresAt())
                throw new IllegalStateException("Assisted authority expired");
            captured.profile().requireCurrent();
            var profile = captured.profile().profile();
            if (!profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString().equals(join.incarnation())
                    || !profile.get("dtlsFingerprint").getAsString().equalsIgnoreCase(join.hostFingerprint())
                    || !profile.get("credentialKeyId").getAsString().equals(join.keyId())
                    || join.expiresAt() > captured.expiresAt()) throw new IllegalStateException("Assisted binding mismatch");
        };
        guard.run();
        return transport.assistedJoin(join, guard).thenApply(answer -> { guard.run(); return new ProviderWebSocket.AssistedAnswer(answer, guard); });
    }

    private String registration(String field) {
        return state.getAsJsonObject("registration").get(field).getAsString();
    }

    private JsonObject redactedRegistration() {
        JsonObject copy = state.getAsJsonObject("registration").deepCopy();
        copy.remove("ticketKey");
        if (!registrationExtensions.isEmpty()) {
            copy.add("extensions", registrationExtensions.deepCopy());
        }
        return copy;
    }

    private void save() throws IOException {
        try {
            store.write(state);
        } catch (IOException failure) {
            diagnostics.accept("provider_persistence_failed");
            stop();
            throw failure;
        }
    }

    private static String safeFailure(Exception e) {
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

    public CompletionStage<Void> stop() {
        if (!closing.compareAndSet(false, true)) {
            return stopped;
        }
        executor.execute(() -> {
            try {
                if (started) {
                    drainAndReport();
                    flushEvents();
                }
            } catch (Exception e) {
                diagnostics.accept("provider_drain_unavailable");
            } finally {
                closed = true;
                started = false;
                if (websocket != null) websocket.close();
                if (timer != null) {
                    timer.cancel(false);
                }
                try {
                    transport.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    diagnostics.accept("transport_close_failed");
                }
                try {
                    store.close();
                } catch (Exception e) {
                    diagnostics.accept("provider_state_close_failed");
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
