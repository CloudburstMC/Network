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
            var lifecycleEvidence = lifecycle(journalPath, snapshot);
            if (socket != null && socket.closed().toCompletableFuture().isDone()) throw new IllegalStateException("Fixture socket changed during one-off HTTP operations");
            lifecycleEvidence.addProperty("phase", "verified-lifecycle"); lifecycleEvidence.addProperty("hostId", hostId);
            lifecycleEvidence.addProperty("transport", mode); System.out.println(lifecycleEvidence); System.out.flush();
        } finally { if (socket != null) socket.abort(); }
    }

    /** Manual test orchestration, not coordinator READY or a synchronized production socket owner. */
    private JsonObject lifecycle(Path journalPath, ControlClientJournal.Snapshot initial) throws Exception {
        var state = initial;
        JsonObject heartbeat = new JsonObject();
        heartbeat.addProperty("acceptingPlayers", false); heartbeat.addProperty("healthy", true);
        heartbeat.addProperty("capacity", 10); heartbeat.addProperty("load", 0.25);
        heartbeat.addProperty("protocolVersion", "nethernet"); heartbeat.addProperty("clockUnixMillis", clock.nowMillis());
        heartbeat.addProperty("checkInVersion", 1); heartbeat.addProperty("state", "serving");
        heartbeat.addProperty("appliedStateRevision", 0); heartbeat.addProperty("gameOutcomes", "available");
        heartbeat.addProperty("build", "private-smoke-α"); heartbeat.addProperty("keyRequestId", UUID.randomUUID().toString());
        // Preserve whitespace and non-ASCII bytes through the production header/body transport.
        state = pending(journalPath, state, "heartbeat", (" " + heartbeat + "\n").getBytes(StandardCharsets.UTF_8), null);
        var heartbeatRequest = operational(state);
        var checkedHeartbeat = operation(heartbeatRequest, state.pending().bodyBytes());
        byte[] application = checkedHeartbeat.bodyBytes();
        try {
            var body = ControlJson.parse(new String(application, StandardCharsets.UTF_8), ControlResultCodec.MAX_BODY_BYTES);
            String secret = ControlJson.string(ControlJson.object(body, "ticketKey"), "secret");
            if (!secret.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Missing one-time ticket key");
        } finally { java.util.Arrays.fill(application, (byte) 0); }
        var heartbeatReplay = operation(heartbeatRequest, state.pending().bodyBytes());
        if (!heartbeatReplay.receipt().equals(checkedHeartbeat.receipt())
                || ControlJson.parse(new String(heartbeatReplay.bodyBytes(), StandardCharsets.UTF_8), ControlResultCodec.MAX_BODY_BYTES).has("ticketKey")) {
            throw new IllegalStateException("Heartbeat replay changed receipt or repeated a secret");
        }
        state = completed(journalPath, state, checkedHeartbeat.receipt());

        JsonObject outcomes = new JsonObject(); var events = new com.google.gson.JsonArray();
        String ticketId = UUID.randomUUID().toString(), occurredAt = java.time.Instant.now().toString();
        for (String stage : List.of("ticket.ice_seen", "ticket.dtls_connected", "ticket.sctp_connected")) {
            JsonObject event = new JsonObject(); event.addProperty("ticketId", ticketId);
            event.addProperty("stage", stage); event.addProperty("occurredAt", occurredAt); events.add(event);
        }
        outcomes.add("events", events);
        state = pending(journalPath, state, "outcomes", outcomes.toString().getBytes(StandardCharsets.UTF_8), null);
        var outcomesRequest = operational(state); var outcomeResult = operation(outcomesRequest, state.pending().bodyBytes());
        if (!outcomeResult.equals(operation(outcomesRequest, state.pending().bodyBytes()))
                || ControlJson.number(ControlJson.parse(new String(outcomeResult.bodyBytes(), StandardCharsets.UTF_8), ControlResultCodec.MAX_BODY_BYTES), "eventCount") != 3) {
            throw new IllegalStateException("Outcome replay changed original result");
        }
        state = completed(journalPath, state, outcomeResult.receipt());

        var oldCredential = state.currentKey(); var originalWriter = state.writer();
        String rotationId = UUID.randomUUID().toString();
        var candidate = ControlClientJournal.Credential.from("key_" + UUID.randomUUID().toString().replace("-", ""), ProviderCrypto.generate());
        var rotation = ControlRotationCodec.create(candidate.keyId(), candidate.keyPair(), new ControlRotationCodec.Context(
                origin.toString(), state.subject().instanceId(), 1, oldCredential.keyId(), rotationId));
        state = pending(journalPath, state, "rotate", ControlRotationCodec.encode(rotation).getBytes(StandardCharsets.UTF_8), candidate, rotationId);
        var rotateRequest = operational(state); var rotationResult = operation(rotateRequest, state.pending().bodyBytes());
        emptyResult(rotationResult);
        // Discard the direct acknowledgement for reconciliation purposes. Recover only from the
        // previously persisted exact intent/candidate plus strongly authenticated candidate status.
        state = reopen(journalPath, state);
        JsonObject selected = currentStatus(state.subject().instanceId(), candidate);
        var selectedWriter = ControlWriterFence.read(ControlJson.object(selected, "writer"));
        var wanted = new ControlWriterFence(originalWriter.transport(), originalWriter.sessionEpoch(), originalWriter.sessionId(),
                originalWriter.connectionId(), candidate.keyId(), originalWriter.machineKeyRevision() + 1);
        if (!selectedWriter.equals(wanted)) throw new IllegalStateException("Rotation changed physical writer or selected wrong key");
        historicalGrant(selected, state.grant(), true);
        var recoveredRotation = receiptStatus(state.subject().instanceId(), candidate, state.pending().intent());
        if (!recoveredRotation.equals(rotationResult.receipt())) throw new IllegalStateException("Recovered rotation receipt changed");
        rejectStatus(state.subject().instanceId(), oldCredential);
        if (await(transport.operation(operationEndpoint("rotate"), rotateRequest, state.pending().bodyBytes())).status() != 503) {
            throw new IllegalStateException("Old selected-key carrier was accepted after rotation");
        }
        var withReceipt = new ControlClientJournal.Pending(state.pending().intent(), state.pending().originalBody(), candidate, recoveredRotation);
        state = persist(journalPath, new ControlClientJournal.Snapshot(state.subject(), oldCredential, originalWriter,
                state.lastSequence(), withReceipt, null, state.grant(), state.authorityFloor()));
        state = persist(journalPath, new ControlClientJournal.Snapshot(state.subject(), candidate, selectedWriter,
                state.lastSequence(), null, null, state.grant(), state.authorityFloor()));

        JsonObject retirement = new JsonObject(); retirement.addProperty("keyId", oldCredential.keyId());
        state = pending(journalPath, state, "retire", retirement.toString().getBytes(StandardCharsets.UTF_8), null);
        var retirementRequest = operational(state); var retired = operation(retirementRequest, state.pending().bodyBytes());
        emptyResult(retired);
        if (!retired.equals(operation(retirementRequest, state.pending().bodyBytes()))
                || !retired.receipt().equals(receiptStatus(state.subject().instanceId(), candidate, state.pending().intent()))) {
            throw new IllegalStateException("Retirement replay or status changed receipt");
        }
        state = completed(journalPath, state, retired.receipt()); rejectStatus(state.subject().instanceId(), oldCredential);

        state = pending(journalPath, state, "deregister", "{}".getBytes(StandardCharsets.UTF_8), null);
        var deregisterRequest = operational(state); var deregistered = operation(deregisterRequest, state.pending().bodyBytes());
        emptyResult(deregistered);
        if (!deregistered.receipt().equals(receiptStatus(state.subject().instanceId(), candidate, state.pending().intent()))) {
            throw new IllegalStateException("Terminal receipt is not recoverable by selected key");
        }
        JsonObject disabled = currentStatus(state.subject().instanceId(), candidate);
        if (!ControlWriterFence.read(ControlJson.object(disabled, "writer")).equals(selectedWriter)) throw new IllegalStateException("Terminal state changed retained writer");
        historicalGrant(disabled, state.grant(), false);
        if (await(transport.operation(operationEndpoint("deregister"), deregisterRequest, state.pending().bodyBytes())).status() != 503) {
            throw new IllegalStateException("Disabled writer accepted another lifecycle carrier");
        }
        state = completed(journalPath, state, deregistered.receipt());
        // A fresh higher sequence is also refused. It is a negative request, not a committed journal intent.
        byte[] rejectedBody = "{}".getBytes(StandardCharsets.UTF_8);
        var rejectedIntent = ControlLifecycleCodec.intent(origin.toString(), "deregister", state.subject().instanceId(), 1,
                state.lastSequence() + 1, UUID.randomUUID().toString(), rejectedBody);
        if (await(transport.operation(operationEndpoint("deregister"), operational(state, rejectedIntent), rejectedBody)).status() != 503) {
            throw new IllegalStateException("Terminal writer accepted a fresh operation");
        }
        if (state.lastSequence() != 5 || state.pending() != null) throw new IllegalStateException("Unexpected durable lifecycle state");
        JsonObject evidence = new JsonObject(); var receipts = new com.google.gson.JsonArray();
        for (var receipt : List.of(checkedHeartbeat.receipt(), outcomeResult.receipt(), recoveredRotation, retired.receipt(), deregistered.receipt())) {
            receipts.add(ControlJson.parse(ControlLifecycleCodec.encodeReceipt(receipt), ControlLifecycleCodec.MAX_INTENT_BYTES));
        }
        evidence.add("receipts", receipts); evidence.add("originalWriter", originalWriter.object());
        evidence.add("selectedWriter", selectedWriter.object()); evidence.addProperty("rotationParentExpiresAt", rotateRequest.expiresAt());
        evidence.addProperty("retainedFloorSha256", ControlFrameCodec.payloadDigest(initial.authorityFloor().originalResponse().getBytes(StandardCharsets.UTF_8)));
        evidence.addProperty("terminalDisabled", true); evidence.addProperty("journalReopenedBeforeEveryOperation", true);
        evidence.addProperty("candidateStatusRecoveryVerified", true); evidence.addProperty("historicalGrantUnchanged", true);
        return evidence;
    }

    private ControlClientJournal.Snapshot pending(Path path, ControlClientJournal.Snapshot state, String operation, byte[] body,
                                                 ControlClientJournal.Credential candidate) throws Exception {
        return pending(path, state, operation, body, candidate, UUID.randomUUID().toString());
    }
    private ControlClientJournal.Snapshot pending(Path path, ControlClientJournal.Snapshot state, String operation, byte[] body,
                                                 ControlClientJournal.Credential candidate, String idempotencyKey) throws Exception {
        if (state.pending() != null) throw new IllegalStateException("Previous intent is unresolved");
        var intent = ControlLifecycleCodec.intent(origin.toString(), operation, state.subject().instanceId(), 1,
                state.lastSequence() + 1, idempotencyKey, body);
        return persist(path, new ControlClientJournal.Snapshot(state.subject(), state.currentKey(), state.writer(), intent.sequence(),
                new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), candidate, null), null, state.grant(), state.authorityFloor()));
    }
    private ControlClientJournal.Snapshot completed(Path path, ControlClientJournal.Snapshot state, ControlLifecycleCodec.Receipt receipt) throws Exception {
        ControlLifecycleCodec.verifyReceipt(receipt, state.pending().intent());
        return persist(path, new ControlClientJournal.Snapshot(state.subject(), state.currentKey(), state.writer(), state.lastSequence(),
                null, null, state.grant(), state.authorityFloor()));
    }
    private ControlClientJournal.Snapshot persist(Path path, ControlClientJournal.Snapshot snapshot) throws Exception {
        try (var journal = new FileControlClientJournal(path)) { journal.commit(snapshot); }
        return reopen(path, snapshot);
    }
    private ControlClientJournal.Snapshot reopen(Path path, ControlClientJournal.Snapshot expected) throws Exception {
        try (var journal = new FileControlClientJournal(path)) {
            var stored = journal.read().orElseThrow();
            if (!stored.equals(expected)) throw new IllegalStateException("Exact intent/key/writer did not survive journal reopen");
            return stored;
        }
    }
    private ControlHttpCodec.Request operational(ControlClientJournal.Snapshot state) throws Exception { return operational(state, state.pending().intent()); }
    private ControlHttpCodec.Request operational(ControlClientJournal.Snapshot state, ControlLifecycleCodec.Intent intent) throws Exception {
        long now = clock.nowMillis(), expiry = Math.min(now + 30_000, Math.min(state.grant().authorityExpiresAt(), state.grant().sessionExpiresAt()));
        var writer = state.writer();
        return ControlHttpCodec.sign(new ControlHttpCodec.Request(1, origin.toString(), "POST", "/nxs/v1/" + intent.operation(), now, expiry,
                intent, writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), writer.transport(), CAPABILITIES,
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, state.currentKey().keyId(), "")), state.currentKey().keyPair().getPrivate());
    }
    private ControlResultCodec.Result operation(ControlHttpCodec.Request request, byte[] body) throws Exception {
        var reply = await(transport.operation(operationEndpoint(request.intent().operation()), request, body));
        if (reply.status() != 200) throw new IllegalStateException("Lifecycle HTTP envelope unavailable");
        var result = ControlResultCodec.verify(ControlResultCodec.decode(reply.body()), request.intent());
        if (!result.receipt().disposition().equals("committed")) throw new IllegalStateException("Operation was not committed");
        return result;
    }
    private static void emptyResult(ControlResultCodec.Result result) {
        if (!new String(result.bodyBytes(), StandardCharsets.UTF_8).equals("{}")) throw new IllegalStateException("Mutator delivered application state");
    }
    private JsonObject currentStatus(String hostId, ControlClientJournal.Credential credential) throws Exception {
        JsonObject query = new JsonObject(); query.addProperty("query", "current-writer");
        var request = request(hostId, credential.keyId(), credential.keyPair().getPrivate(), "status", "/control/status", query);
        return payload(response(await(transport.bootstrap(endpoint("status"), request)), request));
    }
    private void rejectStatus(String hostId, ControlClientJournal.Credential credential) throws Exception {
        JsonObject query = new JsonObject(); query.addProperty("query", "current-writer");
        var request = request(hostId, credential.keyId(), credential.keyPair().getPrivate(), "status", "/control/status", query);
        if (await(transport.bootstrap(endpoint("status"), request)).status() != 503) throw new IllegalStateException("Old key recovered current-writer status");
    }
    private ControlLifecycleCodec.Receipt receiptStatus(String hostId, ControlClientJournal.Credential credential,
                                                       ControlLifecycleCodec.Intent intent) throws Exception {
        JsonObject query = new JsonObject(); query.addProperty("query", "intent-receipt"); query.addProperty("intentDigest", ControlLifecycleCodec.intentDigest(intent));
        var request = request(hostId, credential.keyId(), credential.keyPair().getPrivate(), "status", "/control/status", query);
        var body = payload(response(await(transport.bootstrap(endpoint("status"), request)), request));
        var receipt = ControlLifecycleCodec.decodeReceipt(ControlJson.object(body, "receipt").toString());
        ControlLifecycleCodec.verifyReceipt(receipt, intent); return receipt;
    }
    private static void historicalGrant(JsonObject status, ControlClientJournal.Grant grant, boolean enabled) {
        if (status.get("writerEnabled").getAsBoolean() != enabled || !ControlJson.strings(status, "capabilities").equals(grant.capabilities())
                || ControlJson.number(status, "activatedAt") != grant.activatedAt() || ControlJson.number(status, "sessionExpiresAt") != grant.sessionExpiresAt()
                || ControlJson.number(status, "authoritySourceCheckedAt") != grant.authoritySourceCheckedAt()
                || ControlJson.number(status, "authorityExpiresAt") != grant.authorityExpiresAt()) throw new IllegalStateException("Status restamped the historical grant");
    }
    private URI operationEndpoint(String action) { return URI.create(origin + "/nxs/v1/" + action); }
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
