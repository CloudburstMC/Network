package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** One asynchronous, serialized control lifecycle per backend, never one poller per player. */
public final class ProviderClient implements AutoCloseable {
    public static final String NEW_SERVICE = "new-service", ATTACH_INSTANCE = "attach-instance";
    public static final String ANONYMOUS_PROOF_OF_WORK = "anonymous-proof-of-work", BEARER_TOKEN = "bearer-token";
    public record Configuration(URI provider, String profile, String label, String registrationMode, String authorizationScheme,
                                String authorizationToken, String region, String pool, Map<String, String> tags) {
        public Configuration {
            Objects.requireNonNull(provider); Objects.requireNonNull(profile); Objects.requireNonNull(registrationMode); Objects.requireNonNull(authorizationScheme);
            if (!"nxs-admission-v1".equals(profile)) throw new IllegalArgumentException("Unsupported operational profile");
            ProviderCrypto.origin(provider);
            if (region != null && (!region.matches("[A-Za-z0-9_-]{1,32}") || pool == null || !pool.matches("[A-Za-z0-9_-]{1,64}"))) throw new IllegalArgumentException("Invalid placement");
            tags = tags == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tags));
            if (!Set.of(NEW_SERVICE, ATTACH_INSTANCE).contains(registrationMode)) throw new IllegalArgumentException("Invalid provider registration mode");
            if (!Set.of(ANONYMOUS_PROOF_OF_WORK, BEARER_TOKEN).contains(authorizationScheme)) throw new IllegalArgumentException("Invalid provider authorization scheme");
            if ((BEARER_TOKEN.equals(authorizationScheme)) != (authorizationToken != null && !authorizationToken.isBlank())) throw new IllegalArgumentException("Bearer authorization requires exactly one token");
            if (ANONYMOUS_PROOF_OF_WORK.equals(authorizationScheme) && !NEW_SERVICE.equals(registrationMode)) throw new IllegalArgumentException("Anonymous proof of work can only create a service");
            if (ATTACH_INSTANCE.equals(registrationMode) && (region == null || region.isBlank() || pool == null || pool.isBlank())) throw new IllegalArgumentException("Attached instances require region and pool");
            if ((region == null) != (pool == null) || (!tags.isEmpty() && region == null)) throw new IllegalArgumentException("Provider placement requires region and pool together");
            if (tags.size() > 16 || tags.entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Za-z0-9_.-]{1,32}") || e.getValue() == null || !e.getValue().equals(e.getValue().trim()) || e.getValue().isEmpty() || e.getValue().length() > 64 || e.getValue().codePoints().anyMatch(c -> c < 32 || c == 127))) throw new IllegalArgumentException("Invalid provider placement tags");
        }
        public Configuration(URI provider, String profile, String label) {
            this(provider, profile, label, NEW_SERVICE, ANONYMOUS_PROOF_OF_WORK, null, null, null, Map.of());
        }
        @Override public String toString() { return "Configuration[provider=" + provider + ", profile=" + profile + ", registrationMode=" + registrationMode + ", authorizationScheme=" + authorizationScheme + "]"; }
    }
    public record Health(boolean healthy, int capacity, double load, String protocolVersion, String build) {
        public Health { if (capacity < 0 || capacity > 1000000 || !Double.isFinite(load) || load < 0 || load > 1 || protocolVersion == null) throw new IllegalArgumentException("Invalid health"); }
    }
    public static final class ProviderException extends IOException {
        private final int status;
        ProviderException(int status, String code) { super("Provider request failed: " + status + " " + code); this.status = status; }
        public int status() { return status; }
    }
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Configuration config;
    private final ProviderStateStore store;
    private final ProviderTransport transport;
    private final Supplier<ServerStatus> statusSupplier;
    private final Supplier<Health> healthSupplier;
    private final Consumer<String> diagnostics;
    private final String origin;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "nethernet-provider"); t.setDaemon(true); return t; });
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<ServerStatus> explicitStatus = new AtomicReference<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private JsonObject state, discovery;
    private JsonObject registrationExtensions = new JsonObject();
    private PrivateKey privateKey;
    private String profileRevision;
    private JsonObject lastProfile;
    private long intervalMs = 10000, nextHeartbeat, snapshotClock;
    private boolean started, closed, scheduledCheckIns;
    private long nextControl, nextStatusUpdate, controlIntervalMs = 1000, minUpdateIntervalMs = 1000;
    private ServerStatus lastReportedStatus;
    private Health lastReportedHealth;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private ScheduledFuture<?> timer;
    public ProviderClient(Configuration config, ProviderStateStore store, ProviderTransport transport, Supplier<ServerStatus> statusSupplier, Supplier<Health> healthSupplier, Consumer<String> diagnostics) {
        this.config = config; this.store = store; this.transport = transport; this.statusSupplier = statusSupplier; this.healthSupplier = healthSupplier;
        this.diagnostics = diagnostics; this.origin = ProviderCrypto.origin(config.provider());
    }
    public CompletableFuture<JsonObject> start() { return submit(() -> {
        if (started) throw new IllegalStateException("Already started");
        discovery = exchange(URI.create(origin + "/.well-known/nethernet-external-signalling"), "GET", null, false, null, null);
        validateDiscovery(); state = store.read();
        if (state.has("provider") && !origin.equals(state.get("provider").getAsString())) throw new IOException("State belongs to another provider; use a separate directory");
        if (!state.has("privateKey")) {
            KeyPair pair = ProviderCrypto.generate(); state.addProperty("provider", origin); state.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded())); state.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic())); save();
        }
        privateKey = ProviderCrypto.privateKey(state.get("privateKey").getAsString());
        if (!state.has("registration")) enroll();
        else recoverExisting();
        JsonObject registration = state.getAsJsonObject("registration");
        if (!registration.get("provider").getAsString().equals(origin)) throw new IOException("Registration audience changed");
        JsonObject activationRequest = new JsonObject(); activationRequest.addProperty("profile", config.profile());
        JsonObject activation = signed("activate", "POST", activationRequest);
        state.addProperty("protocol", ProviderCrypto.PROTOCOL); state.addProperty("profile", config.profile());
        state.addProperty("generation", activation.get("leaseGeneration").getAsLong()); state.addProperty("sequence", 0); state.remove("cursor"); save();
        installKeys();
        // Volatile admissions from an earlier profile cannot be restored by a stateless endpoint.
        state.remove("pendingAdmissions"); save();
        started = true; heartbeat();
        timer = executor.scheduleWithFixedDelay(() -> {
            if (closed) return;
            try {
                if (started && (System.nanoTime() >= nextHeartbeat || statusChanged())) heartbeat();
            } catch (Exception e) {
                nextHeartbeat = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                nextStatusUpdate = nextHeartbeat;
                diagnostics.accept("provider_status_unavailable: " + safeFailure(e));
            }
            if (System.nanoTime() >= nextControl) {
                nextControl = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(controlIntervalMs);
                try { control(); } catch (Exception e) { diagnostics.accept("provider_control_unavailable: " + safeFailure(e)); }
            }
            try { flushEvents(); } catch (Exception e) { diagnostics.accept("provider_events_unavailable: " + safeFailure(e)); }
        }, 1000, 1000, TimeUnit.MILLISECONDS);
        return redactedRegistration();
    }); }
    private void validateDiscovery() throws IOException {
        ProviderContract.require("discovery", discovery);
        ProtocolExtensions.validate(discovery);
        if (!origin.equals(discovery.get("controlOrigin").getAsString()) || !origin.equals(discovery.get("provider").getAsString())) throw new IOException("Discovery provider mismatch");
        for (String[] pair : List.of(new String[]{"protocols", ProviderCrypto.PROTOCOL}, new String[]{"signatures", ProviderCrypto.SIGNATURE}, new String[]{"profiles", config.profile()}, new String[]{"modes", config.registrationMode()})) {
            if (!discovery.getAsJsonArray(pair[0]).contains(new JsonPrimitive(pair[1]))) throw new IOException("Unsupported required provider capability: " + pair[0]);
        }
        boolean authorizationSupported = false;
        for (JsonElement item : discovery.getAsJsonObject("authorization").getAsJsonArray("schemes")) {
            JsonObject scheme = item.getAsJsonObject();
            if (config.authorizationScheme().equals(scheme.get("scheme").getAsString()) && scheme.getAsJsonArray("modes").contains(new JsonPrimitive(config.registrationMode()))) authorizationSupported = true;
        }
        if (!authorizationSupported || !"Authorization".equals(discovery.getAsJsonObject("authorization").get("header").getAsString())) throw new IOException("Unsupported provider authorization");
        for (String op : ProviderContract.operations()) if (!discovery.getAsJsonObject("operations").has(op)) throw new IOException("Missing required operation: " + op);
        for (var op : discovery.getAsJsonObject("operations").entrySet()) trusted(URI.create(op.getValue().getAsString()));
        intervalMs = discovery.getAsJsonObject("limits").get("heartbeatIntervalMs").getAsLong();
        if (intervalMs < 1000 || intervalMs > 30000) throw new IOException("Unsupported heartbeat interval");
        JsonObject limits = discovery.getAsJsonObject("limits");
        if (limits.get("maxBodyBytes").getAsLong() < 1 || limits.get("maxBodyBytes").getAsLong() > 65536
            || limits.get("maxControlPage").getAsLong() < 1 || limits.get("maxControlPage").getAsLong() > 100
            || limits.get("clockSkewMs").getAsLong() < 0 || limits.get("clockSkewMs").getAsLong() > 60000)
            throw new IOException("Unsupported provider limits");
    }
    private JsonObject recoveryRequest(String registrationId) {
        JsonObject recovery = new JsonObject(); recovery.addProperty("registrationId", registrationId);
        recovery.addProperty("protocol", ProviderCrypto.PROTOCOL); recovery.addProperty("profile", config.profile());
        return recovery;
    }
    private void recoverExisting() throws Exception {
        String registrationId = registration("registrationId");
        completeRecovery(unsigned("recover", recoveryRequest(registrationId)), registrationId);
    }
    private void completeRecovery(JsonObject challenge, String registrationId) throws Exception {
        ProviderContract.require("challenge", challenge);
        JsonObject context = challenge.getAsJsonObject("context");
        if (!origin.equals(challenge.get("audience").getAsString()) || !ProviderCrypto.PROTOCOL.equals(challenge.get("protocol").getAsString())
            || !ProviderCrypto.SIGNATURE.equals(challenge.get("signature").getAsString()) || !"recover".equals(context.get("mode").getAsString())
            || !config.profile().equals(context.get("profile").getAsString()) || !registrationId.equals(context.get("registrationId").getAsString())
            || !ProviderCrypto.contextDigest(context).equals(challenge.get("contextDigest").getAsString())
            || challenge.get("expiresAt").getAsLong() <= System.currentTimeMillis()
            || !"sha256-leading-zero-bits-v0".equals(challenge.getAsJsonObject("pow").get("algorithm").getAsString())
            || challenge.getAsJsonObject("pow").get("difficulty").getAsInt() != 0)
            throw new IOException("Unbound recovery challenge");
        String thumbprint = challenge.get("thumbprint").getAsString();
        boolean pending = state.has("pendingPublicKeyJwk") && ProviderCrypto.thumbprint(state.getAsJsonObject("pendingPublicKeyJwk")).equals(thumbprint);
        PrivateKey key = pending ? ProviderCrypto.privateKey(state.get("pendingPrivateKey").getAsString()) : privateKey;
        if (!pending && !ProviderCrypto.thumbprint(state.getAsJsonObject("publicKeyJwk")).equals(thumbprint)) throw new IOException("Recovery key does not match durable state");
        String intent = UUID.randomUUID().toString(); JsonObject completion = new JsonObject();
        completion.addProperty("protocol", ProviderCrypto.PROTOCOL); completion.addProperty("challengeId", challenge.get("challengeId").getAsString());
        completion.addProperty("proofNonce", "0"); completion.addProperty("idempotencyKey", intent); completion.addProperty("signature", ProviderCrypto.sign(key, ProviderCrypto.proof(challenge, "0", intent)));
        JsonObject recovered = unsigned("complete", completion); validateRegistration(recovered);
        if (!registrationId.equals(recovered.get("registrationId").getAsString())) throw new IOException("Recovered registration changed");
        if (state.has("registration")) for (String field : List.of("instanceId", "serviceId", "registrationId"))
            if (!state.getAsJsonObject("registration").get(field).equals(recovered.get(field))) throw new IOException("Recovered instance identity changed");
        registrationExtensions = ProtocolExtensions.copy(recovered); recovered.remove("extensions"); recovered.remove("ticketKey");
        state.add("registration", recovered); state.addProperty("generation", recovered.get("leaseGeneration").getAsLong());
        if (!state.has("sequence")) state.addProperty("sequence", 0);
        if (!state.has("ticketKeys")) state.add("ticketKeys", new JsonArray());
        state.remove("challenge");
        // Sequence is monotonic within a generation; the previous durable reservation is retained.
        if (pending) { state.add("privateKey", state.remove("pendingPrivateKey")); state.add("publicKeyJwk", state.remove("pendingPublicKeyJwk")); privateKey = key; }
        save();
    }
    private void enroll() throws Exception {
        JsonObject challenge;
        if (state.has("challenge")) {
            String registrationId = state.getAsJsonObject("challenge").get("challengeId").getAsString();
            JsonObject recoveredChallenge = null;
            try { recoveredChallenge = unsigned("recover", recoveryRequest(registrationId)); }
            catch (ProviderException e) { if (e.status != 403) throw e; }
            if (recoveredChallenge != null) { completeRecovery(recoveredChallenge, registrationId); return; }
            challenge = state.getAsJsonObject("challenge");
        } else {
            JsonObject request = new JsonObject(); request.addProperty("protocol", ProviderCrypto.PROTOCOL); request.addProperty("mode", config.registrationMode());
            request.addProperty("profile", config.profile()); request.add("publicKeyJwk", state.get("publicKeyJwk")); if (config.label() != null) request.addProperty("label", config.label());
            JsonObject authorization = new JsonObject(); authorization.addProperty("scheme", config.authorizationScheme()); request.add("authorization", authorization);
            if (config.region() != null) { JsonObject p = new JsonObject(); p.addProperty("region", config.region()); p.addProperty("pool", config.pool()); if (!config.tags().isEmpty()) p.add("tags", JSON.toJsonTree(config.tags())); request.add("placement", p); }
            challenge = unsigned("challenges", request, config.authorizationToken()); state.add("challenge", challenge); save();
        }
        ProviderContract.require("challenge", challenge);
        if (!ProviderCrypto.PROTOCOL.equals(challenge.get("protocol").getAsString()) || !ProviderCrypto.SIGNATURE.equals(challenge.get("signature").getAsString()) || !origin.equals(challenge.get("audience").getAsString()) || !ProviderCrypto.thumbprint(state.getAsJsonObject("publicKeyJwk")).equals(challenge.get("thumbprint").getAsString()) || !ProviderCrypto.contextDigest(challenge.getAsJsonObject("context")).equals(challenge.get("contextDigest").getAsString())) throw new IOException("Unbound registration challenge");
        JsonObject context = challenge.getAsJsonObject("context");
        if (!config.profile().equals(context.get("profile").getAsString()) || !config.registrationMode().equals(context.get("mode").getAsString())) throw new IOException("Challenge registration context changed");
        String expectedTagsDigest = ProviderCrypto.tagsDigest(config.tags());
        if (config.region() == null) {
            if (!context.get("region").getAsString().isEmpty() || !context.get("pool").getAsString().isEmpty() || context.has("tagsDigest")) throw new IOException("Challenge placement changed");
        } else if (!config.region().equals(context.get("region").getAsString()) || !config.pool().equals(context.get("pool").getAsString()) ||
            (expectedTagsDigest == null ? context.has("tagsDigest") : !context.has("tagsDigest") || !expectedTagsDigest.equals(context.get("tagsDigest").getAsString()))) throw new IOException("Challenge placement changed");
        if (challenge.has("authorization") && !config.authorizationScheme().equals(challenge.getAsJsonObject("authorization").get("scheme").getAsString())) throw new IOException("Challenge authorization changed");
        if (BEARER_TOKEN.equals(config.authorizationScheme()) && (!challenge.has("authorization") || challenge.getAsJsonObject("authorization").get("reference").getAsString().isBlank())) throw new IOException("Bearer challenge omitted its authority reference");
        String intent = UUID.randomUUID().toString(), nonce = null;
        int bits = challenge.getAsJsonObject("pow").get("difficulty").getAsInt();
        if (!"sha256-leading-zero-bits-v0".equals(challenge.getAsJsonObject("pow").get("algorithm").getAsString()) || bits < 0 || bits > 24) throw new IOException("Unsupported proof of work");
        if (BEARER_TOKEN.equals(config.authorizationScheme()) && bits != 0) throw new IOException("Bearer-authorized registration unexpectedly requires proof of work");
        long deadline = challenge.get("expiresAt").getAsLong();
        for (long i = 0; System.currentTimeMillis() < deadline; i++) { String candidate = Long.toString(i); if (ProviderCrypto.meetsDifficulty(ProviderCrypto.digest(ProviderCrypto.proof(challenge, candidate, intent)), bits)) { nonce = candidate; break; } }
        if (nonce == null) throw new IOException("Challenge expired before proof completed");
        JsonObject completion = new JsonObject(); completion.addProperty("protocol", ProviderCrypto.PROTOCOL); completion.addProperty("challengeId", challenge.get("challengeId").getAsString()); completion.addProperty("proofNonce", nonce); completion.addProperty("idempotencyKey", intent); completion.addProperty("signature", ProviderCrypto.sign(privateKey, ProviderCrypto.proof(challenge, nonce, intent)));
        JsonObject registration = unsigned("complete", completion); ProviderContract.require("registration", registration); validateRegistration(registration);
        registrationExtensions = ProtocolExtensions.copy(registration); registration.remove("extensions");
        state.add("registration", registration); state.addProperty("generation", registration.get("leaseGeneration").getAsLong()); state.addProperty("sequence", 0);
        state.add("ticketKeys", new JsonArray()); state.remove("challenge");
        if (registration.has("ticketKey")) { state.getAsJsonArray("ticketKeys").add(registration.remove("ticketKey")); }
        save();
    }
    private void validateRegistration(JsonObject registration) throws IOException {
        ProviderContract.require("registration", registration);
        ProtocolExtensions.validate(registration);
        if (!origin.equals(registration.get("provider").getAsString()) || !config.profile().equals(registration.get("profile").getAsString())) throw new IOException("Registration provider or profile changed");
        JsonObject placement = registration.getAsJsonObject("placement");
        String expectedRegion = config.region() == null ? "" : config.region(), expectedPool = config.pool() == null ? "" : config.pool();
        if (!expectedRegion.equals(placement.get("region").getAsString()) || !expectedPool.equals(placement.get("pool").getAsString())) throw new IOException("Registration placement changed");
        JsonObject expectedTags = JSON.toJsonTree(config.tags()).getAsJsonObject();
        JsonObject actualTags = placement.has("tags") ? placement.getAsJsonObject("tags") : new JsonObject();
        if (!expectedTags.equals(actualTags)) throw new IOException("Registration placement tags changed");
    }
    private void installKeys() throws Exception {
        if (!state.has("ticketKeys")) state.add("ticketKeys", new JsonArray());
        JsonArray unexpired = new JsonArray(); for (JsonElement e : state.getAsJsonArray("ticketKeys")) if (!e.getAsJsonObject().has("retireAfter") || e.getAsJsonObject().get("retireAfter").getAsLong() > System.currentTimeMillis()) unexpired.add(e);
        state.add("ticketKeys", unexpired); save();
        if (state.getAsJsonArray("ticketKeys").isEmpty()) {
            JsonObject fresh = signed("ticket-keys", "POST", new JsonObject());
            if (!fresh.has("ticketKey")) throw new IOException("Ticket response was lost; retry fresh provisioning");
            state.getAsJsonArray("ticketKeys").add(fresh.get("ticketKey")); save();
        }
        List<ProviderTransport.TicketKey> keys = new ArrayList<>();
        for (JsonElement e : state.getAsJsonArray("ticketKeys")) { JsonObject k = e.getAsJsonObject(); keys.add(new ProviderTransport.TicketKey(k.get("keyId").getAsString(), k.get("secret").getAsString(), k.has("notBefore") ? k.get("notBefore").getAsLong() : 0, k.has("retireAfter") ? k.get("retireAfter").getAsLong() : Long.MAX_VALUE)); }
        transport.installTicketKeys(List.copyOf(keys)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        JsonObject ack = new JsonObject(); ack.addProperty("keyId", keys.getLast().keyId()); JsonObject acknowledgement = signed("ticket-keys/ack", "POST", ack);
        if (acknowledgement.has("retirements")) {
            for (JsonElement retired : acknowledgement.getAsJsonArray("retirements")) for (JsonElement stored : state.getAsJsonArray("ticketKeys")) {
                JsonObject r = retired.getAsJsonObject(), k = stored.getAsJsonObject();
                if (r.get("keyId").equals(k.get("keyId"))) k.addProperty("retireAfter", Math.min(k.has("retireAfter") ? k.get("retireAfter").getAsLong() : Long.MAX_VALUE, r.get("retireAfter").getAsLong()));
            }
            save(); List<ProviderTransport.TicketKey> bounded = new ArrayList<>();
            for (JsonElement stored : state.getAsJsonArray("ticketKeys")) { JsonObject k = stored.getAsJsonObject(); long end = k.has("retireAfter") ? k.get("retireAfter").getAsLong() : Long.MAX_VALUE; if (end > System.currentTimeMillis()) bounded.add(new ProviderTransport.TicketKey(k.get("keyId").getAsString(), k.get("secret").getAsString(), k.has("notBefore") ? k.get("notBefore").getAsLong() : 0, end)); }
            transport.installTicketKeys(List.copyOf(bounded)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
    /** A full immutable snapshot. Callers may update every one of the seven fields. */
    public void setServerStatus(ServerStatus status) { explicitStatus.set(Objects.requireNonNull(status)); requestStatusRefresh(); }
    /** Wake local observation on join/leave/reload; unchanged snapshots never create network traffic. */
    public void requestStatusRefresh() {
        if (!closing.get() && refreshQueued.compareAndSet(false, true)) executor.execute(() -> { refreshQueued.set(false); if (!closed && started) { try { if (statusChanged()) heartbeat(); } catch (Exception e) { nextStatusUpdate = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); diagnostics.accept("provider_status_unavailable: " + safeFailure(e)); } } });
    }
    private ServerStatus currentStatus() { ServerStatus s = explicitStatus.get(); return s == null && statusSupplier != null ? statusSupplier.get() : s; }
    private boolean statusChanged() {
        if (System.nanoTime() < nextStatusUpdate) return false;
        if (!scheduledCheckIns) return System.nanoTime() >= nextHeartbeat;
        ServerStatus status = currentStatus(); Health health = healthSupplier.get();
        return !Objects.equals(status, lastReportedStatus) || lastReportedHealth == null
            || health.healthy() != lastReportedHealth.healthy() || health.capacity() != lastReportedHealth.capacity()
            || !Objects.equals(health.protocolVersion(), lastReportedHealth.protocolVersion()) || !Objects.equals(health.build(), lastReportedHealth.build());
    }
    private void heartbeat() throws Exception {
        JsonObject profile = transport.hostProfile().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (profile == null) throw new IOException("Transport profile unavailable");
        boolean supportsSchedule = discovery.getAsJsonObject("limits").has("checkInVersion")
            && discovery.getAsJsonObject("limits").get("checkInVersion").getAsInt() == 1 && profile.has("statelessAdmission");
        if (!profile.equals(lastProfile) || !state.has("profilePublishedAt") || (!supportsSchedule && System.currentTimeMillis() - state.get("profilePublishedAt").getAsLong() > 300000)) {
            JsonObject published = signed("host-profile", "POST", profile); profileRevision = published.get("revision").getAsString(); lastProfile = profile.deepCopy(); state.addProperty("profilePublishedAt", System.currentTimeMillis()); save();
        }
        Health h = healthSupplier.get(); JsonObject body = new JsonObject(); body.addProperty("healthy", h.healthy()); body.addProperty("capacity", h.capacity()); body.addProperty("load", h.load()); body.addProperty("protocolVersion", h.protocolVersion()); body.addProperty("build", h.build()); body.addProperty("hostProfileRevision", profileRevision);
        if (config.region() != null) body.addProperty("region", config.region());
        snapshotClock = Math.max(System.currentTimeMillis(), snapshotClock + 1); body.addProperty("clockUnixMillis", snapshotClock);
        ServerStatus status = null;
        try { status = currentStatus(); if (status != null) body.add("serverStatus", JSON.toJsonTree(status)); }
        catch (RuntimeException e) { diagnostics.accept("status_refresh_failed"); /* Omit snapshot; old report timestamp must expire. */ }
        if (supportsSchedule) body.addProperty("checkInVersion", 1);
        long requestStarted = System.nanoTime();
        JsonObject response = signed("heartbeat", "POST", body);
        if (supportsSchedule && response.has("checkIn")) {
            CheckInSchedule schedule = CheckInSchedule.parse(response);
            scheduledCheckIns = true; controlIntervalMs = schedule.controlPollAfterMillis(); minUpdateIntervalMs = schedule.minUpdateIntervalMillis();
            // Count network time against the granted interval; retries cannot postpone an absolute lease.
            long received = java.time.Instant.parse(response.get("receivedAt").getAsString()).toEpochMilli();
            long remaining = Math.min(schedule.afterMillis(), Math.max(0, response.getAsJsonObject("checkIn").get("nextCheckInAt").getAsLong() - Math.max(received, System.currentTimeMillis())));
            nextHeartbeat = Math.min(requestStarted + TimeUnit.MILLISECONDS.toNanos(schedule.afterMillis()), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining));
            nextControl = Math.min(nextControl, requestStarted + TimeUnit.MILLISECONDS.toNanos(controlIntervalMs));
        } else {
            scheduledCheckIns = false; controlIntervalMs = 1000;
            nextHeartbeat = requestStarted + TimeUnit.MILLISECONDS.toNanos(intervalMs + ThreadLocalRandom.current().nextLong(Math.max(1, intervalMs / 10)));
            nextControl = Math.min(nextControl, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
        }
        nextStatusUpdate = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scheduledCheckIns ? minUpdateIntervalMs : intervalMs);
        lastReportedStatus = status; lastReportedHealth = h;
    }
    private void control() throws Exception {
        JsonObject page = signed("control", "GET", null);
        JsonArray commands = page.has("commands") ? page.getAsJsonArray("commands") : new JsonArray();
        if (commands.size() > discovery.getAsJsonObject("limits").get("maxControlPage").getAsInt()) throw new IOException("Control page exceeds limit");
        boolean terminal = true;
        for (JsonElement item : commands) {
            JsonObject command = item.getAsJsonObject(); String kind = command.has("kind") ? command.get("kind").getAsString() : "";
            if (!Set.of("noop", "drain", "suspend", "revoke").contains(kind)) {
                terminal = false; diagnostics.accept("unsupported_control_command"); continue;
            }
            ProviderTransport.ApplyResult result = transport.applyControl(command.deepCopy()).toCompletableFuture().get(10, TimeUnit.SECONDS);
            if (result == ProviderTransport.ApplyResult.PENDING) terminal = false;
        }
        if (terminal && !commands.isEmpty() && page.has("cursor")) {
            // Native terminal results are replay-safe; merely staged volatile admissions never reach here.
            String cursor = page.get("cursor").getAsString(); JsonObject ack = new JsonObject(); ack.addProperty("cursor", cursor); signed("control/ack", "POST", ack); state.addProperty("cursor", cursor); save();
        }
    }
    private void flushEvents() throws Exception {
        if (!state.has("pendingEvents")) state.add("pendingEvents", new JsonArray());
        JsonArray pending = state.getAsJsonArray("pendingEvents");
        List<JsonObject> fresh = transport.pollEvents();
        if (fresh.size() > 100 || pending.size() + fresh.size() > 1000) throw new IOException("Transport event queue exceeds limit");
        for (JsonObject event : fresh) {
            // Persist only the existing redacted telemetry fields, never native SDP or secret extensions.
            JsonObject safe = new JsonObject();
            for (String field : List.of("stage", "type", "ticketId", "decisionId", "occurredAt", "reason")) if (event.has(field)) safe.add(field, event.get(field));
            if ((!safe.has("stage") && !safe.has("type")) || !safe.has("occurredAt")) throw new IOException("Malformed transport event");
            pending.add(safe);
        }
        if (pending.isEmpty()) return;
        save();
        for (String operation : List.of("ticket-events", "events")) {
            JsonArray batch = new JsonArray();
            for (JsonElement e : pending) if (e.getAsJsonObject().has(operation.equals("events") ? "type" : "stage") && batch.size() < 100) batch.add(e);
            if (batch.isEmpty()) continue;
            JsonObject body = new JsonObject(); body.add("events", batch); signed(operation, "POST", body);
            for (JsonElement sent : batch) pending.remove(sent); save();
        }
    }
    public CompletableFuture<JsonObject> readiness() { return submit(() -> {
        JsonObject response = signed("readiness", "GET", null); ProtocolExtensions.validate(response); return response;
    }); }
    /** Opaque optional extension metadata; the application decides what it means. */
    public CompletableFuture<JsonObject> extensions() { return submit(() -> registrationExtensions.deepCopy()); }
    /** Explicit application request to an advertised extension operation, never automatic execution. */
    public CompletableFuture<JsonObject> extensionRequest(String namespace, String operation, String method, JsonObject body) {
        return submit(() -> {
            if (!Set.of("GET", "POST").contains(method)) throw new IOException("Unsupported extension method");
            JsonObject extension = ProtocolExtensions.copy(discovery).getAsJsonObject(namespace);
            if (extension == null) throw new IOException("Extension unavailable");
            JsonObject operations = extension.getAsJsonObject("data").getAsJsonObject("operations");
            if (operations == null || !operations.has(operation)) throw new IOException("Extension operation unavailable");
            URI uri = trusted(URI.create(operations.get(operation).getAsString()));
            long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1;
            state.addProperty("sequence", sequence); save();
            JsonObject response = exchange(uri, method, body == null ? null : JSON.toJson(body), true, UUID.randomUUID().toString(), null);
            ProtocolExtensions.validate(response); return response;
        });
    }
    public CompletableFuture<Void> deregister() { return submit(() -> {
        signed("deregister", "POST", new JsonObject()); transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS); started = false; return null;
    }); }
    public CompletableFuture<JsonObject> rotateTicketKey() { return submit(() -> {
        JsonObject result = signed("ticket-keys", "POST", new JsonObject()); if (!result.has("ticketKey")) throw new IOException("Fresh ticket provisioning required");
        state.getAsJsonArray("ticketKeys").add(result.get("ticketKey")); save(); installKeys(); lastProfile = null; heartbeat(); return redactedRegistration();
    }); }
    public CompletableFuture<JsonObject> rotateMachineKey() { return submit(() -> {
        KeyPair replacement = ProviderCrypto.generate(); JsonObject jwk = ProviderCrypto.publicJwk(replacement.getPublic());
        state.addProperty("pendingPrivateKey", ProviderCrypto.base64(replacement.getPrivate().getEncoded())); state.add("pendingPublicKeyJwk", jwk); save();
        String intent = UUID.randomUUID().toString(); JsonObject body = new JsonObject(); body.add("publicKeyJwk", jwk);
        body.addProperty("proof", ProviderCrypto.sign(replacement.getPrivate(), ProviderCrypto.array(ProviderCrypto.PROTOCOL, "rotate", origin, registration("instanceId"), registration("keyId"), ProviderCrypto.thumbprint(jwk), state.get("generation").getAsLong(), intent)));
        JsonObject result = signed("rotate", "POST", body, intent); String oldKey = registration("keyId");
        state.add("privateKey", state.remove("pendingPrivateKey")); state.add("publicKeyJwk", state.remove("pendingPublicKeyJwk")); state.getAsJsonObject("registration").addProperty("keyId", result.get("keyId").getAsString()); save(); privateKey = replacement.getPrivate();
        JsonObject retire = new JsonObject(); retire.addProperty("keyId", oldKey); signed("retire", "POST", retire); return result;
    }); }
    public CompletableFuture<Void> drain() { return submit(() -> { signed("drain", "POST", new JsonObject()); transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS); started = false; return null; }); }
    private JsonObject unsigned(String op, JsonObject body) throws Exception { return unsigned(op, body, null); }
    private JsonObject unsigned(String op, JsonObject body, String bearerToken) throws Exception { return exchange(operation(op), "POST", JSON.toJson(body), false, null, bearerToken); }
    private JsonObject signed(String op, String method, JsonObject body) throws Exception { return signed(op, method, body, UUID.randomUUID().toString()); }
    private JsonObject signed(String op, String method, JsonObject body, String intent) throws Exception {
        long sequence = state.has("sequence") ? state.get("sequence").getAsLong() + 1 : 1; state.addProperty("sequence", sequence); save();
        URI uri = operation(op);
        if (op.equals("control") && state.has("cursor")) uri = URI.create(uri + "?cursor=" + java.net.URLEncoder.encode(state.get("cursor").getAsString(), java.nio.charset.StandardCharsets.UTF_8));
        return exchange(uri, method, body == null ? null : JSON.toJson(body), true, intent, null);
    }
    private URI operation(String op) throws IOException { if (!discovery.getAsJsonObject("operations").has(op)) throw new IOException("Missing provider operation: " + op); return trusted(URI.create(discovery.getAsJsonObject("operations").get(op).getAsString())); }
    private URI trusted(URI uri) throws IOException {
        URI authority = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
        if (!ProviderCrypto.origin(authority).equals(origin) || uri.getUserInfo() != null || uri.getFragment() != null) throw new IOException("Untrusted provider operation"); return uri;
    }
    private JsonObject exchange(URI uri, String method, String body, boolean signed, String intent, String bearerToken) throws Exception {
        trusted(uri); String raw = body == null ? "" : body;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("accept", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) b.header("content-type", "application/json");
            if (bearerToken != null) b.header("authorization", "Bearer " + bearerToken);
            if (signed) {
                long now = System.currentTimeMillis(), generation = state.get("generation").getAsLong(), sequence = state.get("sequence").getAsLong();
                String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                b.header("nxs-instance-id", registration("instanceId")).header("nxs-key-id", registration("keyId")).header("nxs-timestamp", Long.toString(now)).header("nxs-signature-version", ProviderCrypto.SIGNATURE).header("nxs-generation", Long.toString(generation)).header("nxs-sequence", Long.toString(sequence)).header("idempotency-key", intent).header("nxs-signature", ProviderCrypto.sign(privateKey, ProviderCrypto.request(origin, method, path, now, registration("instanceId"), registration("keyId"), intent, generation, sequence, raw)));
            }
            HttpResponse<byte[]> response;
            var responseFuture = http.sendAsync(b.build(), info -> new LimitedBodySubscriber(65536));
            try { response = responseFuture.get(20, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failure) {
                responseFuture.cancel(true);
                if (attempt == 2) throw new IOException("Provider transport unavailable", failure);
                Thread.sleep((250L << attempt) + ThreadLocalRandom.current().nextLong(100)); continue;
            }
            String text = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            int status = response.statusCode();
            if ((status == 429 || status == 503 || status == 502 || status == 504) && attempt < 2) { long delay = 250L << attempt;
                try { delay = Math.max(delay, Long.parseLong(response.headers().firstValue("retry-after").orElse("0")) * 1000); } catch (NumberFormatException ignored) { }
                if (delay > 10000) throw new ProviderException(status, "retry_later"); Thread.sleep(delay + ThreadLocalRandom.current().nextLong(100)); continue;
            }
            if (status / 100 != 2) { String code = "request_rejected"; try { JsonObject error = JsonParser.parseString(text).getAsJsonObject(); if (error.has("code") && error.get("code").getAsString().matches("[a-z0-9_]{1,80}")) code = error.get("code").getAsString(); } catch (RuntimeException ignored) {} throw new ProviderException(status, code); }
            return JsonParser.parseString(text).getAsJsonObject();
        }
        throw new IOException("Provider retry limit exceeded");
    }
    private String registration(String field) { return state.getAsJsonObject("registration").get(field).getAsString(); }
    private JsonObject redactedRegistration() { JsonObject copy = state.getAsJsonObject("registration").deepCopy(); copy.remove("ticketKey"); if (!registrationExtensions.isEmpty()) copy.add("extensions", registrationExtensions.deepCopy()); return copy; }
    private void save() throws IOException {
        try { store.write(state); }
        catch (IOException failure) { diagnostics.accept("provider_persistence_failed"); stop(); throw failure; }
    }
    private static String safeFailure(Exception e) { return e instanceof ProviderException ? e.getMessage() : e.getClass().getSimpleName(); }
    private <T> CompletableFuture<T> submit(Callable<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>(); executor.execute(() -> { try { if (closed) throw new IOException("Provider is closed"); f.complete(fn.call()); } catch (Throwable e) { f.completeExceptionally(e); } }); return f;
    }
    public CompletionStage<Void> stop() {
        if (!closing.compareAndSet(false, true)) return stopped;
        executor.execute(() -> { try { if (started) { signed("drain", "POST", new JsonObject()); transport.drain().toCompletableFuture().get(10, TimeUnit.SECONDS); } } catch (Exception e) { diagnostics.accept("provider_drain_unavailable"); }
            finally {
                closed = true; started = false; if (timer != null) timer.cancel(false);
                try { transport.close().toCompletableFuture().get(10, TimeUnit.SECONDS); }
                catch (Exception e) { diagnostics.accept("transport_close_failed"); }
                try { store.close(); } catch (Exception e) { diagnostics.accept("provider_state_close_failed"); }
                http.close(); executor.shutdown(); stopped.complete(null);
            }
        });
        return stopped;
    }
    @Override public void close() { stop(); }
}
