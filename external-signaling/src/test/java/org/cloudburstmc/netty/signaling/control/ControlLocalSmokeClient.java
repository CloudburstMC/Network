package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local real-I/O fixture, not a provider-discovered client or a synchronized control owner. */
public final class ControlLocalSmokeClient {
    private static final List<String> CAPABILITIES = List.of("request-response");
    private final JsonObject configuration;
    private final URI origin;
    private final ControlFrameCodec.VerificationKey provider;
    private final ControlClientClock clock = ControlClientClock.system();
    private final long deadline;
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final java.util.concurrent.ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-control-smoke-input"); thread.setDaemon(true); return thread;
    });
    private final java.util.concurrent.ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final java.util.concurrent.ExecutorService receiver = Executors.newSingleThreadExecutor();
    private final HttpClient client;
    private final JdkControlHttpTransport transport;

    private ControlLocalSmokeClient(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32768) throw new IllegalArgumentException("Invalid private fixture file");
        var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(permissions)) throw new IllegalArgumentException("Fixture file must be private");
        configuration = ControlJson.parse(Files.readString(path), 32768);
        ControlJson.fields(configuration, "origin", "caCertificate", "providerKeyId", "providerPublicKeyJwk", "validFrom", "validUntil", "journalRoot", "hosts");
        origin = URI.create(ControlJson.string(configuration, "origin"));
        if (!origin.toString().matches("https://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Only explicit local HTTPS is allowed");
        ControlOrigin.requireCanonical(origin.toString());
        deadline = clock.nowMillis() + 120_000;
        provider = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL,
                ControlJson.string(configuration, "providerKeyId"), ProviderCrypto.publicKey(ControlJson.object(configuration, "providerPublicKeyJwk")),
                ControlJson.number(configuration, "validFrom"), ControlJson.number(configuration, "validUntil"));
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
        try (var certificate = Files.newInputStream(Path.of(ControlJson.string(configuration, "caCertificate")))) {
            trust.setCertificateEntry("private-local-fixture", CertificateFactory.getInstance("X.509").generateCertificate(certificate));
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tmf.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, tmf.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        transport = new JdkControlHttpTransport(client, scheduler, clock, 2, 5000);
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--config")) throw new IllegalArgumentException("Expected --config private-file");
        ControlLocalSmokeClient smoke = null; boolean failed = false;
        try {
            smoke = new ControlLocalSmokeClient(Path.of(args[1]));
            var hosts = smoke.configuration.getAsJsonArray("hosts");
            if (hosts.size() != 2) throw new IllegalArgumentException("Expected two local fixture hosts");
            for (var host : hosts) smoke.exercise(host.getAsJsonObject());
            emit("complete", null, null);
        } catch (Exception failure) {
            // Configuration contains fresh private keys; do not print values or nested exception messages.
            JsonObject result = new JsonObject(); result.addProperty("phase", "failed"); result.addProperty("errorType", failure.getClass().getSimpleName());
            var frames = new com.google.gson.JsonArray(); for (var frame : java.util.Arrays.stream(failure.getStackTrace()).limit(5).toList()) frames.add(frame.toString()); result.add("at", frames);
            System.out.println(result); System.out.flush(); failed = true;
        } finally {
            if (smoke != null) { smoke.transport.close(); smoke.receiver.shutdownNow(); smoke.scheduler.shutdownNow(); smoke.reader.shutdownNow(); }
        }
        if (failed) System.exit(1);
    }

    private void exercise(JsonObject host) throws Exception {
        ControlJson.fields(host, "hostId", "keyId", "publicKeyJwk", "privateKeyPkcs8", "transport");
        String hostId = ControlJson.string(host, "hostId"), mode = ControlJson.string(host, "transport"), keyId = ControlJson.string(host, "keyId");
        if (!List.of("websocket", "https").contains(mode)) throw new IllegalArgumentException("Unexpected fixture transport");
        var credential = new ControlClientJournal.Credential(keyId, ControlJson.object(host, "publicKeyJwk").toString(), ControlJson.string(host, "privateKeyPkcs8"));
        KeyPair pair = credential.keyPair();
        JsonObject query = new JsonObject(); query.addProperty("query", "current-writer");
        var status = request(hostId, keyId, pair.getPrivate(), "status", "/control/status", query);
        var current = response(await(transport.bootstrap(endpoint("status"), status)), status);
        JsonObject currentBody = payload(current);
        var expected = ControlWriterFence.read(ControlJson.object(currentBody, "writer"));
        if (currentBody.get("writerEnabled").getAsBoolean() || expected.sessionEpoch() != 0) throw new IllegalStateException("Fixture host must start disabled");
        long now = clock.nowMillis();
        JsonObject prepareBody = new JsonObject(); prepareBody.addProperty("transport", mode);
        prepareBody.add("capabilities", ControlProof.capabilitiesObject(CAPABILITIES)); prepareBody.addProperty("clientNonce", UUID.randomUUID().toString());
        prepareBody.add("expectedWriter", expected.object()); prepareBody.addProperty("sessionDurationMillis", 600_000);
        prepareBody.addProperty("intentCreatedAt", now); prepareBody.addProperty("intentExpiresAt", now + 30_000);
        var prepare = request(hostId, keyId, pair.getPrivate(), "prepare", "/control/prepare", prepareBody);
        var prepared = response(await(transport.bootstrap(endpoint("prepare"), prepare)), prepare);
        JdkControlLink socket = null;
        try {
            ControlSessionCodec.VerifiedResponse challenge = null;
            if (mode.equals("websocket")) {
                JsonObject upgradeBody = new JsonObject(); upgradeBody.addProperty("preparedProof", prepared.encodedOriginalWire());
                var upgrade = request(hostId, keyId, pair.getPrivate(), "upgrade", "/control/upgrade", upgradeBody);
                var received = new CompletableFuture<String>(); var timeout = Duration.ofSeconds(5);
                socket = JdkControlLink.connect(client, URI.create("wss://" + origin.getRawAuthority() + "/control/upgrade"), upgrade,
                        new JdkWebSocketTransport.Limits(16384, 128, 4, 65536, timeout, timeout, timeout, timeout), receiver, scheduler, received::complete);
                await(socket.opened()); challenge = verified(await(received), upgrade);
            }
            JsonObject activationBody = new JsonObject(); activationBody.add("expectedWriter", expected.object());
            activationBody.addProperty("preparedProof", prepared.encodedOriginalWire());
            if (challenge == null) activationBody.add("connectionProof", JsonNull.INSTANCE); else activationBody.addProperty("connectionProof", challenge.encodedOriginalWire());
            var activate = request(hostId, keyId, pair.getPrivate(), "activate", "/control/activate", activationBody);
            var proposed = ControlSessionPayloadCodec.checkActivationAssociation(activate, prepared, challenge, expected, clock.nowMillis());
            var activated = response(await(transport.bootstrap(endpoint("activate"), activate)), activate);
            JsonObject activatedBody = payload(activated); var writer = ControlWriterFence.read(ControlJson.object(activatedBody, "writer"));
            if (!writer.equals(proposed) || writer.sessionEpoch() != 1) throw new IllegalStateException("Unexpected activated writer");
            var grant = new ControlClientJournal.Grant(CAPABILITIES, ControlJson.number(activatedBody, "activatedAt"), ControlJson.number(activatedBody, "sessionExpiresAt"),
                    ControlJson.number(activatedBody, "authoritySourceCheckedAt"), ControlJson.number(activatedBody, "authorityExpiresAt"));
            Path journalPath = Path.of(ControlJson.string(configuration, "journalRoot")).resolve(hostId);
            Files.createDirectories(journalPath);
            JsonObject activationEvidence = new JsonObject(); activationEvidence.addProperty("request", ControlSessionCodec.encode(activate)); activationEvidence.addProperty("response", new String(activated.originalWireBytes(), StandardCharsets.UTF_8));
            Files.writeString(journalPath.resolve("activation.json"), activationEvidence.toString(), StandardCharsets.UTF_8);
            emit("activated", hostId, mode);
            String publication = reader.submit(input::readLine).get(Math.min(10_000, remaining()), TimeUnit.MILLISECONDS);
            if (!publication.equals("published:" + hostId)) throw new IllegalStateException("Missing trusted local publication barrier");
            now = clock.nowMillis();
            var authorityRequest = ControlAuthorityCodec.sign(new ControlAuthorityCodec.Request(1, "authority-request", UUID.randomUUID().toString(), origin.toString(), hostId, 1,
                    writer, CAPABILITIES, now, now + 30_000, "POST", "/control/authority", Math.min(now + 300_000, grant.sessionExpiresAt()),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, keyId, "")), pair.getPrivate());
            var reply = await(transport.authority(endpoint("authority"), authorityRequest));
            if (reply.status() != 200) throw new IllegalStateException("Cached authority unavailable after explicit local publication");
            var authority = ControlAuthorityCodec.verifyResponse(reply.body(), new ControlAuthorityCodec.ResponseContext(authorityRequest, clock.nowMillis(), grant.sessionExpiresAt(), 30_000, null), provider);
            authority.requireFreshDelivery(clock.nowMillis(), null);
            var floor = new ControlClientJournal.AuthorityFloor(authority.originalWire());
            var snapshot = new ControlClientJournal.Snapshot(new ControlClientJournal.Subject(origin.toString(), hostId, 1), credential, writer, 0, null, null, grant, floor);
            try (var journal = new FileControlClientJournal(journalPath)) { journal.commit(snapshot); }
            try (var reopened = new FileControlClientJournal(journalPath)) {
                var retained = reopened.read().orElseThrow();
                if (!retained.authorityFloor().value().equals(authority.floor()) || !retained.writer().equals(writer)) throw new IllegalStateException("Journal floor failed restart validation");
            }
            // Real Worker refusal: the proof is validly signed but its action is sent to the status route.
            prepareBody.add("expectedWriter", writer.object()); prepareBody.addProperty("clientNonce", UUID.randomUUID().toString());
            prepareBody.addProperty("intentCreatedAt", clock.nowMillis()); prepareBody.addProperty("intentExpiresAt", clock.nowMillis() + 30_000);
            var wrongTarget = request(hostId, keyId, pair.getPrivate(), "prepare", "/control/status", prepareBody);
            if (await(transport.bootstrap(endpoint("status"), wrongTarget)).status() != 503) throw new IllegalStateException("Wrong action/target was accepted");
            var wrongPair = ProviderCrypto.generate();
            var wrongKey = request(hostId, keyId, wrongPair.getPrivate(), "prepare", "/control/prepare", prepareBody);
            if (await(transport.bootstrap(endpoint("prepare"), wrongKey)).status() != 503) throw new IllegalStateException("Unselected key material was accepted");
            var wrongAuthority = ControlAuthorityCodec.sign(authorityRequest, wrongPair.getPrivate());
            if (await(transport.authority(endpoint("authority"), wrongAuthority)).status() != 503) throw new IllegalStateException("Wrong authority key material was accepted");
            if (socket != null && socket.closed().toCompletableFuture().isDone()) throw new IllegalStateException("Physical socket closed before initial proof verification");
            emit("verified-floor-and-negatives", hostId, mode);
        } finally { if (socket != null) socket.abort(); }
    }
    private URI endpoint(String action) { return URI.create(origin + "/control/" + action); }
    private ControlSessionCodec.Request request(String host, String keyId, PrivateKey key, String action, String target, JsonObject payload) throws Exception {
        long now = clock.nowMillis(), expires = now + 30_000; byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.has("intentExpiresAt")) expires = Math.min(expires, ControlJson.number(payload, "intentExpiresAt"));
        for (String field : List.of("preparedProof", "connectionProof")) if (payload.has(field) && !payload.get(field).isJsonNull()) {
            var nested = ControlSessionCodec.decodeResponse(new String(ControlJson.base64(ControlJson.string(payload, field), ControlSessionCodec.MAX_ENVELOPE_BYTES, false), StandardCharsets.UTF_8));
            expires = Math.min(expires, nested.expiresAt());
        }
        return ControlSessionCodec.sign(new ControlSessionCodec.Request(1, action, UUID.randomUUID().toString(), origin.toString(), action.equals("upgrade") ? "GET" : "POST", target,
                host, 1, now, expires, ProviderCrypto.base64(bytes), ControlFrameCodec.payloadDigest(bytes),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, keyId, "")), key);
    }
    private ControlSessionCodec.VerifiedResponse response(ControlClientIo.HttpReply reply, ControlSessionCodec.Request request) {
        if (reply.status() != 200) throw new IllegalStateException("Local bootstrap unavailable"); return verified(reply.body(), request);
    }
    private ControlSessionCodec.VerifiedResponse verified(String wire, ControlSessionCodec.Request request) {
        var result = ControlSessionCodec.verifyResponse(wire, new ControlSessionCodec.ResponseContext(request, clock.nowMillis(), provider.validUntil(), 30_000), provider);
        result.requireUnexpired(clock.nowMillis()); return result;
    }
    private static JsonObject payload(ControlSessionCodec.VerifiedResponse response) {
        return ControlSessionPayloadCodec.decodeResponse(response.response().kind(), response.response().payloadBytes());
    }
    private long remaining() { long value = deadline - clock.nowMillis(); if (value <= 0) throw new IllegalStateException("Local test deadline"); return value; }
    private <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(Math.min(10_000, remaining()), TimeUnit.MILLISECONDS); }
    private static void emit(String phase, String hostId, String transport) {
        JsonObject value = new JsonObject(); value.addProperty("phase", phase);
        if (hostId != null) value.addProperty("hostId", hostId); if (transport != null) value.addProperty("transport", transport);
        System.out.println(value); System.out.flush();
    }
}
