package org.cloudburstmc.netty.signalling;

import com.google.gson.*;

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
    public static final String NEW_SERVICE = "new-service", ATTACH_INSTANCE = "attach-instance";
    public static final String ANONYMOUS_PROOF_OF_WORK = "anonymous-proof-of-work", BEARER_TOKEN = "bearer-token";

    public record Configuration(URI provider, String profile, String label, String registrationMode,
                                String authorizationScheme,
                                String authorizationToken, String region, String pool, Map<String, String> tags) {
        public Configuration {
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
    public record Health(boolean healthy, int capacity, double load, String protocolVersion, String build,
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
        public Health(boolean healthy, int capacity, double load, String protocolVersion, String build) {
            this(healthy, capacity, load, protocolVersion, build, null);
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

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
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
    private JsonObject state, discovery;
    private JsonObject registrationExtensions = new JsonObject();
    private PrivateKey privateKey;
    private String profileRevision;
    private JsonObject lastProfile;
    private long intervalMs = 10000, nextHeartbeat, snapshotClock;
    private boolean started, closed, scheduledCheckIns;
    private long nextOutcomes, nextStatusUpdate, minUpdateIntervalMs = 1000, appliedStateRevision;
    private String hostState = "serving", installedKeyId;
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

    public CompletableFuture<JsonObject> start() {
        return submit(() -> {
            if (started) {
                throw new IllegalStateException("Already started");
            }
            discovery = exchange(URI.create(origin + "/.well-known/nethernet-external-signalling"), "GET", null, false,
                    null, null);
            validateDiscovery();
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
            save();
            installKeys();
            started = true;
            heartbeat();
            timer = executor.scheduleWithFixedDelay(() -> {
                if (closed) {
                    return;
                }
                try {
                    if (started && (System.nanoTime() >= nextHeartbeat || statusChanged())) {
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

    private void completeRecovery(JsonObject challenge, String registrationId) throws Exception {
        ProviderContract.require("challenge", challenge);
        JsonObject context = challenge.getAsJsonObject("context");
        if (!origin.equals(challenge.get("audience").getAsString()) || !ProviderCrypto.PROTOCOL.equals(
                challenge.get("protocol").getAsString())
                || !ProviderCrypto.SIGNATURE.equals(challenge.get("signature").getAsString()) || !"recover".equals(
                context.get("mode").getAsString())
                || !config.profile().equals(context.get("profile").getAsString()) || !registrationId.equals(
                context.get("registrationId").getAsString())
                || !ProviderCrypto.contextDigest(context).equals(challenge.get("contextDigest").getAsString())
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
        JsonObject completion = new JsonObject();
        completion.addProperty("protocol", ProviderCrypto.PROTOCOL);
        completion.addProperty("challengeId", challenge.get("challengeId").getAsString());
        completion.addProperty("proofNonce", "0");
        completion.addProperty("idempotencyKey", intent);
        completion.addProperty("signature", ProviderCrypto.sign(key, ProviderCrypto.proof(challenge, "0", intent)));
        JsonObject recovered = unsigned("complete", completion);
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
        ProviderContract.require("challenge", challenge);
        if (!ProviderCrypto.PROTOCOL.equals(challenge.get("protocol").getAsString())
                || !ProviderCrypto.SIGNATURE.equals(challenge.get("signature").getAsString()) || !origin.equals(
                challenge.get("audience").getAsString()) || !ProviderCrypto.thumbprint(
                state.getAsJsonObject("publicKeyJwk")).equals(challenge.get("thumbprint").getAsString())
                || !ProviderCrypto.contextDigest(challenge.getAsJsonObject("context"))
                .equals(challenge.get("contextDigest").getAsString())) {
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
        JsonObject completion = new JsonObject();
        completion.addProperty("protocol", ProviderCrypto.PROTOCOL);
        completion.addProperty("challengeId", challenge.get("challengeId").getAsString());
        completion.addProperty("proofNonce", nonce);
        completion.addProperty("idempotencyKey", intent);
        completion.addProperty("signature",
                ProviderCrypto.sign(privateKey, ProviderCrypto.proof(challenge, nonce, intent)));
        JsonObject registration = unsigned("complete", completion);
        ProviderContract.require("registration", registration);
        validateRegistration(registration);
        registrationExtensions = ProtocolExtensions.copy(registration);
        registration.remove("extensions");
        state.add("registration", registration);
        state.addProperty("generation", registration.get("leaseGeneration").getAsLong());
        state.addProperty("sequence", 0);
        state.add("ticketKeys", new JsonArray());
        state.remove("challenge");
        if (registration.has("ticketKey")) {
            state.getAsJsonArray("ticketKeys").add(registration.remove("ticketKey"));
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
        ProtocolExtensions.validate(registration);
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
        if (retained.size() > 8) {
            throw new IOException("Too many admission key epochs");
        }
        state.add("ticketKeys", retained);
        save();
        if (retained.isEmpty()) {
            installedKeyId = null;
            if (!state.has("keyRequestId")) {
                state.addProperty("keyRequestId", UUID.randomUUID().toString());
                save();
            }
            return;
        }
        List<ProviderTransport.TicketKey> keys = new ArrayList<>();
        for (JsonElement entry : retained) {
            JsonObject key = entry.getAsJsonObject();
            keys.add(new ProviderTransport.TicketKey(key.get("keyId").getAsString(), key.get("secret").getAsString(),
                    key.has("notBefore") ? key.get("notBefore").getAsLong() : 0,
                    key.has("retireAfter") ? key.get("retireAfter").getAsLong() : Long.MAX_VALUE));
        }
        transport.installTicketKeys(List.copyOf(keys)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        installedKeyId = keys.getLast().keyId();
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
                || health.capacity() != lastReportedHealth.capacity()
                || !Objects.equals(connectedPlayers(health), connectedPlayers(lastReportedHealth))
                || !Objects.equals(health.protocolVersion(), lastReportedHealth.protocolVersion()) || !Objects.equals(
                health.build(), lastReportedHealth.build());
    }

    private static Integer connectedPlayers(Health health) {
        return health.playerCount() == null ? null : health.playerCount().connectedPlayers();
    }

    private void heartbeat() throws Exception {
        // Key delivery and application acknowledgements can need an immediate second exchange.
        for (int exchange = 0; exchange < 3; exchange++) {
            JsonObject body = new JsonObject(), profile = null;
            if (installedKeyId != null && hostState.equals("serving")) {
                profile = transport.hostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (profile == null) {
                    throw new IOException("Transport profile unavailable");
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
            body.addProperty("healthy", health.healthy() && installedKeyId != null && hostState.equals("serving"));
            body.addProperty("capacity", health.capacity());
            body.addProperty("load", health.load());
            if (health.playerCount() != null) {
                body.add("playerCount", JSON.toJsonTree(health.playerCount()));
            }
            body.addProperty("protocolVersion", health.protocolVersion());
            body.addProperty("build", health.build());
            if (config.region() != null) {
                body.addProperty("region", config.region());
            }
            snapshotClock = Math.max(System.currentTimeMillis(), snapshotClock + 1);
            body.addProperty("clockUnixMillis", snapshotClock);
            body.addProperty("checkInVersion", 1);
            body.addProperty("state", hostState);
            body.addProperty("appliedStateRevision", appliedStateRevision);
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
            JsonObject response = signed("heartbeat", "POST", body);
            ProtocolExtensions.validate(response);
            if (body.has("hostProfile")) {
                if (!response.has("hostProfileRevision") || response.get("hostProfileRevision").isJsonNull()) {
                    throw new IOException("Profile acknowledgement missing");
                }
                profileRevision = response.get("hostProfileRevision").getAsString();
                lastProfile = profile.deepCopy();
                state.addProperty("profilePublishedAt", System.currentTimeMillis());
                save();
            }
            boolean again = false;
            if (response.has("ticketKey")) {
                JsonObject key = response.remove("ticketKey").getAsJsonObject();
                if (!state.has("keyRequestId") || !response.has("keyRequest") ||
                        !state.get("keyRequestId").equals(response.getAsJsonObject("keyRequest").get("id")) ||
                        !key.get("keyId").equals(response.getAsJsonObject("keyRequest").get("keyId"))) {
                    throw new IOException("Unbound admission key response");
                }
                state.getAsJsonArray("ticketKeys").add(key);
                state.remove("keyRequestId");
                save();
                installKeys();
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
            JsonObject desired = response.getAsJsonObject("desiredState");
            if (desired == null || !desired.has("revision") || !desired.has("state")) {
                throw new IOException("Provider state missing");
            }
            long revision = desired.getAsJsonPrimitive("revision").getAsBigDecimal().longValueExact();
            String target = desired.get("state").getAsString();
            if (revision < appliedStateRevision || !Set.of("serving", "draining", "closed").contains(target)) {
                throw new IOException("Unsupported provider state");
            }
            if (revision > appliedStateRevision) {
                ProviderTransport.ApplyResult applied = target.equals("serving") ? ProviderTransport.ApplyResult.APPLIED
                        : transport.applyState(target).toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (applied == ProviderTransport.ApplyResult.APPLIED) {
                    appliedStateRevision = revision;
                    if (!target.equals("serving")) {
                        hostState = target;
                        again = true;
                    }
                } else {
                    diagnostics.accept("provider_state_not_applied");
                    nextHeartbeat = Math.min(nextHeartbeat, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
                }
            }
            if (!again) {
                return;
            }
        }
        nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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
            for (String field : List.of("stage", "ticketId", "occurredAt", "reason")) {
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
        JsonArray batch = new JsonArray();
        for (JsonElement event : pending) {
            if (batch.size() < 100) {
                batch.add(event);
            }
        }
        JsonObject body = new JsonObject();
        body.add("events", batch);
        signed("outcomes", "POST", body);
        for (JsonElement sent : batch) {
            pending.remove(sent);
        }
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
     * Opaque optional extension metadata; the application decides what it means.
     */
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
            signed("deregister", "POST", new JsonObject());
            transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS);
            started = false;
            return null;
        });
    }

    public CompletableFuture<JsonObject> rotateTicketKey() {
        return submit(() -> {
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
            started = false;
            return null;
        });
    }

    private void drainAndReport() throws Exception {
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
        long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
        state.addProperty("sequence", sequence);
        save();
        URI uri = operation(op);
        return exchange(uri, method, body == null ? null : JSON.toJson(body), true, intent, null,
                op.equals("outcomes") ? 3 : 15, op.equals("outcomes") ? 1 : 3);
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
        trusted(uri);
        String raw = body == null ? "" : body;
        for (int attempt = 0; attempt < attempts; attempt++) {
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
                long now = System.currentTimeMillis(), generation = state.get("generation").getAsLong(), sequence =
                        state.get("sequence").getAsLong();
                String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                b.header("nxs-instance-id", registration("instanceId")).header("nxs-key-id", registration("keyId"))
                        .header("nxs-timestamp", Long.toString(now))
                        .header("nxs-signature-version", ProviderCrypto.SIGNATURE)
                        .header("nxs-generation", Long.toString(generation))
                        .header("nxs-sequence", Long.toString(sequence)).header("idempotency-key", intent)
                        .header("nxs-signature", ProviderCrypto.sign(privateKey,
                                ProviderCrypto.request(origin, method, path, now, registration("instanceId"),
                                        registration("keyId"), intent, generation, sequence, raw)));
            }
            HttpResponse<byte[]> response;
            var responseFuture = http.sendAsync(b.build(), info -> new LimitedBodySubscriber(65536));
            try {
                response = responseFuture.get(timeoutSeconds + 1, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException failure) {
                responseFuture.cancel(true);
                if (attempt == attempts - 1) {
                    throw new IOException("Provider transport unavailable", failure);
                }
                Thread.sleep((250L << attempt) + ThreadLocalRandom.current().nextLong(100));
                continue;
            }
            String text = new String(response.body(), StandardCharsets.UTF_8);
            int status = response.statusCode();
            if ((status == 429 || status == 503 || status == 502 || status == 504) && attempt < attempts - 1) {
                long delay = 250L << attempt;
                try {
                    delay = Math.max(delay,
                            Long.parseLong(response.headers().firstValue("retry-after").orElse("0")) * 1000);
                } catch (NumberFormatException ignored) {
                }
                if (delay > 10000) {
                    throw new ProviderException(status, "retry_later");
                }
                Thread.sleep(delay + ThreadLocalRandom.current().nextLong(100));
                continue;
            }
            if (status / 100 != 2) {
                String code = "request_rejected";
                try {
                    JsonObject error = JsonParser.parseString(text).getAsJsonObject();
                    if (error.has("code") && error.get("code").getAsString().matches("[a-z0-9_]{1,80}")) {
                        code = error.get("code").getAsString();
                    }
                } catch (RuntimeException ignored) {
                }
                throw new ProviderException(status, code);
            }
            return JsonParser.parseString(text).getAsJsonObject();
        }
        throw new IOException("Provider retry limit exceeded");
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
                http.close();
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
