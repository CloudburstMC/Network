package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Standalone conformance provider: no product code, accounts, hostname rules or database. */
public final class IndependentProviderStub implements AutoCloseable {
    final HttpServer server;
    final String origin;
    final Map<String, JsonObject> challenges = new HashMap<>(), keys = new HashMap<>(), placements = new HashMap<>();
    JsonObject registration; volatile JsonObject lastHeartbeat;
    volatile int failHeartbeats;
    volatile boolean failOutcomes;
    volatile int outcomeAttempts;
    volatile boolean loseCompletionResponse;
    volatile long checkInMillis;
    volatile int controlPolls;
    final java.util.List<JsonObject> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    long generation, sequence;
    volatile boolean poolOnlyRegistration;
    volatile int registrations, heartbeats, acknowledgements;
    volatile String challengeAuthorization;
    volatile String selectedMode;
    volatile int challengeDifficulty = -1;
    volatile JsonObject extensionMetadata;
    volatile int extensionRequests, keyAcknowledgements;
    boolean draining;
    volatile String desiredState = "serving";
    volatile long desiredRevision = 1, appliedRevision;
    String keyRequestId; JsonObject requestedKey;
    int epoch = 1, profileRevision;
    final List<String> operationsSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
    public IndependentProviderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle); server.start();
    }
    private synchronized void handle(HttpExchange e) throws IOException {
        int status = 200; JsonObject response;
        try { response = dispatch(e); } catch (Failure f) { status = f.status; response = new JsonObject(); response.addProperty("code", f.getMessage()); }
        catch (Exception f) { status = 400; response = new JsonObject(); response.addProperty("code", "invalid_request"); }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().add("content-type", "application/json"); e.sendResponseHeaders(status, bytes.length); try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
    }
    private JsonObject dispatch(HttpExchange e) throws Exception {
        String path = e.getRequestURI().getPath(), raw = new String(e.getRequestBody().readNBytes(65537), StandardCharsets.UTF_8);
        if (raw.length() > 65536) throw new Failure(400, "payload_limit");
        JsonObject body = raw.isEmpty() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        if (path.equals("/.well-known/nethernet-external-signalling")) {
            JsonObject d = new JsonObject(); d.addProperty("provider", origin); d.addProperty("controlOrigin", origin);
            d.add("protocols", strings(ProviderCrypto.PROTOCOL)); d.add("signatures", strings(ProviderCrypto.SIGNATURE)); d.add("modes", strings("automatic", "new-service", "attach-instance")); d.add("profiles", strings("nxs-admission-v1"));
            JsonObject operations = new JsonObject(); for (String op : List.of("register", "complete", "heartbeat", "outcomes", "rotate", "retire", "deregister")) operations.addProperty(op, origin + "/example/" + op);
            if (extensionMetadata != null) d.add("extensions", extensionMetadata.deepCopy());
            d.add("operations", operations); JsonObject limits = new JsonObject(); limits.addProperty("heartbeatIntervalMs", 1000); if (checkInMillis > 0) limits.addProperty("checkInVersion", 1); limits.addProperty("leaseMs", 30000); limits.addProperty("maxBodyBytes", 65536); limits.addProperty("clockSkewMs", 60000); d.add("limits", limits);
            JsonObject authorization = new JsonObject(); authorization.addProperty("header", "Authorization"); JsonArray schemes = new JsonArray();
            schemes.add(authorizationScheme("anonymous-proof-of-work", "automatic", "new-service")); schemes.add(authorizationScheme("bearer-token", "automatic", "new-service", "attach-instance")); authorization.add("schemes", schemes); d.add("authorization", authorization); return d;
        }
        operationsSeen.add(path);
        if (path.equals("/example/register")) {
            boolean recovery = body.has("registrationId");
            if (!ProviderCrypto.PROTOCOL.equals(body.get("protocol").getAsString()) || !"nxs-admission-v1".equals(body.get("profile").getAsString())) throw new Failure(400, "unsupported_profile");
            if (recovery && (registration == null || !registration.get("registrationId").equals(body.get("registrationId")))) throw new Failure(403, "recovery_unavailable");
            JsonObject key = recovery ? keys.get(registration.get("keyId").getAsString()) : body.getAsJsonObject("publicKeyJwk");
            String authorization = recovery ? "recovery" : body.getAsJsonObject("authorization").get("scheme").getAsString();
            if (!recovery && body.get("mode").getAsString().equals("attach-instance") && !authorization.equals("bearer-token")) throw new Failure(403, "bearer_token_required");
            if (authorization.equals("bearer-token")) {
                challengeAuthorization = e.getRequestHeaders().getFirst("Authorization");
                if (!"Bearer independent-provider-token".equals(challengeAuthorization)) throw new Failure(401, "invalid_bearer_token");
            }
            JsonObject c = new JsonObject(); c.addProperty("protocol", ProviderCrypto.PROTOCOL); c.addProperty("signature", ProviderCrypto.SIGNATURE); c.addProperty("challengeId", UUID.randomUUID().toString()); c.addProperty("audience", origin); c.addProperty("nonce", UUID.randomUUID().toString()); c.addProperty("thumbprint", ProviderCrypto.thumbprint(key)); c.addProperty("expiresAt", System.currentTimeMillis() + 60000); c.addProperty("serverTime", System.currentTimeMillis());
            JsonObject context = new JsonObject(); for (String f : List.of("label", "authorizationId", "serviceId", "region", "pool", "registrationId")) context.addProperty(f, ""); context.addProperty("mode", recovery ? "recover" : body.get("mode").getAsString()); if (!recovery && "automatic".equals(context.get("mode").getAsString())) context.addProperty("mode", authorization.equals("bearer-token") && body.has("placement") ? "attach-instance" : "new-service"); context.addProperty("profile", "nxs-admission-v1"); if (recovery) context.add("registrationId", body.get("registrationId"));
            if (!recovery && authorization.equals("bearer-token")) { context.addProperty("authorizationId", "independent-authority"); JsonObject selected = new JsonObject(); selected.addProperty("scheme", authorization); selected.addProperty("reference", "independent-authority"); c.add("authorization", selected); }
            if (!recovery && body.has("placement")) { JsonObject placement = body.getAsJsonObject("placement"); context.add("region", placement.get("region")); context.add("pool", placement.get("pool")); if (placement.has("tags")) { Map<String, String> tags = new TreeMap<>(); for (var tag : placement.getAsJsonObject("tags").entrySet()) tags.put(tag.getKey(), tag.getValue().getAsString()); context.addProperty("tagsDigest", ProviderCrypto.tagsDigest(tags)); } }
            if (!recovery && selectedMode != null) context.addProperty("mode", selectedMode);
            c.add("context", context); c.addProperty("contextDigest", ProviderCrypto.contextDigest(context)); JsonObject pow = new JsonObject(); pow.addProperty("algorithm", "sha256-leading-zero-bits-v0"); challengeDifficulty = recovery || authorization.equals("bearer-token") ? 0 : 2; pow.addProperty("difficulty", challengeDifficulty); c.add("pow", pow);
            challenges.put(c.get("challengeId").getAsString(), c.deepCopy()); keys.put(c.get("challengeId").getAsString(), key);
            if (!recovery && body.has("placement")) placements.put(c.get("challengeId").getAsString(), body.getAsJsonObject("placement").deepCopy());
            return c;
        }
        if (path.equals("/example/complete")) {
            String id = body.get("challengeId").getAsString(); JsonObject c = challenges.get(id);
            if (c == null) throw new Failure(409, "challenge_consumed");
            String proof = ProviderCrypto.proof(c, body.get("proofNonce").getAsString(), body.get("idempotencyKey").getAsString());
            if (!ProviderCrypto.verify(keys.get(id), body.get("signature").getAsString(), proof) || !ProviderCrypto.meetsDifficulty(ProviderCrypto.digest(proof), c.getAsJsonObject("pow").get("difficulty").getAsInt())) throw new Failure(401, "proof_invalid");
            challenges.remove(id); generation++; sequence = 0; draining = false; appliedRevision = 0; profileRevision = 0;
            if (c.getAsJsonObject("context").get("mode").getAsString().equals("recover")) { JsonObject r = registration.deepCopy(); r.remove("ticketKey"); if (poolOnlyRegistration) { r.remove("serviceId"); r.remove("publicAddress"); } r.addProperty("leaseGeneration", generation); return r; }
            if (registration != null) throw new Failure(409, "already_registered"); registrations++;
            registration = new JsonObject(); registration.addProperty("protocol", ProviderCrypto.PROTOCOL); registration.addProperty("provider", origin); registration.addProperty("registrationId", id); registration.addProperty("instanceId", "example-machine-1"); if (!poolOnlyRegistration) registration.addProperty("serviceId", "example-service-1"); registration.addProperty("keyId", "example-key-1"); if (!poolOnlyRegistration) registration.addProperty("publicAddress", "https://play.example.invalid"); registration.addProperty("profile", "nxs-admission-v1"); registration.addProperty("leaseGeneration", generation); registration.addProperty("leaseDeadline", System.currentTimeMillis() + 30000); registration.addProperty("heartbeatIntervalMs", 1000); JsonObject ready = new JsonObject(); ready.addProperty("routable", false); ready.add("reasons", new JsonArray()); registration.add("readiness", ready); JsonObject place = placements.getOrDefault(id, new JsonObject()).deepCopy(); if (!place.has("region")) place.addProperty("region", ""); if (!place.has("pool")) place.addProperty("pool", ""); registration.add("placement", place); registration.add("ticketKey", ticket()); if (extensionMetadata != null) registration.add("extensions", extensionMetadata.deepCopy()); keys.put("example-key-1", keys.get(id)); if (loseCompletionResponse) { loseCompletionResponse = false; e.close(); throw new Failure(503, "completion_response_lost"); } return registration.deepCopy();
        }
        if (path.equals("/example/heartbeat") && failHeartbeats-- > 0) throw new Failure(503, "fixture_transient");
        authenticate(e, raw);
        JsonObject ok = new JsonObject(); ok.addProperty("accepted", true);
        switch (path) {
            case "/example/heartbeat" -> {
                lastHeartbeat = body; heartbeats++;
                if (body.has("installedKeyIds")) keyAcknowledgements++;
                if (body.has("hostProfile")) {
                    JsonObject profile = body.getAsJsonObject("hostProfile");
                    if (keyAcknowledgements == 0 || !"nethernet.stateless-admission.v1".equals(profile.getAsJsonObject("statelessAdmission").get("capability").getAsString())
                        || !profile.get("dtlsFingerprint").getAsString().matches("sha-256 [0-9A-F]{2}(?::[0-9A-F]{2}){31}")) throw new Failure(400, "invalid_host_profile");
                    profileRevision++;
                }
                if (body.has("keyRequestId")) {
                    String wanted = body.get("keyRequestId").getAsString();
                    if (!wanted.equals(keyRequestId)) { keyRequestId = wanted; requestedKey = ticket(); requestedKey.addProperty("keyId", String.format("T%03d", ++epoch)); ok.add("ticketKey", requestedKey.deepCopy()); }
                    JsonObject request = new JsonObject(); request.addProperty("id", keyRequestId); request.add("keyId", requestedKey.get("keyId")); ok.add("keyRequest", request);
                }
                draining = !body.get("state").getAsString().equals("serving");
                long applied = body.get("appliedStateRevision").getAsLong();
                if (applied > appliedRevision) { appliedRevision = applied; acknowledgements++; }
                JsonObject desired = new JsonObject(); desired.addProperty("revision", desiredRevision); desired.addProperty("state", desiredState); ok.add("desiredState", desired);
                ok.addProperty("hostProfileRevision", "example-profile-" + profileRevision);
                ok.addProperty("routable", profileRevision > 0 && keyAcknowledgements > 0 && !draining);
                JsonObject ready = new JsonObject(); ready.addProperty("routable", ok.get("routable").getAsBoolean()); ready.add("reasons", new JsonArray()); ok.add("readiness", ready);
                if (extensionMetadata != null) ok.add("extensions", extensionMetadata.deepCopy());
                if (checkInMillis > 0 && body.has("checkInVersion")) {
                    long now = System.currentTimeMillis(); JsonObject schedule = new JsonObject();
                    schedule.addProperty("version", 1); schedule.addProperty("afterMillis", checkInMillis);
                    schedule.addProperty("nextCheckInAt", now + checkInMillis); schedule.addProperty("leaseExpiresAt", now + checkInMillis + 30000);
                    schedule.addProperty("minUpdateIntervalMillis", 1000);
                    ok.add("checkIn", schedule); ok.addProperty("receivedAt", java.time.Instant.ofEpochMilli(now).toString());
                }
            }
            case "/example/extension" -> { extensionRequests++; }
            case "/example/deregister" -> { draining = true; }
            case "/example/outcomes" -> { outcomeAttempts++; if (failOutcomes) throw new Failure(503, "fixture_outcome_unavailable"); for (JsonElement event : body.getAsJsonArray("events")) events.add(event.getAsJsonObject()); }
            case "/example/rotate" -> {
                String old = e.getRequestHeaders().getFirst("nxs-key-id"), intent = e.getRequestHeaders().getFirst("idempotency-key"); JsonObject key = body.getAsJsonObject("publicKeyJwk");
                if (!ProviderCrypto.verify(key, body.get("proof").getAsString(), ProviderCrypto.array(ProviderCrypto.PROTOCOL, "rotate", origin, "example-machine-1", old, ProviderCrypto.thumbprint(key), generation, intent))) throw new Failure(401, "replacement_proof_invalid");
                String id = "example-key-" + UUID.randomUUID(); keys.put(id, key); registration.addProperty("keyId", id); ok.addProperty("keyId", id);
            }
            case "/example/retire" -> keys.remove(body.get("keyId").getAsString());
            default -> throw new Failure(422, "unknown_operation");
        }
        return ok;
    }
    private void authenticate(HttpExchange e, String raw) throws Failure {
        try {
            Headers h = e.getRequestHeaders(); String key = h.getFirst("nxs-key-id"), instance = h.getFirst("nxs-instance-id"); long timestamp = Long.parseLong(h.getFirst("nxs-timestamp")), gen = Long.parseLong(h.getFirst("nxs-generation")), seq = Long.parseLong(h.getFirst("nxs-sequence"));
            if (!ProviderCrypto.SIGNATURE.equals(h.getFirst("nxs-signature-version")) || !"example-machine-1".equals(instance) || Math.abs(System.currentTimeMillis() - timestamp) > 60000 || gen != generation || seq <= sequence) throw new Failure(401, "auth_invalid");
            if (!ProviderCrypto.verify(keys.get(key), h.getFirst("nxs-signature"), ProviderCrypto.request(origin, e.getRequestMethod(), e.getRequestURI().toASCIIString(), timestamp, instance, key, h.getFirst("idempotency-key"), gen, seq, raw))) throw new Failure(401, "signature_invalid"); sequence = seq;
        } catch (RuntimeException f) { throw new Failure(401, "auth_invalid"); }
    }
    private static JsonArray strings(String... strings) { JsonArray a = new JsonArray(); for (String s : strings) a.add(s); return a; }
    private static JsonObject authorizationScheme(String scheme, String... modes) { JsonObject value = new JsonObject(); value.addProperty("scheme", scheme); value.add("modes", strings(modes)); return value; }
    private static JsonObject ticket() { JsonObject key = new JsonObject(); key.addProperty("keyId", "T001"); key.addProperty("secret", "independent-stub-only-secret-32-bytes-minimum"); return key; }
    private static final class Failure extends Exception { final int status; Failure(int status, String message) { super(message); this.status = status; } }
    @Override public void close() { server.stop(0); }
    public static void main(String[] args) throws Exception { var stub = new IndependentProviderStub(); System.out.println(stub.origin); Runtime.getRuntime().addShutdownHook(new Thread(stub::close)); new CountDownLatch(1).await(); }
}
