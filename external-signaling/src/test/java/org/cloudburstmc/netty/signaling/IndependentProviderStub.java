package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Standalone conformance provider: no product code, accounts, hostname rules or database.
 */
public final class IndependentProviderStub implements AutoCloseable {
    final HttpServer server;
    String origin;
    volatile Runnable heartbeatResponseHook = () -> {};
    volatile Runnable heartbeatAttemptHook = () -> {};
    final Map<String, JsonObject> challenges = new HashMap<>(), keys = new HashMap<>();
    JsonObject registration;
    String initialChallengeId;
    volatile long challengeLifetimeMillis = 60000;
    volatile boolean operatorActions;
    volatile int operatorActionRequests;
    volatile JsonObject lastHeartbeat, lastHostProfile;
    volatile int failHeartbeats;
    volatile boolean failOutcomes;
    /** Outcome batches still to reject permanently. */
    volatile int rejectOutcomes;
    /** Answer the next heartbeat without a profile as if the provider held none. */
    volatile boolean forgetProfile;
    volatile int outcomeAttempts;
    volatile boolean loseCompletionResponse;
    volatile long checkInMillis;
    volatile int controlPolls;
    final List<JsonObject> events = new CopyOnWriteArrayList<>();
    long generation, sequence;
    volatile boolean poolOnlyRegistration;
    volatile int registrations, heartbeats, acknowledgements;
    volatile String challengeAuthorization;
    volatile String selectedMode;
    /** Whether the bearer token carries attachment authority, which automatic mode follows. */
    volatile boolean attachToken;
    volatile int challengeDifficulty = -1;
    volatile JsonObject extensionMetadata;
    volatile JsonArray heartbeatRetirements;
    volatile int extensionRequests, keyAcknowledgements;
    boolean draining;
    String keyRequestId;
    JsonObject requestedKey;
    int epoch = 1, profileRevision;
    final List<String> operationsSeen = new CopyOnWriteArrayList<>();

    public IndependentProviderStub() throws IOException {
        this(null);
    }

    IndependentProviderStub(javax.net.ssl.SSLContext tls) throws IOException {
        if (tls == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } else {
            var https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(tls));
            server = https;
        }
        origin =
                (tls == null ? "http://127.0.0.1:" : "https://localhost:")
                        + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    private synchronized void handle(HttpExchange e) throws IOException {
        int status = 200;
        JsonObject response;
        try {
            response = dispatch(e);
            if (e.getRequestURI().getPath().endsWith("/heartbeat")) {
                heartbeatResponseHook.run();
            }
        } catch (Failure f) {
            status = f.status;
            response = new JsonObject();
            response.addProperty("code", f.getMessage());
        } catch (Exception f) {
            status = 400;
            response = new JsonObject();
            response.addProperty("code", "invalid_request");
        }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("content-type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(bytes);
        }
    }

    private JsonObject dispatch(HttpExchange e) throws Exception {
        String path = e.getRequestURI().getPath(),
                raw = new String(e.getRequestBody().readNBytes(65537), StandardCharsets.UTF_8);
        if (path.startsWith("/v1/nxs/")) {
            path = "/example/" + path.substring("/v1/nxs/".length());
        }
        if (raw.length() > 65536) {
            throw new Failure(400, "payload_limit");
        }
        JsonObject body = raw.isEmpty() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
        if (path.equals("/.well-known/nethernet-external-signaling")) {
            JsonObject d = new JsonObject();
            d.addProperty("provider", origin);
            d.add("protocols", strings(ProviderCrypto.PROTOCOL));
            d.add("signatures", strings(ProviderCrypto.SIGNATURE));
            if (extensionMetadata != null) {
                d.add("extensions", extensionMetadata.deepCopy());
            }
            JsonObject limits = new JsonObject();
            limits.addProperty("heartbeatIntervalMs", 1000);
            d.add("limits", limits);
            JsonObject authorization = new JsonObject();
            authorization.add("schemes", strings("anonymous-proof-of-work", "bearer-token"));
            d.add("authorization", authorization);
            return d;
        }
        operationsSeen.add(path);
        if (path.equals("/example/register")) {
            boolean recovery = body.has("registrationId");
            if (!ProviderCrypto.PROTOCOL.equals(body.get("protocol").getAsString())) {
                throw new Failure(400, "unsupported_protocol");
            }
            // A lost completion recovers by the challenge that created the registration
            if (recovery && (registration == null || !registration.get("registrationId").equals(body.get(
                    "registrationId")) && !body.get("registrationId").getAsString().equals(initialChallengeId))) {
                throw new Failure(403, "recovery_unavailable");
            }
            JsonObject key =
                    recovery ? keys.get(registration.get("keyId").getAsString()) : body.getAsJsonObject("publicKeyJwk");
            String authorization;
            if (recovery) {
                authorization = "recovery";
            } else if (e.getRequestHeaders().containsKey("Authorization")) {
                authorization = "bearer-token";
            } else {
                authorization = "anonymous-proof-of-work";
            }
            if (authorization.equals("bearer-token")) {
                challengeAuthorization = e.getRequestHeaders().getFirst("Authorization");
                if (!"Bearer independent-provider-token".equals(challengeAuthorization)) {
                    throw new Failure(401, "invalid_bearer_token");
                }
            }
            JsonObject c = new JsonObject();
            c.addProperty("protocol", ProviderCrypto.PROTOCOL);
            c.addProperty("signature", ProviderCrypto.SIGNATURE);
            c.addProperty("challengeId", UUID.randomUUID().toString());
            c.addProperty("audience", origin);
            c.addProperty("nonce", UUID.randomUUID().toString());
            c.addProperty("thumbprint", ProviderCrypto.thumbprint(key));
            c.addProperty("expiresAt", System.currentTimeMillis() + challengeLifetimeMillis);
            c.addProperty("serverTime", System.currentTimeMillis());
            JsonObject context = new JsonObject();
            for (String f : List.of("label", "authorizationId", "serviceId", "registrationId")) {
                context.addProperty(f, "");
            }
            if (!recovery && body.has("label")) {
                context.add("label", body.get("label"));
            }
            String mode;
            if (recovery) {
                mode = "recover";
            } else if (authorization.equals("bearer-token") && attachToken) {
                mode = "attach-instance";
            } else {
                mode = "new-service";
            }
            context.addProperty("mode", mode);
            if (recovery) {
                context.add("registrationId", registration.get("registrationId"));
            }
            if (!recovery && authorization.equals("bearer-token")) {
                context.addProperty("authorizationId", "independent-authority");
                JsonObject selected = new JsonObject();
                selected.addProperty("scheme", authorization);
                selected.addProperty("reference", "independent-authority");
                c.add("authorization", selected);
            }
            if (!recovery && selectedMode != null) {
                context.addProperty("mode", selectedMode);
            }
            c.add("context", context);
            c.addProperty("contextDigest", ProviderCrypto.contextDigest(context));
            JsonObject pow = new JsonObject();
            challengeDifficulty = recovery || authorization.equals("bearer-token") ? 0 : 2;
            pow.addProperty("difficulty", challengeDifficulty);
            c.add("pow", pow);
            challenges.put(c.get("challengeId").getAsString(), c.deepCopy());
            keys.put(c.get("challengeId").getAsString(), key);
            return c;
        }
        if (path.equals("/example/complete")) {
            String id = body.get("challengeId").getAsString();
            JsonObject c = challenges.get(id);
            if (c == null) {
                throw new Failure(409, "recovery_required");
            }
            String proof = ProviderCrypto.proof(c, body.get("proofNonce").getAsString(),
                    body.get("idempotencyKey").getAsString());
            if (!ProviderCrypto.verify(keys.get(id), body.get("signature").getAsString(), proof)
                    || !ProviderCrypto.meetsDifficulty(ProviderCrypto.digest(proof),
                    c.getAsJsonObject("pow").get("difficulty").getAsInt())) {
                throw new Failure(401, "proof_invalid");
            }
            challenges.remove(id);
            generation++;
            sequence = 0;
            draining = false;
            profileRevision = 0;
            if (c.getAsJsonObject("context").get("mode").getAsString().equals("recover")) {
                JsonObject r = registration.deepCopy();
                r.remove("admissionKey");
                if (poolOnlyRegistration) {
                    r.remove("serviceId");
                    r.remove("publicAddress");
                }
                r.addProperty("leaseGeneration", generation);
                return r;
            }
            if (registration != null) {
                throw new Failure(409, "already_registered");
            }
            registrations++;
            registration = new JsonObject();
            registration.addProperty("protocol", ProviderCrypto.PROTOCOL);
            registration.addProperty("provider", origin);
            registration.addProperty("registrationId", "example-registration-" + registrations);
            initialChallengeId = id;
            registration.addProperty("instanceId", "example-machine-1");
            if (!poolOnlyRegistration) {
                registration.addProperty("serviceId", "example-service-1");
            }
            registration.addProperty("keyId", "example-key-1");
            if (!poolOnlyRegistration) {
                registration.addProperty("publicAddress", "https://play.example.invalid");
            }
            registration.addProperty("leaseGeneration", generation);
            registration.add("admissionKey", ticket());
            if (extensionMetadata != null) {
                registration.add("extensions", extensionMetadata.deepCopy());
            }
            keys.put("example-key-1", keys.get(id));
            if (loseCompletionResponse) {
                loseCompletionResponse = false;
                e.close();
                throw new Failure(503, "completion_response_lost");
            }
            JsonObject completed = registration.deepCopy();
            if (operatorActions) {
                completed.add("operatorAction", operatorAction("https://panel.example/claim/first"));
            }
            return completed;
        }
        if (path.equals("/example/heartbeat")) {
            heartbeatAttemptHook.run();
        }
        if (path.equals("/example/heartbeat")) {
            int remainingFailures = failHeartbeats;
            failHeartbeats--;
            if (remainingFailures > 0) {
                throw new Failure(503, "fixture_transient");
            }
        }
        authenticate(e, raw);
        JsonObject ok = new JsonObject();
        switch (path) {
            case "/example/heartbeat" -> {
                lastHeartbeat = body;
                heartbeats++;
                if (body.has("installedAdmissionKeyIds")) {
                    keyAcknowledgements++;
                }
                if (body.has("hostProfile")) {
                    lastHostProfile = body.getAsJsonObject("hostProfile");
                    JsonObject profile = body.getAsJsonObject("hostProfile");
                    if (keyAcknowledgements == 0 || !"nethernet.stateless-admission.v1".equals(
                            profile.getAsJsonObject("statelessAdmission").get("capability").getAsString())
                            || !profile.get("dtlsFingerprint").getAsString()
                            .matches("sha-256 [0-9A-F]{2}(?::[0-9A-F]{2}){31}")) {
                        throw new Failure(400, "invalid_host_profile");
                    }
                    profileRevision++;
                }
                if (body.has("admissionKeyRequestId")) {
                    String wanted = body.get("admissionKeyRequestId").getAsString();
                    if (!wanted.equals(keyRequestId)) {
                        keyRequestId = wanted;
                        requestedKey = ticket();
                        epoch++;
                        requestedKey.addProperty("keyId", String.format("T%03d", epoch));
                        ok.add("admissionKey", requestedKey.deepCopy());
                    }
                    JsonObject request = new JsonObject();
                    request.addProperty("id", keyRequestId);
                    request.add("keyId", requestedKey.get("keyId"));
                    ok.add("admissionKeyRequest", request);
                }
                JsonArray retirements = heartbeatRetirements;
                if (retirements != null) {
                    ok.add("admissionKeyRetirements", retirements.deepCopy());
                }
                draining = !body.get("acceptingPlayers").getAsBoolean();
                ok.addProperty("hostProfileRevision", "example-profile-" + profileRevision);
                if (forgetProfile && !body.has("hostProfile")) {
                    forgetProfile = false;
                    ok.add("hostProfileRevision", JsonNull.INSTANCE);
                }
                ok.addProperty("routable", profileRevision > 0 && keyAcknowledgements > 0 && !draining);
                JsonObject ready = new JsonObject();
                ready.addProperty("routable", ok.get("routable").getAsBoolean());
                ready.add("reasons", new JsonArray());
                ok.add("readiness", ready);
                if (extensionMetadata != null) {
                    ok.add("extensions", extensionMetadata.deepCopy());
                }
                if (operatorActions) {
                    ok.addProperty("operatorActionAvailable", true);
                }
                if (checkInMillis > 0) {
                    JsonObject schedule = new JsonObject();
                    schedule.addProperty("afterMillis", checkInMillis);
                    schedule.addProperty("leaseMillis", checkInMillis + 30000);
                    schedule.addProperty("minUpdateIntervalMillis", 1000);
                    ok.add("checkIn", schedule);
                }
            }
            case "/example/extension" -> {
                extensionRequests++;
            }
            case "/example/operator-action" -> {
                operatorActionRequests++;
                ok.add("operatorAction", operatorAction("https://panel.example/claim/" + operatorActionRequests));
            }
            case "/example/deregister" -> {
                draining = true;
            }
            case "/example/outcomes" -> {
                outcomeAttempts++;
                if (failOutcomes) {
                    throw new Failure(503, "fixture_outcome_unavailable");
                }
                if (rejectOutcomes > 0) {
                    rejectOutcomes--;
                    throw new Failure(400, "invalid_outcomes");
                }
                for (JsonElement event : body.getAsJsonArray("events")) {
                    events.add(event.getAsJsonObject());
                }
            }
            case "/example/rotate" -> {
                String old = e.getRequestHeaders().getFirst("nxs-key-id"), intent =
                        e.getRequestHeaders().getFirst("idempotency-key");
                JsonObject key = body.getAsJsonObject("publicKeyJwk");
                if (!ProviderCrypto.verify(key, body.get("proof").getAsString(),
                        ProviderCrypto.array(ProviderCrypto.PROTOCOL, "rotate", origin, "example-machine-1", old,
                                ProviderCrypto.thumbprint(key), generation, intent))) {
                    throw new Failure(401, "replacement_proof_invalid");
                }
                String id = "example-key-" + UUID.randomUUID();
                keys.put(id, key);
                registration.addProperty("keyId", id);
                ok.addProperty("keyId", id);
            }
            case "/example/retire" -> {
                keys.remove(body.get("keyId").getAsString());
            }
            default -> {
                throw new Failure(422, "unknown_operation");
            }
        }
        return ok;
    }

    private static JsonObject operatorAction(String url) {
        JsonObject action = new JsonObject();
        action.addProperty("url", url);
        action.addProperty("text", "Claim this server.");
        action.addProperty("expiresAt", System.currentTimeMillis() + 60000);
        return action;
    }

    private void authenticate(HttpExchange e, String raw) throws Failure {
        try {
            Headers h = e.getRequestHeaders();
            String key = h.getFirst("nxs-key-id"), instance = h.getFirst("nxs-instance-id");
            long timestamp = Long.parseLong(h.getFirst("nxs-timestamp")), gen =
                    Long.parseLong(h.getFirst("nxs-generation")), seq = Long.parseLong(h.getFirst("nxs-sequence"));
            if (gen < generation) {
                throw new Failure(409, "generation_fenced");
            }
            if (!ProviderCrypto.SIGNATURE.equals(h.getFirst("nxs-signature-version")) || !"example-machine-1".equals(
                    instance) || Math.abs(System.currentTimeMillis() - timestamp) > 60000 || gen != generation
                    || seq <= sequence) {
                throw new Failure(401, "auth_invalid");
            }
            if (!ProviderCrypto.verify(keys.get(key), h.getFirst("nxs-signature"),
                    ProviderCrypto.request(origin, e.getRequestMethod(), e.getRequestURI().toASCIIString(), timestamp,
                            instance, key, h.getFirst("idempotency-key"), gen, seq, raw))) {
                throw new Failure(401, "signature_invalid");
            }
            sequence = seq;
        } catch (RuntimeException f) {
            throw new Failure(401, "auth_invalid");
        }
    }

    private static JsonArray strings(String... strings) {
        JsonArray a = new JsonArray();
        for (String s : strings) {
            a.add(s);
        }
        return a;
    }

    private static JsonObject ticket() {
        JsonObject key = new JsonObject();
        key.addProperty("keyId", "T001");
        key.addProperty("secret", "LYDph--S9dykiRFwmo5VAzY5GO_QGapcKQ4luEgrCRM");
        return key;
    }

    private static final class Failure extends Exception {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static void main(String[] args) throws Exception {
        var stub = new IndependentProviderStub();
        System.out.println(stub.origin);
        Runtime.getRuntime().addShutdownHook(new Thread(stub::close));
        new CountDownLatch(1).await();
    }
}
