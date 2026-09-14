package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * One serialized owner of an opted-in control generation. The injected I/O owns only control
 * connections. This class never touches the gameplay listener, admission identity or established peers.
 * Persisted grants/status/activation receipts cannot establish live readiness without synchronization.
 */
public final class ControlClientCoordinator implements AutoCloseable {
    public enum State { STOPPED, RECONCILING, PREPARING, STANDBY, ACTIVATING, SYNCHRONIZING, READY,
        AUTHORITY_EXPIRED, BACKOFF, UNRESOLVED, CLOSED }
    public record Config(String audience, URI prepare, URI activate, URI status, URI upgrade,
                         Map<String, URI> operations, String transport, List<String> capabilities,
                         long sessionDurationMillis, long proofMillis, long baseBackoffMillis, long maxBackoffMillis) {
        public Config {
            ControlOrigin.requireCanonical(audience); operations = Map.copyOf(operations); capabilities = List.copyOf(capabilities);
            ControlProof.capabilities(transport, capabilities);
            if (sessionDurationMillis <= 0 || sessionDurationMillis > ControlSessionPayloadCodec.MAX_SESSION_DURATION_MILLIS
                    || proofMillis <= 0 || proofMillis > 30000 || baseBackoffMillis < 100 || maxBackoffMillis < baseBackoffMillis
                    || maxBackoffMillis > 300000) throw ControlJson.invalid("client timing bounds");
            for (URI endpoint : List.of(prepare, activate, status)) endpoint(audience, endpoint, false);
            endpoint(audience, upgrade, true);
            operations.values().forEach(endpoint -> endpoint(audience, endpoint, false));
        }
    }
    /** Lookup is exclusively in the trusted provider-control family; never import a key from a frame. */
    public interface ProviderKeys { ControlFrameCodec.VerificationKey resolve(String keyId); }

    private final ControlClientJournal journal;
    private final Config config;
    private final ControlClientIo io;
    private final ControlClientClock clock;
    private final ControlClientIo.Scheduler scheduler;
    private final DoubleSupplier jitter;
    private final Supplier<String> identifiers;
    private final ProviderKeys keys;
    private ControlClientJournal.Snapshot snapshot;
    private State state = State.STOPPED;
    private String desiredTransport;
    private List<String> desiredCapabilities;
    private long attempt;
    private int failures;
    private Candidate candidate, active;
    private ControlClientIo.Authority authority;
    private ControlClientIo.Scheduler.Task retry, authorityTimer, rotationTimer;
    private long outgoingSequence, incomingSequence = 1;
    private boolean operationInFlight, synchronizationInFlight;
    private CompletableFuture<ControlLifecycleCodec.Receipt> pendingResult;
    private boolean forceHttp;

    private static final class Candidate {
        ControlClientIo.Link link;
        ControlSessionCodec.Request upgrade;
        ControlSessionCodec.VerifiedResponse prepared, challenge;
        boolean opened, activating;
        ControlWriterFence writer;
    }

    public ControlClientCoordinator(ControlClientJournal journal, ControlClientJournal.Snapshot initial, Config config,
            ControlClientIo io, ControlClientClock clock, ControlClientIo.Scheduler scheduler, DoubleSupplier jitter,
            Supplier<String> identifiers, ProviderKeys keys) throws IOException {
        this.journal = Objects.requireNonNull(journal); this.config = config; this.io = io; this.clock = clock;
        this.scheduler = scheduler; this.jitter = jitter; this.identifiers = identifiers; this.keys = keys;
        this.snapshot = journal.read().orElse(null);
        if (snapshot == null) { journal.commit(initial); snapshot = initial; }
        if (!snapshot.subject().equals(initial.subject()) || !snapshot.subject().audience().equals(config.audience())) throw ControlJson.invalid("client journal subject");
        desiredTransport = config.transport(); desiredCapabilities = config.capabilities();
    }

    /** Production identifiers contain 192 CSPRNG bits; deterministic suppliers are reserved for tests. */
    public static Supplier<String> secureIdentifiers() {
        var random = new java.security.SecureRandom();
        return () -> { byte[] bytes = new byte[24]; random.nextBytes(bytes); return ProviderCrypto.base64(bytes); };
    }

    public synchronized State state() { expireAuthority(); return state; }
    public synchronized boolean ready() { return state() == State.READY; }
    public synchronized ControlClientJournal.Snapshot snapshot() { return snapshot; }
    public synchronized void start() {
        if (state != State.STOPPED) throw new IllegalStateException("Control client already started");
        try { recover(); } catch (RuntimeException failure) { fail(); }
    }

    /** Persistent fallback is an explicit new prepare/activate CAS; a one-off HTTPS send never calls this. */
    public synchronized void replaceTransport(String transport, List<String> capabilities) {
        requireRunning(); ControlProof.capabilities(transport, capabilities);
        if (snapshot.pending() != null && snapshot.pending().candidate() != null) throw new IllegalStateException("Resolve machine key rotation before replacing writer");
        if (snapshot.pendingBootstrap() != null) throw new IllegalStateException("Resolve existing bootstrap intent first");
        desiredTransport = transport; desiredCapabilities = List.copyOf(capabilities);
        attempt++; operationInFlight = false; synchronizationInFlight = false; beginPrepare();
    }

    /** Body and stable intent are committed before any network effect. The returned receipt contains no mutable observation. */
    public synchronized CompletionStage<ControlLifecycleCodec.Receipt> submit(String operation, byte[] originalBody, boolean oneOffHttps) {
        requireRunning();
        if (operation.equals("rotate")) throw new IllegalArgumentException("Use rotateMachineKey to persist candidate possession first");
        return submit(operation, originalBody.clone(), null, oneOffHttps, id());
    }

    public synchronized CompletionStage<ControlLifecycleCodec.Receipt> rotateMachineKey() {
        requireRunning();
        if (snapshot.pending() != null) throw new IllegalStateException("Unresolved lifecycle intent");
        try {
            String intentId = id();
            var candidateKey = ControlClientJournal.Credential.from(id(), ProviderCrypto.generate());
            var subject = snapshot.subject();
            var context = new ControlRotationCodec.Context(subject.audience(), subject.instanceId(), subject.generation(), snapshot.currentKey().keyId(), intentId);
            var body = ControlRotationCodec.create(candidateKey.keyId(), candidateKey.keyPair(), context);
            return submit("rotate", ControlRotationCodec.encode(body).getBytes(StandardCharsets.UTF_8), candidateKey, false, intentId);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Cannot prepare rotation", failure); }
    }

    private CompletionStage<ControlLifecycleCodec.Receipt> submit(String operation, byte[] body, ControlClientJournal.Credential candidateKey,
                                                                  boolean https, String intentId) {
        if (snapshot.pending() != null) throw new IllegalStateException("Unresolved lifecycle intent");
        if (!config.operations().containsKey(operation)) throw new IllegalArgumentException("No configured operation route");
        var subject = snapshot.subject();
        var intent = new ControlLifecycleCodec.Intent(1, subject.audience(), operation, subject.instanceId(), subject.generation(),
                snapshot.lastSequence() + 1, intentId, ControlFrameCodec.payloadDigest(body));
        var pending = new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), candidateKey, null);
        persist(new ControlClientJournal.Snapshot(subject, snapshot.currentKey(), snapshot.writer(), intent.sequence(), pending, snapshot.pendingBootstrap(), snapshot.grant()));
        CompletableFuture<ControlLifecycleCodec.Receipt> result = new CompletableFuture<>(); pendingResult = result; forceHttp = https;
        deliverPending();
        return result;
    }

    /** Explicit retry/reconciliation trigger. Unknown, rejected and expired receipts keep the intent barrier. */
    public synchronized void reconcilePending() { requireRunning(); try { recover(); } catch (RuntimeException failure) { fail(); } }

    private void recover() {
        attempt++; operationInFlight = false; synchronizationInFlight = false; state = State.RECONCILING;
        cancel(retry); retry = null;
        var pending = snapshot.pending();
        var credential = pending != null && pending.candidate() != null ? pending.candidate() : snapshot.currentKey();
        currentStatus(credential, !credential.equals(snapshot.currentKey()));
    }

    private void currentStatus(ControlClientJournal.Credential credential, boolean canTryOldKey) {
        JsonObject payload = new JsonObject(); payload.addProperty("query", "current-writer");
        var request = request("status", config.status(), payload, id(), credential, clock.nowMillis() + config.proofMillis());
        watch(() -> io.bootstrap(config.status(), request), request.expiresAt(), reply -> {
            checkedReply(reply, config.status(), "POST", ControlSessionCodec.MAX_ENVELOPE_BYTES);
            if (reply.status() == 401 && canTryOldKey) { currentStatus(snapshot.currentKey(), false); return; }
            JsonObject result = response(reply, request);
            var writer = ControlWriterFence.read(ControlJson.object(result, "writer"));
            if (!writer.keyId().equals(credential.keyId())) throw ControlJson.invalid("strong current selected key");
            if (snapshot.pending() != null) receiptStatus(credential, writer, result, request.sentAt());
            else acceptCurrent(credential, writer, result, request.sentAt());
        });
    }

    private void receiptStatus(ControlClientJournal.Credential credential, ControlWriterFence writer, JsonObject current, long currentRequestIssuedAt) {
        var pending = snapshot.pending();
        JsonObject payload = new JsonObject(); payload.addProperty("query", "intent-receipt");
        payload.addProperty("intentDigest", ControlLifecycleCodec.intentDigest(pending.intent()));
        var request = request("status", config.status(), payload, id(), credential, clock.nowMillis() + config.proofMillis());
        watch(() -> io.bootstrap(config.status(), request), request.expiresAt(), reply -> {
            JsonObject result = response(reply, request);
            ControlLifecycleCodec.Receipt receipt = result.get("receipt").isJsonNull() ? null
                    : ControlLifecycleCodec.decodeReceipt(result.get("receipt").toString());
            if (receipt != null) ControlLifecycleCodec.verifyReceipt(receipt, pending.intent());
            if (receipt != null && receipt.disposition().equals("committed")) {
                if (pending.candidate() != null && !credential.keyId().equals(pending.candidate().keyId())) throw ControlJson.invalid("rotation current key reconciliation");
                persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), null, snapshot.pendingBootstrap(), grant(current)));
                completeReceipt(receipt);
            } else {
                if (!credential.equals(snapshot.currentKey())) { halt(); return; }
                if (receipt != null) persistPendingReceipt(receipt);
            }
            acceptCurrent(credential, writer, current, currentRequestIssuedAt);
        });
    }

    private void acceptCurrent(ControlClientJournal.Credential credential, ControlWriterFence writer, JsonObject current, long currentRequestIssuedAt) {
        var bootstrap = snapshot.pendingBootstrap();
        if (bootstrap != null) {
            var original = ControlSessionCodec.decodeRequest(bootstrap.originalRequest());
            if (original.action().equals("activate")) {
                var intent = ControlSessionPayloadCodec.decodeRequest("activate", original.payloadBytes());
                var expected = ControlWriterFence.read(ControlJson.object(intent, "expectedWriter"));
                var proposed = proposed(original);
                if (writer.equals(proposed)) {
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current)));
                    // A restarted process has no ownership of the previous physical WebSocket.
                    if (candidate != null && candidate.link != null && !candidate.link.closed().toCompletableFuture().isDone()
                            && current.get("writerEnabled").getAsBoolean()) { activatedCandidate(); return; }
                } else if (writer.equals(expected)) {
                    long expires = preparationDeadline(original);
                    if (clock.nowMillis() < expires) { sendActivation(refresh(original, credential, expires)); return; }
                    // A response crossing expiry does not prove that its strong read occurred after expiry.
                    if (currentRequestIssuedAt < expires + 30000) {
                        long generation = attempt;
                        retry = scheduler.schedule(() -> { synchronized (this) {
                            if (generation == attempt && state == State.RECONCILING) currentStatus(credential, false);
                        }}, Math.max(1, expires + 30000 - clock.nowMillis()));
                        return;
                    }
                    // This fresh request was issued after expiry plus bounded clock uncertainty.
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current)));
                } else { halt(); return; }
            } else if (!writer.equals(snapshot.writer())) {
                // No activation was attempted for this preparation. A changed strong writer invalidates it.
                persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current)));
            }
        }
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), snapshot.pendingBootstrap(), grant(current)));
        if (current.get("writerEnabled").getAsBoolean() && snapshot.pendingBootstrap() == null && active != null && active.link != null && active.writer != null
                && samePhysicalWriter(active.writer, writer) && !active.link.closed().toCompletableFuture().isDone()
                && clock.nowMillis() < snapshot.grant().sessionExpiresAt()) {
            // A committed machine rotation changes selected key/revision without replacing this socket or its frame sequences.
            active.writer = writer; authority = null; scheduleRotation(); synchronize(); return;
        }
        beginPrepare();
    }

    private void beginPrepare() {
        state = State.PREPARING;
        try {
            ControlSessionCodec.Request original = snapshot.pendingBootstrap() == null ? null : ControlSessionCodec.decodeRequest(snapshot.pendingBootstrap().originalRequest());
            if (original != null && !original.action().equals("prepare")) throw new IllegalStateException("Activation must reconcile first");
            if (original != null && clock.nowMillis() >= ControlJson.number(ControlSessionPayloadCodec.decodeRequest("prepare", original.payloadBytes()), "intentExpiresAt")) original = null;
            if (original == null) {
                long now = clock.nowMillis(); JsonObject payload = new JsonObject();
                payload.addProperty("transport", desiredTransport); payload.add("capabilities", ControlProof.capabilitiesObject(desiredCapabilities));
                payload.addProperty("clientNonce", id()); payload.add("expectedWriter", snapshot.writer().object());
                payload.addProperty("sessionDurationMillis", config.sessionDurationMillis()); payload.addProperty("intentCreatedAt", now);
                payload.addProperty("intentExpiresAt", now + ControlSessionPayloadCodec.MAX_PREPARATION_MILLIS);
                original = request("prepare", config.prepare(), payload, id(), snapshot.currentKey(), now + config.proofMillis());
                persistBootstrap(original);
            }
            var payload = ControlSessionPayloadCodec.decodeRequest("prepare", original.payloadBytes());
            var request = refresh(original, snapshot.currentKey(), ControlJson.number(payload, "intentExpiresAt"));
            watch(() -> io.bootstrap(config.prepare(), request), request.expiresAt(), reply -> {
                var verified = verifiedReply(reply, request);
                JsonObject prepared = ControlSessionPayloadCodec.decodeResponse("prepared", verified.response().payloadBytes());
                if (clock.nowMillis() >= ControlJson.number(prepared, "expiresAt")) throw ControlJson.invalid("expired prepared response");
                candidate = new Candidate(); candidate.prepared = verified;
                if (ControlJson.string(prepared, "transport").equals("https")) { activate(candidate); return; }
                state = State.STANDBY;
                JsonObject upgradeBody = new JsonObject(); upgradeBody.addProperty("preparedProof", verified.encodedOriginalWire());
                Candidate connection = candidate;
                connection.upgrade = request("upgrade", config.upgrade(), upgradeBody, id(), snapshot.currentKey(), Math.min(ControlJson.number(prepared, "expiresAt"), verified.response().expiresAt()));
                connection.link = io.openWebSocket(config.upgrade(), connection.upgrade, wire -> receive(connection, wire));
                connection.link.closed().whenComplete((ignored, failure) -> { synchronized (this) {
                    if (state != State.CLOSED && (connection == active || connection == candidate) ) fail();
                }});
                watch(() -> connection.link.opened(), connection.upgrade.expiresAt(), ignored -> { connection.opened = true; if (connection.challenge != null) activate(connection); });
            });
        } catch (RuntimeException failure) { fail(); }
    }

    private synchronized void receive(Candidate connection, String wire) {
        if (state == State.CLOSED || state == State.UNRESOLVED) return;
        try {
            if (connection == active) { activeFrame(wire); return; }
            if (connection != candidate || state != State.STANDBY || connection.challenge != null) return;
            connection.challenge = verify(wire, connection.upgrade);
            if (connection.opened) activate(connection);
        } catch (RuntimeException failure) { fail(); }
    }

    private void activate(Candidate connection) {
        if (connection.activating) return;
        connection.activating = true;
        JsonObject payload = new JsonObject(); payload.add("expectedWriter", snapshot.writer().object());
        payload.addProperty("preparedProof", connection.prepared.encodedOriginalWire());
        if (connection.challenge == null) payload.add("connectionProof", com.google.gson.JsonNull.INSTANCE);
        else payload.addProperty("connectionProof", connection.challenge.encodedOriginalWire());
        var request = request("activate", config.activate(), payload, id(), snapshot.currentKey(),
                Math.min(connection.prepared.response().expiresAt(), connection.challenge == null ? Long.MAX_VALUE : connection.challenge.response().expiresAt()));
        ControlSessionPayloadCodec.checkActivationAssociation(request, connection.prepared, connection.challenge, snapshot.writer(), clock.nowMillis());
        persistBootstrap(request); sendActivation(request);
    }

    private void sendActivation(ControlSessionCodec.Request request) {
        state = State.ACTIVATING;
        watch(() -> io.bootstrap(config.activate(), request), request.expiresAt(), reply -> {
            JsonObject result = response(reply, request);
            var writer = ControlWriterFence.read(ControlJson.object(result, "writer"));
            var grant = grant(result);
            if (clock.nowMillis() >= grant.sessionExpiresAt()) throw ControlJson.invalid("expired activation grant");
            persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), writer, snapshot.lastSequence(), snapshot.pending(), null, grant));
            if (writer.transport().equals("websocket") && (candidate == null || candidate.link == null)) { beginPrepare(); return; }
            activatedCandidate();
        });
    }

    private void activatedCandidate() {
        Candidate previous = active; active = candidate; candidate = null;
        if (active != null) active.writer = snapshot.writer();
        if (previous != null && previous != active && previous.link != null) previous.link.close();
        outgoingSequence = 0; incomingSequence = 1; authority = null;
        scheduleRotation();
        state = State.SYNCHRONIZING;
        synchronize();
    }

    /** Called on explicit authority/state updates; idle expiry does not introduce a per-host status polling loop. */
    public synchronized void synchronize() {
        requireRunning();
        if (snapshot.grant() == null || snapshot.writer().transport().equals("legacy-http")) throw new IllegalStateException("No controlled writer");
        if (candidate != null || state == State.PREPARING || state == State.STANDBY || state == State.ACTIVATING) throw new IllegalStateException("Bootstrap is not ready to synchronize");
        if (synchronizationInFlight) throw new IllegalStateException("Synchronization already in progress");
        synchronizationInFlight = true;
        state = State.SYNCHRONIZING; var expected = snapshot.writer(); var grant = snapshot.grant();
        // The verified original grant may authorize synchronization frames, never addressed application work.
        if (grant.authoritySourceCheckedAt() <= clock.nowMillis() + 30000 && grant.authorityExpiresAt() > clock.nowMillis() && grant.authorityExpiresAt() - grant.authoritySourceCheckedAt() <= 300000)
            authority = new ControlClientIo.Authority(expected, grant.authoritySourceCheckedAt(), grant.authorityExpiresAt());
        try { watch(() -> io.synchronize(expected, grant), Math.min(clock.nowMillis() + config.proofMillis(), grant.sessionExpiresAt()), synced -> {
            synchronizationInFlight = false;
            long now = clock.nowMillis();
            if (synced == null || !synced.writer().equals(expected) || !snapshot.writer().equals(expected)
                    || synced.sourceCheckedAt() > now + 30000 || synced.expiresAt() <= now
                    || synced.expiresAt() - synced.sourceCheckedAt() > 300000 || synced.expiresAt() > grant.sessionExpiresAt()
                    || expected.transport().equals("websocket") && (active == null || active.link == null)) throw ControlJson.invalid("active synchronization authority");
            authority = synced; state = State.READY; failures = 0;
            cancel(authorityTimer); long generation = attempt;
            authorityTimer = scheduler.schedule(() -> { synchronized (this) { if (generation == attempt) expireAuthority(); }}, synced.expiresAt() - now);
            deliverPending();
        }); } catch (RuntimeException failure) { fail(); }
    }

    private void activeFrame(String wire) {
        expireAuthority();
        if (authority == null || clock.nowMillis() >= authority.expiresAt()) throw ControlJson.invalid("expired active authority");
        var raw = ControlFrameCodec.decode(wire); var writer = snapshot.writer(); var grant = snapshot.grant();
        var context = new ControlFrameCodec.Context(ControlFrameCodec.Direction.PROVIDER_TO_HOST, config.audience(), snapshot.subject().instanceId(), snapshot.subject().generation(),
                writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), grant.capabilities(), incomingSequence, clock.nowMillis(), authority.expiresAt(), 30000);
        var frame = ControlFrameCodec.verify(wire, context, keys.resolve(raw.authentication().keyId()));
        if (clock.nowMillis() >= frame.expiresAt() || !authority.writer().equals(snapshot.writer())) throw ControlJson.invalid("dispatch authority");
        incomingSequence++;
        if (frame.type().equals("lifecycle.receipt")) acceptReceipt(ControlLifecycleCodec.decodeReceipt(new String(frame.payloadBytes(), StandardCharsets.UTF_8)));
        else if (frame.type().equals("session.reconnect")) replaceTransport(desiredTransport, desiredCapabilities);
        else if (state == State.SYNCHRONIZING && List.of("session.ready", "session.resync", "state.desired").contains(frame.type())) io.onSynchronizationFrame(frame);
        else if (state == State.READY) io.onVerifiedFrame(frame);
    }

    private void deliverPending() {
        var pending = snapshot.pending(); var grant = snapshot.grant();
        if (pending == null || operationInFlight || grant == null || state != State.READY && state != State.AUTHORITY_EXPIRED
                || pending.receipt() != null && !pending.receipt().disposition().equals("unknown") || clock.nowMillis() >= grant.sessionExpiresAt()) return;
        operationInFlight = true;
        try {
            var writer = snapshot.writer(); long now = clock.nowMillis();
            boolean useHttp = forceHttp || writer.transport().equals("https") || state != State.READY || pending.bodyBytes().length > ControlLifecycleCodec.MAX_WS_BODY_BYTES;
            if (useHttp) {
                URI endpoint = config.operations().get(pending.intent().operation());
                var proof = new ControlHttpCodec.Request(1, config.audience(), "POST", target(endpoint), now, Math.min(now + config.proofMillis(), grant.sessionExpiresAt()), pending.intent(),
                        writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), writer.transport(), grant.capabilities(), authentication(snapshot.currentKey()));
                proof = ControlHttpCodec.sign(proof, snapshot.currentKey().keyPair().getPrivate());
                final var signedProof = proof;
                watch(() -> io.operation(endpoint, signedProof, pending.bodyBytes()), signedProof.expiresAt(), reply -> {
                    checkedReply(reply, endpoint, "POST", 4096);
                    if (reply.status() != 200) throw ControlJson.invalid("operational HTTPS response");
                    acceptReceipt(ControlLifecycleCodec.decodeReceipt(reply.body()));
                });
            } else {
                byte[] payload = ControlLifecycleCodec.encodeWsRequest(pending.intent(), pending.bodyBytes()).getBytes(StandardCharsets.UTF_8);
                var frame = new ControlFrameCodec.Frame(1, "lifecycle.request", id(), ++outgoingSequence, ControlFrameCodec.Direction.HOST_TO_PROVIDER,
                        config.audience(), snapshot.subject().instanceId(), snapshot.subject().generation(), writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), grant.capabilities(),
                        now, Math.min(now + config.proofMillis(), authority.expiresAt()), ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload), authentication(snapshot.currentKey()));
                var signed = ControlFrameCodec.sign(frame, ControlFrameCodec.KeyFamily.MACHINE, snapshot.currentKey().keyPair().getPrivate());
                // Local send completion is not a durable receipt. Keep the intent and the one-flight barrier.
                watch(() -> active.link.sendText(ControlFrameCodec.encode(signed)), frame.expiresAt(), ignored -> { });
                long generation = attempt;
                scheduler.schedule(() -> { synchronized (this) { if (attempt == generation && operationInFlight && snapshot.pending() != null && snapshot.pending().intent().equals(pending.intent())) recover(); }}, frame.expiresAt() - now);
            }
        } catch (GeneralSecurityException | RuntimeException failure) { fail(); }
    }

    private void acceptReceipt(ControlLifecycleCodec.Receipt receipt) {
        var pending = snapshot.pending(); if (pending == null) return;
        ControlLifecycleCodec.verifyReceipt(receipt, pending.intent()); operationInFlight = false;
        if (!receipt.disposition().equals("committed")) { persistPendingReceipt(receipt); return; }
        if (pending.candidate() != null) { persistPendingReceipt(receipt); recover(); return; }
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), null, snapshot.pendingBootstrap(), snapshot.grant()));
        completeReceipt(receipt);
    }

    private void persistPendingReceipt(ControlLifecycleCodec.Receipt receipt) {
        var pending = snapshot.pending();
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(),
                new ControlClientJournal.Pending(pending.intent(), pending.originalBody(), pending.candidate(), receipt), snapshot.pendingBootstrap(), snapshot.grant()));
    }
    private void completeReceipt(ControlLifecycleCodec.Receipt receipt) {
        var result = pendingResult; pendingResult = null;
        if (result != null) result.complete(receipt);
    }

    private ControlSessionCodec.VerifiedResponse verifiedReply(ControlClientIo.HttpReply reply, ControlSessionCodec.Request request) {
        URI endpoint = switch (request.action()) { case "prepare" -> config.prepare(); case "activate" -> config.activate(); default -> config.status(); };
        checkedReply(reply, endpoint, "POST", ControlSessionCodec.MAX_ENVELOPE_BYTES);
        if (reply.status() != 200) throw ControlJson.invalid("bootstrap HTTPS response");
        return verify(reply.body(), request);
    }
    private JsonObject response(ControlClientIo.HttpReply reply, ControlSessionCodec.Request request) {
        var verified = verifiedReply(reply, request); return ControlSessionPayloadCodec.decodeResponse(verified.response().kind(), verified.response().payloadBytes());
    }
    private ControlSessionCodec.VerifiedResponse verify(String wire, ControlSessionCodec.Request request) {
        var raw = ControlSessionCodec.decodeResponse(wire); var key = keys.resolve(raw.authentication().keyId());
        var result = ControlSessionCodec.verifyResponse(wire, new ControlSessionCodec.ResponseContext(request, clock.nowMillis(), key.validUntil(), 30000), key);
        result.requireUnexpired(clock.nowMillis()); return result;
    }
    private ControlSessionCodec.Request refresh(ControlSessionCodec.Request original, ControlClientJournal.Credential credential, long deadline) {
        long now = clock.nowMillis();
        try { return ControlSessionCodec.sign(new ControlSessionCodec.Request(original.version(), original.action(), original.requestId(), original.audience(),
                original.method(), original.encodedPathAndQuery(), original.instanceId(), original.generation(), now, Math.min(now + config.proofMillis(), deadline),
                original.payload(), original.payloadSha256(), authentication(credential)), credential.keyPair().getPrivate());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Cannot sign control retry", failure); }
    }
    private ControlSessionCodec.Request request(String action, URI endpoint, JsonObject payload, String requestId, ControlClientJournal.Credential credential, long deadline) {
        long now = clock.nowMillis(); byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try { return ControlSessionCodec.sign(new ControlSessionCodec.Request(1, action, requestId, config.audience(), action.equals("upgrade") ? "GET" : "POST", target(endpoint),
                snapshot.subject().instanceId(), snapshot.subject().generation(), now, Math.min(now + config.proofMillis(), deadline), ProviderCrypto.base64(bytes), ControlFrameCodec.payloadDigest(bytes), authentication(credential)), credential.keyPair().getPrivate());
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Cannot sign control proof", failure); }
    }

    private <T> void watch(Supplier<CompletionStage<T>> operation, long deadline, Consumer<T> success) {
        long generation = attempt; if (deadline <= clock.nowMillis()) { fail(); return; }
        final boolean[] done = {false};
        var timeout = scheduler.schedule(() -> { synchronized (this) { if (!done[0] && generation == attempt && state != State.CLOSED) { done[0] = true; fail(); } }}, deadline - clock.nowMillis());
        CompletionStage<T> future;
        try { future = Objects.requireNonNull(operation.get(), "Missing control I/O"); }
        catch (RuntimeException failure) { done[0] = true; timeout.cancel(); fail(); return; }
        future.whenComplete((value, failure) -> { synchronized (this) {
            if (done[0]) return; done[0] = true; timeout.cancel();
            if (generation != attempt || state == State.CLOSED) return;
            if (failure != null || clock.nowMillis() >= deadline) { fail(); return; }
            try { success.accept(value); } catch (RuntimeException invalid) { fail(); }
        }});
    }

    private void halt() {
        state = State.UNRESOLVED; attempt++; operationInFlight = false; synchronizationInFlight = false; authority = null;
        cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        Candidate oldCandidate = candidate, oldActive = active; candidate = null; active = null;
        if (oldCandidate != null && oldCandidate.link != null) oldCandidate.link.abort();
        if (oldActive != null && oldActive != oldCandidate && oldActive.link != null) oldActive.link.abort();
    }
    private void fail() {
        if (state == State.CLOSED || state == State.UNRESOLVED) return;
        attempt++; operationInFlight = false; synchronizationInFlight = false; authority = null; state = State.BACKOFF;
        Candidate oldCandidate = candidate, oldActive = active; candidate = null; active = null;
        if (oldCandidate != null && oldCandidate.link != null) oldCandidate.link.abort();
        if (oldActive != null && oldActive != oldCandidate && oldActive.link != null) oldActive.link.abort();
        cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        long bound = config.baseBackoffMillis(); for (int i = 0; i < Math.min(failures++, 30) && bound < config.maxBackoffMillis(); i++) bound = Math.min(bound * 2, config.maxBackoffMillis());
        double random = jitter.getAsDouble(); if (!Double.isFinite(random) || random < 0 || random >= 1) { halt(); return; }
        long delay = Math.max(50, (long) (bound * (0.5 + random * 0.5))), generation = attempt;
        retry = scheduler.schedule(() -> { synchronized (this) { if (generation == attempt && state == State.BACKOFF) recover(); }}, delay);
    }
    private void scheduleRotation() {
        cancel(rotationTimer);
        long remaining = snapshot.grant().sessionExpiresAt() - clock.nowMillis();
        double random = jitter.getAsDouble();
        if (!Double.isFinite(random) || random < 0 || random >= 1) throw ControlJson.invalid("rotation jitter");
        long lead = Math.min(remaining / 4, 60000 + (long) (random * 240000));
        long deadline = snapshot.grant().sessionExpiresAt() - lead, generation = attempt;
        rotationTimer = scheduler.schedule(() -> { synchronized (this) {
            if (generation != attempt || state == State.CLOSED) return;
            try {
                if (snapshot.pendingBootstrap() != null || snapshot.pending() != null && snapshot.pending().candidate() != null) recover();
                else replaceTransport(desiredTransport, desiredCapabilities);
            } catch (RuntimeException failure) { fail(); }
        }}, Math.max(1, deadline - clock.nowMillis()));
    }
    private static boolean samePhysicalWriter(ControlWriterFence left, ControlWriterFence right) {
        return left.transport().equals(right.transport()) && left.sessionEpoch() == right.sessionEpoch()
                && left.sessionId().equals(right.sessionId()) && left.connectionId().equals(right.connectionId());
    }
    private void expireAuthority() { if (state == State.READY && (authority == null || clock.nowMillis() >= authority.expiresAt())) state = State.AUTHORITY_EXPIRED; }
    private void persistBootstrap(ControlSessionCodec.Request request) {
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), snapshot.pending(), new ControlClientJournal.Bootstrap(ControlSessionCodec.encode(request)), snapshot.grant()));
    }
    private void persist(ControlClientJournal.Snapshot next) {
        try { journal.commit(next); snapshot = next; }
        catch (IOException failure) { halt(); throw new IllegalStateException("Control journal commit failed; no new delivery authorized", failure); }
    }
    private static ControlClientJournal.Grant grant(JsonObject payload) {
        if (ControlWriterFence.read(ControlJson.object(payload, "writer")).transport().equals("legacy-http")) return null;
        return new ControlClientJournal.Grant(ControlJson.strings(payload, "capabilities"), ControlJson.number(payload, "activatedAt"), ControlJson.number(payload, "sessionExpiresAt"),
                ControlJson.number(payload, "authoritySourceCheckedAt"), ControlJson.number(payload, "authorityExpiresAt"));
    }
    private static ControlWriterFence proposed(ControlSessionCodec.Request request) {
        var body = ControlSessionPayloadCodec.decodeRequest("activate", request.payloadBytes());
        var expected = ControlWriterFence.read(ControlJson.object(body, "expectedWriter"));
        var prepared = nested(body, "preparedProof", "prepared"); String connection;
        if (ControlJson.string(prepared, "transport").equals("websocket")) connection = ControlJson.string(nested(body, "connectionProof", "connection-challenge"), "connectionId");
        else connection = ControlJson.string(prepared, "connectionId");
        return new ControlWriterFence(ControlJson.string(prepared, "transport"), expected.sessionEpoch() + 1, ControlJson.string(prepared, "pendingSessionId"), connection, expected.keyId(), expected.machineKeyRevision());
    }
    private static long preparationDeadline(ControlSessionCodec.Request request) {
        var body = ControlSessionPayloadCodec.decodeRequest("activate", request.payloadBytes());
        var prepared = nestedResponse(body, "preparedProof");
        long deadline = Math.min(prepared.expiresAt(), ControlJson.number(ControlSessionPayloadCodec.decodeResponse("prepared", prepared.payloadBytes()), "expiresAt"));
        if (!body.get("connectionProof").isJsonNull()) deadline = Math.min(deadline, nestedResponse(body, "connectionProof").expiresAt());
        return deadline;
    }
    private static ControlSessionCodec.Response nestedResponse(JsonObject body, String field) {
        return ControlSessionCodec.decodeResponse(new String(ControlJson.base64(ControlJson.string(body, field), ControlSessionCodec.MAX_ENVELOPE_BYTES, false), StandardCharsets.UTF_8));
    }
    private static JsonObject nested(JsonObject body, String field, String kind) {
        var response = ControlSessionCodec.decodeResponse(new String(ControlJson.base64(ControlJson.string(body, field), ControlSessionCodec.MAX_ENVELOPE_BYTES, false), StandardCharsets.UTF_8));
        if (!response.kind().equals(kind)) throw ControlJson.invalid("journal nested proof kind");
        return ControlSessionPayloadCodec.decodeResponse(kind, response.payloadBytes());
    }
    private static ControlFrameCodec.Authentication authentication(ControlClientJournal.Credential credential) { return new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, credential.keyId(), ""); }
    private String id() { String id = identifiers.get(); ControlJson.opaque(id); return id; }
    private void requireRunning() { if (state == State.STOPPED || state == State.CLOSED || state == State.UNRESOLVED) throw new IllegalStateException("Control client is not running"); }
    private static String target(URI endpoint) { return endpoint.getRawPath() + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery()); }
    private static void endpoint(String audience, URI endpoint, boolean websocket) {
        String origin = (websocket ? ("wss".equals(endpoint.getScheme()) ? "https" : "http") : endpoint.getScheme()) + "://" + endpoint.getRawAuthority();
        if (!origin.equals(audience) || endpoint.getFragment() != null || endpoint.getRawUserInfo() != null
                || websocket && !(endpoint.getScheme().equals("wss") || endpoint.getScheme().equals("ws"))) throw ControlJson.invalid("trusted configured route");
        ControlProof.path(target(endpoint));
    }
    private static void checkedReply(ControlClientIo.HttpReply reply, URI endpoint, String method, int maxBytes) {
        if (reply == null || !endpoint.equals(reply.requestUri()) || !endpoint.equals(reply.responseUri()) || !method.equals(reply.requestMethod())
                || reply.body() == null || reply.body().getBytes(StandardCharsets.UTF_8).length > maxBytes) throw ControlJson.invalid("trusted HTTPS reply provenance");
    }
    private static void cancel(ControlClientIo.Scheduler.Task task) { if (task != null) task.cancel(); }
    @Override public synchronized void close() throws IOException {
        if (state == State.CLOSED) return; state = State.CLOSED; attempt++; cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        Candidate previous = active, standby = candidate; active = null; candidate = null;
        if (previous != null && previous.link != null) previous.link.close();
        if (standby != null && standby != previous && standby.link != null) standby.link.abort();
        if (pendingResult != null) pendingResult.completeExceptionally(new IllegalStateException("Control client closed; durable intent retained"));
        journal.close();
    }
}
