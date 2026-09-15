package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        AUTHORITY_EXPIRED, BACKOFF, UNRESOLVED, DEREGISTERED, CLOSED }
    public record Config(String audience, URI prepare, URI activate, URI status, URI upgrade, URI authority,
                         Map<String, URI> operations, String transport, List<String> capabilities,
                         long sessionDurationMillis, long proofMillis, long baseBackoffMillis, long maxBackoffMillis, URI cancelIntent) {
        public Config(String audience, URI prepare, URI activate, URI status, URI upgrade, URI authority,
                      Map<String, URI> operations, String transport, List<String> capabilities,
                      long sessionDurationMillis, long proofMillis, long baseBackoffMillis, long maxBackoffMillis) {
            this(audience, prepare, activate, status, upgrade, authority, operations, transport, capabilities,
                    sessionDurationMillis, proofMillis, baseBackoffMillis, maxBackoffMillis, null);
        }
        public Config {
            ControlOrigin.requireCanonical(audience); operations = Map.copyOf(operations); capabilities = List.copyOf(capabilities);
            ControlProof.capabilities(transport, capabilities);
            if (sessionDurationMillis <= 0 || sessionDurationMillis > ControlSessionPayloadCodec.MAX_SESSION_DURATION_MILLIS
                    || proofMillis <= 0 || proofMillis > 30000 || baseBackoffMillis < 100 || maxBackoffMillis < baseBackoffMillis
                    || maxBackoffMillis > 300000) throw ControlJson.invalid("client timing bounds");
            for (URI endpoint : List.of(prepare, activate, status, authority)) endpoint(audience, endpoint, false);
            endpoint(audience, upgrade, true);
            if (cancelIntent != null) endpoint(audience, cancelIntent, false);
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
    private Candidate candidate, active, discardedGapConnection;
    private ControlAuthorityCodec.Verified authority;
    private ControlFrameCodec.VerificationKey authorityKey;
    private ControlClientIo.Scheduler.Task retry, authorityTimer, rotationTimer, authorityRetry, synchronizationTimeout;
    private PendingAuthority pendingAuthority;
    private PendingAuthority authorityIo;
    private Object synchronizationIo, outcomeAcknowledgementIo;
    private int authorityFailures;
    private long synchronizationVersion;
    private String quarantinedFrame;
    private boolean quarantinedGap;
    private PendingApplicationFrame pendingApplicationFrame;
    private long outgoingSequence, incomingSequence = 1;
    private boolean operationInFlight, synchronizationInFlight;
    private CompletableFuture<ControlOperationResult> pendingResult;
    private SynchronizationExchange synchronizationExchange, pendingSynchronization;
    private boolean forceHttp, cancellationInFlight, cancellationWriterSelected;
    private String cancellationIntentDigest;

    private record PendingApplicationFrame(ControlFrameCodec.Frame frame, ControlFrameCodec.VerificationKey key,
            ControlAuthorityCodec.Verified proof, long attempt, long synchronization) { }

    private static final class Candidate {
        ControlClientIo.Link link;
        ControlSessionCodec.Request upgrade;
        ControlSessionCodec.VerifiedResponse prepared, challenge;
        boolean opened, activating;
        ControlWriterFence writer;
        Object stateSend;
    }
    private static final class PendingAuthority {
        final ControlAuthorityCodec.Request request;
        final ControlClientJournal.Credential credential;
        final ControlClientJournal.Grant grant;
        final long attempt;
        ControlClientIo.Scheduler.Task timeout;
        CompletionStage<ControlClientIo.HttpReply> operation;
        PendingAuthority(ControlAuthorityCodec.Request request, ControlClientJournal.Credential credential,
                         ControlClientJournal.Grant grant, long attempt) {
            this.request = request; this.credential = credential; this.grant = grant; this.attempt = attempt;
        }
    }

    private final class SynchronizationExchange implements ControlClientIo.Synchronization {
        private final long generation = attempt, version = synchronizationVersion;
        private final ControlWriterFence writer = snapshot.writer();
        private final ControlClientJournal.Grant grant = snapshot.grant();
        private final ControlAuthorityCodec.Verified proof;
        private final long deadline;
        private CompletableFuture<ControlSynchronizationResult> confirmation;
        private ControlStateCodec.Acknowledgement acknowledgement;
        private boolean appliedCalled, sent, received;
        SynchronizationExchange(ControlAuthorityCodec.Verified proof, long deadline) { this.proof = proof; this.deadline = deadline; }
        @Override public long deadlineMillis() { return deadline; }
        private boolean current() {
            return synchronizationExchange == this && generation == attempt && version == synchronizationVersion
                    && state == State.SYNCHRONIZING && synchronizationInFlight && clock.nowMillis() < deadline
                    && writer.equals(snapshot.writer()) && grant.equals(snapshot.grant()) && authority == proof && hasAuthority();
        }
        @Override public void requireCurrent() { synchronized (ControlClientCoordinator.this) {
            if (!current()) throw new IllegalStateException("Synchronization writer or authority changed");
        } }
        @Override public Optional<byte[]> pendingHeartbeat() { synchronized (ControlClientCoordinator.this) {
            requireCurrent(); var pending = snapshot.pending();
            if (pending == null) return Optional.empty();
            if (!pending.intent().operation().equals("heartbeat") || pending.candidate() != null) throw new IllegalStateException("Another lifecycle intent requires reconciliation");
            return Optional.of(pending.bodyBytes());
        } }
        @Override public CompletionStage<ControlOperationResult> heartbeat(byte[] originalBody) { synchronized (ControlClientCoordinator.this) {
            requireCurrent(); Objects.requireNonNull(originalBody);
            if (appliedCalled) throw new IllegalStateException("Application confirmation already started");
            var pending = snapshot.pending();
            if (pending == null) return submit("heartbeat", originalBody.clone(), null, false, id(), this);
            if (!pending.intent().operation().equals("heartbeat") || pending.candidate() != null || !Arrays.equals(originalBody, pending.bodyBytes()))
                throw new IllegalStateException("Cannot replace a retained lifecycle intent during synchronization");
            if (pending.receipt() != null && !pending.receipt().disposition().equals("unknown"))
                throw new IllegalStateException("Unresolved heartbeat receipt requires explicit reconciliation");
            if (pendingResult == null) pendingResult = new CompletableFuture<>();
            var result = pendingResult; pendingSynchronization = this; forceHttp = false; deliverPending();
            return result.minimalCompletionStage();
        } }
        @Override public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { synchronized (ControlClientCoordinator.this) {
            requireCurrent(); Objects.requireNonNull(basis);
            if (appliedCalled || snapshot.pending() != null && pendingSynchronization == this)
                throw new IllegalStateException("Application confirmation requires a settled synchronization heartbeat");
            if (basis.generation() != snapshot.subject().generation()) throw ControlJson.invalid("applied generation");
            appliedCalled = true;
            var summary = new ControlStateCodec.Summary(basis.desiredRevision(), basis.state(), ControlStateCodec.appliedBasisDigest(basis));
            if (!summary.equals(proof.response().state())) return CompletableFuture.completedFuture(ControlSynchronizationResult.awaitingSource());
            acknowledgement = new ControlStateCodec.Acknowledgement(id(), summary);
            confirmation = new CompletableFuture<>();
            if (writer.transport().equals("https")) {
                requireCurrent(); confirmation.complete(ControlSynchronizationResult.confirmed(this, acknowledgement));
                return confirmation.minimalCompletionStage();
            }
            if (active.stateSend != null) return CompletableFuture.completedFuture(ControlSynchronizationResult.awaitingSource());
            var connection = active; Object sendIdentity = new Object();
            try {
                byte[] payload = ControlStateCodec.encodeAcknowledgement(acknowledgement).getBytes(StandardCharsets.UTF_8);
                long now = clock.nowMillis();
                long sequence = outgoingSequence + 1;
                var frame = new ControlFrameCodec.Frame(1, "state.applied", id(), sequence, ControlFrameCodec.Direction.HOST_TO_PROVIDER,
                        config.audience(), snapshot.subject().instanceId(), snapshot.subject().generation(), writer.sessionId(), writer.sessionEpoch(),
                        writer.connectionId(), grant.capabilities(), now, deadline, ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload),
                        authentication(snapshot.currentKey()));
                String wire = ControlFrameCodec.encode(ControlFrameCodec.sign(frame, ControlFrameCodec.KeyFamily.MACHINE, snapshot.currentKey().keyPair().getPrivate()));
                requireCurrent();
                connection.stateSend = sendIdentity; outgoingSequence = sequence;
                var sending = Objects.requireNonNull(connection.link.sendText(wire), "Missing state.applied send");
                sending.whenComplete((ignored, failure) -> { synchronized (ControlClientCoordinator.this) {
                    if (connection.stateSend == sendIdentity) connection.stateSend = null;
                    if (failure != null) {
                        // Failed handoff cannot tell us whether the peer consumed this sequence.
                        if (connection == active) fail();
                        cancelConfirmation(); return;
                    }
                    if (!current()) { cancelConfirmation(); return; }
                    sent = true; completeConfirmation();
                }});
            } catch (GeneralSecurityException | RuntimeException failure) {
                // A throwing handoff may already have accepted bytes: recover the physical sequence owner.
                if (connection.stateSend == sendIdentity && connection == active) fail();
                confirmation.completeExceptionally(failure);
            }
            return confirmation.minimalCompletionStage();
        } }
        private void ready(ControlFrameCodec.Frame frame) {
            var ack = ControlStateCodec.decodeAcknowledgement(new String(frame.payloadBytes(), StandardCharsets.UTF_8));
            // A delayed response from an earlier pass cannot confirm this pass or authorize application work.
            if (acknowledgement == null || !ack.syncId().equals(acknowledgement.syncId())) return;
            if (!ack.equals(acknowledgement) || !current() || !ControlStateCodec.matches(proof.response().state(), ack)) {
                cancelConfirmation(); return;
            }
            received = true; completeConfirmation();
        }
        private void completeConfirmation() {
            if (sent && received && current()) confirmation.complete(ControlSynchronizationResult.confirmed(this, acknowledgement));
        }
        private void resync() {
            if (confirmation != null) confirmation.complete(ControlSynchronizationResult.awaitingSource());
        }
        private void cancelConfirmation() {
            if (confirmation != null) confirmation.completeExceptionally(new IllegalStateException("Application confirmation superseded or unavailable"));
        }
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
        return () -> { byte[] bytes = new byte[24]; random.nextBytes(bytes); return "c" + ProviderCrypto.base64(bytes); };
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
        if (outcomeAcknowledgementIo != null) throw new IllegalStateException("Durable outcome acknowledgement still running");
        if (cancellationInFlight) throw new IllegalStateException("Intent cancellation still running");
        desiredTransport = transport; desiredCapabilities = List.copyOf(capabilities);
        attempt++; operationInFlight = false; if (!resetAuthorityWork()) return; beginPrepare();
    }

    /** Body and stable intent are committed before network effects. Apply returned body on a serialized application executor. */
    public synchronized CompletionStage<ControlOperationResult> submit(String operation, byte[] originalBody, boolean oneOffHttps) {
        requireRunning();
        if (synchronizationExchange != null) throw new IllegalStateException("Synchronization owns the lifecycle lane");
        if (operation.equals("rotate")) throw new IllegalArgumentException("Use rotateMachineKey to persist candidate possession first");
        return submit(operation, originalBody.clone(), null, oneOffHttps, id(), null);
    }

    public synchronized CompletionStage<ControlOperationResult> rotateMachineKey() {
        requireRunning();
        if (synchronizationExchange != null) throw new IllegalStateException("Synchronization owns the lifecycle lane");
        if (snapshot.pending() != null) throw new IllegalStateException("Unresolved lifecycle intent");
        try {
            String intentId = id();
            var candidateKey = ControlClientJournal.Credential.from(id(), ProviderCrypto.generate());
            var subject = snapshot.subject();
            var context = new ControlRotationCodec.Context(subject.audience(), subject.instanceId(), subject.generation(), snapshot.currentKey().keyId(), intentId);
            var body = ControlRotationCodec.create(candidateKey.keyId(), candidateKey.keyPair(), context);
            return submit("rotate", ControlRotationCodec.encode(body).getBytes(StandardCharsets.UTF_8), candidateKey, false, intentId, null);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Cannot prepare rotation", failure); }
    }

    private CompletionStage<ControlOperationResult> submit(String operation, byte[] body, ControlClientJournal.Credential candidateKey,
                                                          boolean https, String intentId, SynchronizationExchange synchronization) {
        if (snapshot.pending() != null) throw new IllegalStateException("Unresolved lifecycle intent");
        if (!config.operations().containsKey(operation)) throw new IllegalArgumentException("No configured operation route");
        var subject = snapshot.subject();
        var intent = new ControlLifecycleCodec.Intent(1, subject.audience(), operation, subject.instanceId(), subject.generation(),
                snapshot.lastSequence() + 1, intentId, ControlFrameCodec.payloadDigest(body));
        var pending = new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), candidateKey, null);
        persist(new ControlClientJournal.Snapshot(subject, snapshot.currentKey(), snapshot.writer(), intent.sequence(), pending, snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
        CompletableFuture<ControlOperationResult> result = new CompletableFuture<>(); pendingResult = result; forceHttp = https;
        pendingSynchronization = synchronization;
        deliverPending();
        return result.minimalCompletionStage();
    }

    /** Explicit retry/reconciliation trigger. Unknown/null receipts retain the original intent; only provider terminal receipts release it. */
    public synchronized void reconcilePending() { requireRunning(); try { recover(); } catch (RuntimeException failure) { fail(); } }

    private void recover() {
        if (hasTerminalReceipt()) { stopDeregistered(); return; }
        if (outcomeAcknowledgementIo != null) return; // Timeout/cancellation never releases unsettled application work.
        attempt++; operationInFlight = false; if (!resetAuthorityWork()) return; state = State.RECONCILING;
        cancel(retry); retry = null;
        var pending = snapshot.pending(); cancellationInFlight = false; cancellationWriterSelected = false;
        cancellationIntentDigest = pending != null && pending.intent().operation().equals("heartbeat")
                && io.requiresNativeIntentCancellation(pending.intent(), pending.bodyBytes()) ? ControlLifecycleCodec.intentDigest(pending.intent()) : null;
        if (committedOutcome(pending)) { acknowledgeOutcome(pending.receipt(), this::recover); return; }
        var credential = pending != null && pending.candidate() != null ? pending.candidate() : snapshot.currentKey();
        currentStatus(credential, !credential.equals(snapshot.currentKey()));
    }

    private void currentStatus(ControlClientJournal.Credential credential, boolean canTryOldKey) {
        JsonObject payload = new JsonObject(); payload.addProperty("query", "current-writer");
        var request = request("status", config.status(), payload, id(), credential, clock.nowMillis() + config.proofMillis());
        watch(() -> io.bootstrap(config.status(), request), request.expiresAt(), reply -> {
            checkedReply(reply, config.status(), "POST", ControlSessionCodec.MAX_ENVELOPE_BYTES);
            JsonObject result = response(reply, request);
            var writer = ControlWriterFence.read(ControlJson.object(result, "writer"));
            if (!writer.keyId().equals(credential.keyId())) throw ControlJson.invalid("strong current selected key");
            if (snapshot.pending() != null) receiptStatus(credential, writer, result, request.sentAt());
            else acceptCurrent(credential, writer, result, request.sentAt());
        }, canTryOldKey ? () -> currentStatus(snapshot.currentKey(), false) : this::fail);
        // Unavailability says nothing about which key is selected. At most one independent
        // old-key query follows a candidate failure; only a verified positive status is usable.
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
            if (receipt != null && (receipt.disposition().equals("committed") || terminalNoCommit(receipt))) {
                boolean committed = receipt.disposition().equals("committed");
                if (committed && pending.intent().operation().equals("deregister")) {
                    persistPendingReceipt(receipt); stopDeregistered(); return;
                }
                if (committed && pending.candidate() != null && !credential.keyId().equals(pending.candidate().keyId())) throw ControlJson.invalid("rotation current key reconciliation");
                // A no-commit receipt never proves candidate selection. Require a
                // positive strong status under the original selected credential.
                if (!committed && !credential.equals(snapshot.currentKey())) { halt(); return; }
                if (committed && outcomeAcknowledgement(pending)) {
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(),
                            new ControlClientJournal.Pending(pending.intent(), pending.originalBody(), pending.candidate(), receipt), snapshot.pendingBootstrap(), grant(current), snapshot.authorityFloor()));
                    acknowledgeOutcome(receipt, () -> acceptCurrent(credential, writer, current, currentRequestIssuedAt)); return;
                }
                persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), null, snapshot.pendingBootstrap(), grant(current), snapshot.authorityFloor()));
                cancellationIntentDigest = null; cancellationInFlight = false; cancellationWriterSelected = false;
                long continuationAttempt = attempt; State continuationState = state;
                completeResult(ControlOperationResult.reconciled(receipt));
                // CompletableFuture completion may synchronously close, replace or resynchronize this client.
                // A newly queued intent alone keeps this reconciliation valid and is delivered after sync.
                if (attempt != continuationAttempt || state != continuationState) return;
            } else {
                if (!credential.equals(snapshot.currentKey())) { halt(); return; }
                if (receipt != null) persistPendingReceipt(receipt);
            }
            acceptCurrent(credential, writer, current, currentRequestIssuedAt);
        });
    }

    private void acceptCurrent(ControlClientJournal.Credential credential, ControlWriterFence writer, JsonObject current, long currentRequestIssuedAt) {
        if (snapshot.pending() == null) cancellationIntentDigest = null;
        if (cancellationIntentDigest != null && config.cancelIntent() == null) { halt(); return; }
        var bootstrap = snapshot.pendingBootstrap();
        if (bootstrap != null) {
            var original = ControlSessionCodec.decodeRequest(bootstrap.originalRequest());
            if (original.action().equals("activate")) {
                var intent = ControlSessionPayloadCodec.decodeRequest("activate", original.payloadBytes());
                var expected = ControlWriterFence.read(ControlJson.object(intent, "expectedWriter"));
                var proposed = proposed(original);
                if (writer.equals(proposed)) {
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current), snapshot.authorityFloor()));
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
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current), snapshot.authorityFloor()));
                } else { halt(); return; }
            } else if (!writer.equals(snapshot.writer())) {
                // No activation was attempted for this preparation. A changed strong writer invalidates it.
                persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), null, grant(current), snapshot.authorityFloor()));
            }
        }
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), credential, writer, snapshot.lastSequence(), snapshot.pending(), snapshot.pendingBootstrap(), grant(current), snapshot.authorityFloor()));
        if (current.get("writerEnabled").getAsBoolean() && snapshot.pendingBootstrap() == null && active != null && active != discardedGapConnection && active.link != null && active.writer != null
                && samePhysicalWriter(active.writer, writer) && !active.link.closed().toCompletableFuture().isDone()
                && clock.nowMillis() < snapshot.grant().sessionExpiresAt()) {
            // A committed machine rotation changes selected key/revision without replacing this socket or its frame sequences.
            active.writer = writer; authority = null; scheduleRotation(); cancellationWriterSelected = true;
            if (!cancelBeforeSynchronization()) synchronize(); return;
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
                Candidate previousCandidate = candidate; candidate = null;
                // Fence the old physical callback before aborting a superseded standby connection.
                if (previousCandidate != null && previousCandidate != active && previousCandidate.link != null) previousCandidate.link.abort();
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
        if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED || connection == discardedGapConnection) return;
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
            persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), writer, snapshot.lastSequence(), snapshot.pending(), null, grant, snapshot.authorityFloor()));
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
        state = State.SYNCHRONIZING; cancellationWriterSelected = true;
        if (!cancelBeforeSynchronization()) synchronize();
    }

    /** A retained native claim can be cancelled under the selected writer before source/application readiness. */
    private boolean cancelBeforeSynchronization() {
        if (cancellationIntentDigest == null) return false;
        if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) return true;
        var pending = snapshot.pending();
        if (pending == null) { cancellationIntentDigest = null; return false; }
        if (!pending.intent().operation().equals("heartbeat") || !cancellationIntentDigest.equals(ControlLifecycleCodec.intentDigest(pending.intent()))) { halt(); return true; }
        if (config.cancelIntent() == null) { halt(); return true; }
        if (cancellationInFlight || !cancellationWriterSelected) return true;
        var writer = snapshot.writer(); var grant = snapshot.grant(); var credential = snapshot.currentKey();
        if (grant == null || clock.nowMillis() >= grant.sessionExpiresAt() || !ownsActiveWriter()) { recover(); return true; }
        cancellationInFlight = true; state = State.RECONCILING;
        var payload = new JsonObject(); payload.add("intent", ControlJson.parse(ControlLifecycleCodec.encodeIntent(pending.intent()), ControlLifecycleCodec.MAX_INTENT_BYTES));
        payload.add("expectedWriter", writer.object()); payload.addProperty("reason", "native-application-replaced");
        var request = request("cancel-intent", config.cancelIntent(), payload, id(), credential,
                Math.min(clock.nowMillis() + config.proofMillis(), grant.sessionExpiresAt()));
        watch(() -> io.bootstrap(config.cancelIntent(), request), request.expiresAt(), reply -> {
            var result = response(reply, request);
            if (!writer.equals(snapshot.writer()) || !credential.equals(snapshot.currentKey()) || !grant.equals(snapshot.grant()) || !ownsActiveWriter()
                    || snapshot.pending() == null || !pending.intent().equals(snapshot.pending().intent())) throw ControlJson.invalid("cancellation current writer");
            var receipt = ControlLifecycleCodec.decodeReceipt(ControlJson.object(result, "receipt").toString());
            ControlLifecycleCodec.verifyReceipt(receipt, pending.intent());
            persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), null,
                    snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
            operationInFlight = false; cancellationInFlight = false; cancellationWriterSelected = false; cancellationIntentDigest = null; pendingSynchronization = null;
            long generation = attempt; State previous = state;
            completeResult(ControlOperationResult.reconciled(receipt));
            if (generation == attempt && state == previous) synchronize();
        });
        return true;
    }

    /** Explicit demand only; idle authority expiry does not start a source or strong-status polling loop. */
    public synchronized void synchronize() {
        requireRunning();
        if (snapshot.grant() == null || snapshot.writer().transport().equals("legacy-http")) throw new IllegalStateException("No controlled writer");
        if (candidate != null || state == State.PREPARING || state == State.STANDBY || state == State.ACTIVATING) throw new IllegalStateException("Bootstrap is not ready to synchronize");
        if (synchronizationInFlight || pendingAuthority != null) throw new IllegalStateException("Synchronization already in progress");
        beginAuthorityRefresh();
    }

    private void beginAuthorityRefresh() {
        if (cancelBeforeSynchronization()) return;
        if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED || pendingAuthority != null || synchronizationInFlight) return;
        // Deadline expiry fences consumers; it does not make unabortable underlying work disappear.
        if (authorityIo != null || synchronizationIo != null) { authorityUnavailable(); return; }
        cancel(authorityRetry); authorityRetry = null;
        var grant = snapshot.grant(); long now = clock.nowMillis();
        if (grant == null || now >= grant.sessionExpiresAt()) { state = State.AUTHORITY_EXPIRED; return; }
        state = State.SYNCHRONIZING;
        try {
            var subject = snapshot.subject(); var credential = snapshot.currentKey();
            var request = ControlAuthorityCodec.sign(new ControlAuthorityCodec.Request(1, "authority-request", id(), config.audience(),
                    subject.instanceId(), subject.generation(), snapshot.writer(), grant.capabilities(), now,
                    Math.min(now + config.proofMillis(), grant.sessionExpiresAt()), "POST", target(config.authority()),
                    Math.min(now + ControlAuthorityCodec.MAX_SOURCE_AGE_MILLIS, grant.sessionExpiresAt()), authentication(credential)), credential.keyPair().getPrivate());
            var pending = new PendingAuthority(request, credential, grant, attempt); pendingAuthority = pending;
            pending.timeout = scheduler.schedule(() -> { synchronized (this) { if (pendingAuthority == pending) authorityUnavailable(); }}, request.expiresAt() - now);
            authorityIo = pending;
            CompletionStage<ControlClientIo.HttpReply> operation = Objects.requireNonNull(io.authority(config.authority(), request), "Missing authority I/O");
            pending.operation = operation;
            operation.whenComplete((reply, failure) -> { synchronized (this) {
                if (authorityIo == pending) authorityIo = null;
                if (!currentPending(pending)) return;
                try {
                    if (failure != null) { authorityUnavailable(); return; }
                    checkedReply(reply, config.authority(), "POST", ControlAuthorityCodec.MAX_ENVELOPE_BYTES);
                    if (reply.status() != 200) { authorityUnavailable(); return; }
                    var raw = ControlAuthorityCodec.decodeResponse(reply.body()); var key = keys.resolve(raw.authentication().keyId());
                    var proof = ControlAuthorityCodec.verifyResponse(reply.body(), new ControlAuthorityCodec.ResponseContext(request,
                            clock.nowMillis(), grant.sessionExpiresAt(), 30000, authorityFloor()), key);
                    proof.requireFreshDelivery(clock.nowMillis(), authorityFloor());
                    if (!proof.response().permissions().contains("control.status") || !currentPending(pending) || !currentKey(key)) { authorityUnavailable(); return; }
                    var nextFloor = new ControlClientJournal.AuthorityFloor(proof.originalWire());
                    nextFloor.requireAtLeast(snapshot.authorityFloor());
                    persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(),
                            snapshot.pending(), snapshot.pendingBootstrap(), snapshot.grant(), nextFloor));
                    // Disk writes and completion callbacks may reenter the client. The nonce remains pending until this fence.
                    if (!currentPending(pending)) return;
                    if (!currentKey(key)) { authorityUnavailable(); return; }
                    proof.requireFreshDelivery(clock.nowMillis(), authorityFloor());
                    cancel(pending.timeout); pendingAuthority = null; authority = proof; authorityKey = key;
                    if (pendingApplicationFrame != null && pendingApplicationFrame.proof() != proof) {
                        // An already consumed application frame lost its original scope. Recover ordered delivery.
                        replaceAfterFrameGap(); return;
                    }
                    applySynchronizedState(proof);
                } catch (RuntimeException invalid) { if (currentPending(pending)) authorityUnavailable(); }
            }});
        } catch (GeneralSecurityException | RuntimeException failure) {
            if (authorityIo != null && authorityIo.operation == null) authorityIo = null;
            authorityUnavailable();
        }
    }

    private boolean currentPending(PendingAuthority pending) {
        return pendingAuthority == pending && pending.attempt == attempt && state != State.CLOSED && state != State.UNRESOLVED
                && pending.request.writer().equals(snapshot.writer()) && pending.credential.equals(snapshot.currentKey())
                && pending.grant.equals(snapshot.grant()) && ownsActiveWriter();
    }
    private boolean ownsActiveWriter() {
        return snapshot.writer().transport().equals("https") || active != null && active != discardedGapConnection && active.link != null && active.writer != null
                && active.writer.equals(snapshot.writer()) && !active.link.closed().toCompletableFuture().isDone();
    }
    private ControlAuthorityCodec.Floor authorityFloor() { return snapshot.authorityFloor() == null ? null : snapshot.authorityFloor().value(); }
    private boolean currentKey(ControlFrameCodec.VerificationKey key) {
        if (key == null || key.family() != ControlFrameCodec.KeyFamily.PROVIDER_CONTROL) return false;
        long now = clock.nowMillis();
        return now >= key.validFrom() && now < key.validUntil() && key.equals(keys.resolve(key.keyId()));
    }
    private boolean hasAuthority() {
        try {
            if (authority == null || !ownsActiveWriter() || !authority.response().writer().equals(snapshot.writer()) || !currentKey(authorityKey)
                    || !authority.response().permissions().contains("control.status")) return false;
            authority.requireUnexpired(clock.nowMillis());
            if (authorityFloor() != null) ControlAuthorityCodec.checkFloor(authority.response(), authorityFloor());
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    private void authorityUnavailable() {
        if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) return;
        if (pendingAuthority != null) cancel(pendingAuthority.timeout);
        pendingAuthority = null; authority = null; authorityKey = null; synchronizationInFlight = false; synchronizationVersion++;
        var invalidated = synchronizationExchange; synchronizationExchange = null; pendingSynchronization = null;
        cancel(synchronizationTimeout); synchronizationTimeout = null; state = State.AUTHORITY_EXPIRED;
        cancel(authorityRetry);
        var grant = snapshot.grant();
        if (grant == null || clock.nowMillis() >= grant.sessionExpiresAt()) { if (invalidated != null) invalidated.cancelConfirmation(); return; }
        long bound = backoffBound(authorityFailures); authorityFailures = Math.min(authorityFailures + 1, 30);
        double random = jitter.getAsDouble();
        if (!Double.isFinite(random) || random < 0 || random >= 1) { halt(); if (invalidated != null) invalidated.cancelConfirmation(); return; }
        long generation = attempt;
        authorityRetry = scheduler.schedule(() -> { synchronized (this) {
            if (generation == attempt && state == State.AUTHORITY_EXPIRED) beginAuthorityRefresh();
        }}, Math.max(50, (long) (bound * (0.5 + random * 0.5))));
        // No transition work follows callbacks: they may close or replace this coordinator.
        if (invalidated != null) invalidated.cancelConfirmation();
    }
    private void applySynchronizedState(ControlAuthorityCodec.Verified proof) {
        synchronizationInFlight = true; state = State.SYNCHRONIZING;
        long generation = attempt, synchronization = ++synchronizationVersion;
        long deadline = Math.min(clock.nowMillis() + config.proofMillis(), proof.response().authorityExpiresAt());
        var exchange = new SynchronizationExchange(proof, deadline); synchronizationExchange = exchange;
        synchronizationTimeout = scheduler.schedule(() -> { synchronized (this) {
            if (generation == attempt && synchronization == synchronizationVersion && synchronizationInFlight) authorityUnavailable();
        }}, Math.max(0, deadline - clock.nowMillis()));
        Object operationIdentity = new Object(); synchronizationIo = operationIdentity;
        try {
            var operation = Objects.requireNonNull(io.synchronize(snapshot.writer(), snapshot.grant(), proof, exchange), "Missing synchronization I/O");
            operation.whenComplete((result, failure) -> { synchronized (this) {
                if (synchronizationIo == operationIdentity) synchronizationIo = null;
                if (generation != attempt || synchronization != synchronizationVersion || state == State.CLOSED || state == State.UNRESOLVED) return;
                cancel(synchronizationTimeout); synchronizationTimeout = null; synchronizationInFlight = false;
                Throwable cause = failure;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof ControlClientIo.ReconciliationRequired) {
                    var pending = snapshot.pending();
                    if (pending == null || !pending.intent().operation().equals("heartbeat")) { halt(); return; }
                    cancellationIntentDigest = ControlLifecycleCodec.intentDigest(pending.intent());
                    attempt++; operationInFlight = false; cancellationInFlight = false; if (!resetAuthorityWork()) return; authority = null; cancellationWriterSelected = true;
                    cancelBeforeSynchronization(); return;
                }
                // Delivery has already been consumed. Only the installed inner authority/current trust applies here.
                if (failure != null || result == null || !result.belongsTo(exchange, proof.response().state())
                        || clock.nowMillis() >= deadline || authority != proof || !hasAuthority()
                        || synchronizationExchange != exchange || !exchange.writer.equals(snapshot.writer()) || !exchange.grant.equals(snapshot.grant())
                        || snapshot.pending() != null && pendingSynchronization == exchange) { authorityUnavailable(); return; }
                synchronizationExchange = null; pendingSynchronization = null;
                state = State.READY; failures = 0; authorityFailures = 0; cancel(authorityTimer);
                authorityTimer = scheduler.schedule(() -> { synchronized (this) { if (generation == attempt) expireAuthority(); }}, proof.response().authorityExpiresAt() - clock.nowMillis());
                try {
                    flushQuarantinedFrame();
                    flushApplicationFrame();
                } catch (RuntimeException deliveryFailure) {
                    // This runs in a CompletionStage callback; an ignored dependent failure must not leave READY.
                    if (generation == attempt && state == State.READY) {
                        if (hasAuthority()) replaceAfterFrameGap();
                        else authorityUnavailable();
                    }
                    return;
                }
                if (generation == attempt && state == State.READY && authority == proof && hasAuthority()) deliverPending();
            }});
            flushQuarantinedFrame();
        } catch (RuntimeException failure) {
            if (synchronizationIo == operationIdentity) synchronizationIo = null;
            authorityUnavailable();
        }
    }
    private boolean resetAuthorityWork() {
        long generation = attempt; State phase = state;
        if (pendingAuthority != null) cancel(pendingAuthority.timeout);
        pendingAuthority = null; cancel(authorityRetry); authorityRetry = null;
        cancel(synchronizationTimeout); synchronizationTimeout = null;
        var invalidated = synchronizationExchange;
        synchronizationInFlight = false; synchronizationVersion++; synchronizationExchange = null; pendingSynchronization = null;
        long version = synchronizationVersion;
        quarantinedFrame = null; quarantinedGap = false; pendingApplicationFrame = null;
        if (invalidated != null) invalidated.cancelConfirmation();
        return generation == attempt && version == synchronizationVersion && phase == state;
    }

    private void activeFrame(String wire) {
        expireAuthority();
        if (!hasAuthority()) {
            // Authenticate only bounded gap evidence here; do not accept sequence, dispatch or replace during source lag.
            retainQuarantinedFrame(wire);
            if (pendingAuthority == null && !synchronizationInFlight && authorityRetry == null) beginAuthorityRefresh();
            return;
        }
        var raw = ControlFrameCodec.decode(wire);
        // Applying required state is a separate gate. Preserve ordering instead of accepting then dropping application work.
        if (quarantinedFrame != null) {
            if (quarantinedFrame.equals(wire)) { flushQuarantinedFrame(); return; }
            long generation = attempt; var connection = active;
            flushQuarantinedFrame();
            if (attempt != generation || active != connection || quarantinedFrame != null) return;
            activeFrame(wire); return;
        }
        if (pendingApplicationFrame != null && pendingApplicationFrame.frame().equals(raw)) return;
        var writer = snapshot.writer(); var grant = snapshot.grant();
        var context = new ControlFrameCodec.Context(ControlFrameCodec.Direction.PROVIDER_TO_HOST, config.audience(), snapshot.subject().instanceId(), snapshot.subject().generation(),
                writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), grant.capabilities(), incomingSequence, clock.nowMillis(), authority.response().authorityExpiresAt(), 30000);
        var frameKey = keys.resolve(raw.authentication().keyId());
        var frame = ControlFrameCodec.verify(wire, context, frameKey);
        if (clock.nowMillis() >= frame.expiresAt() || !currentKey(frameKey) || !hasAuthority()) throw ControlJson.invalid("dispatch authority");
        if ((frame.type().startsWith("assisted.") || frame.type().startsWith("diagnostic."))
                && !authority.response().permissions().contains("control.assisted")) throw ControlJson.invalid("assisted session authority");
        incomingSequence++;
        if (frame.type().equals("lifecycle.receipt")) acceptResult(ControlResultCodec.decode(new String(frame.payloadBytes(), StandardCharsets.UTF_8)),
                resultGuard(writer, grant, attempt, frame.expiresAt(), frameKey, pendingSynchronization));
        else if (frame.type().equals("session.reconnect")) replaceTransport(desiredTransport, desiredCapabilities);
        else if (frame.type().equals("session.ready")) {
            if (state == State.SYNCHRONIZING && synchronizationExchange != null) synchronizationExchange.ready(frame);
        }
        else if (state == State.SYNCHRONIZING && frame.type().equals("session.resync") && synchronizationExchange != null && synchronizationExchange.confirmation != null)
            synchronizationExchange.resync();
        else if (state == State.SYNCHRONIZING && frame.type().equals("state.desired") && synchronizationExchange != null && synchronizationExchange.appliedCalled)
            authorityUnavailable();
        else if (state == State.SYNCHRONIZING && List.of("session.resync", "state.desired").contains(frame.type()))
            io.onSynchronizationFrame(frameDelivery(frame, frameKey));
        else if (state == State.SYNCHRONIZING) {
            if (pendingApplicationFrame != null) { replaceAfterFrameGap(); return; }
            pendingApplicationFrame = new PendingApplicationFrame(frame, frameKey, authority, attempt, synchronizationVersion);
        }
        else if (state == State.READY) io.onVerifiedFrame(frameDelivery(frame, frameKey));
    }

    private void flushApplicationFrame() {
        var pending = pendingApplicationFrame;
        if (pending == null || state != State.READY || !hasAuthority()) return;
        if (pending.proof() != authority || pending.attempt() != attempt || pending.synchronization() != synchronizationVersion
                || clock.nowMillis() >= pending.frame().expiresAt() || !currentKey(pending.key()) || quarantinedGap) {
            replaceAfterFrameGap(); return;
        }
        io.onVerifiedFrame(frameDelivery(pending.frame(), pending.key()));
        if (pendingApplicationFrame == pending) pendingApplicationFrame = null;
    }

    private ControlFrameDelivery frameDelivery(ControlFrameCodec.Frame frame, ControlFrameCodec.VerificationKey frameKey) {
        long generation = attempt, synchronization = synchronizationVersion;
        var writer = snapshot.writer(); var grant = snapshot.grant(); var proof = authority; var phase = state;
        return new ControlFrameDelivery(frame, () -> { synchronized (this) {
            if (generation != attempt || state != phase || synchronization != synchronizationVersion
                    || authority != proof || !hasAuthority() || !currentKey(frameKey)
                    || !writer.equals(snapshot.writer()) || !grant.equals(snapshot.grant())
                    || clock.nowMillis() >= frame.expiresAt())
                throw new IllegalStateException("Frame delivery writer, authority, phase or deadline changed");
        } });
    }

    private void replaceAfterFrameGap() {
        discardedGapConnection = active;
        replaceTransport(desiredTransport, desiredCapabilities);
    }

    private void flushQuarantinedFrame() {
        if (quarantinedFrame == null || !hasAuthority()) return;
        String wire = quarantinedFrame;
        var frame = ControlFrameCodec.decode(wire);
        if (frame.sequence() < incomingSequence) { quarantinedFrame = null; quarantinedGap = false; return; }
        if (quarantinedGap || frame.expiresAt() <= clock.nowMillis() || frame.sequence() != incomingSequence) {
            quarantinedFrame = null; quarantinedGap = false;
            // An expired signature is evidence of a lost ordered frame only, never permission to dispatch it.
            // Wait for positive fresh source authority first; unverified traffic cannot cause a replacement CAS.
            if (authenticatedControlEvidence(frame) && hasAuthority()) replaceAfterFrameGap();
            return;
        }
        if (state != State.READY && state != State.SYNCHRONIZING) return;
        quarantinedFrame = null; quarantinedGap = false;
        activeFrame(wire);
    }

    private void retainQuarantinedFrame(String wire) {
        try {
            if (wire.equals(quarantinedFrame)) return;
            var frame = ControlFrameCodec.decode(wire);
            if (frame.sequence() < incomingSequence || !authenticatedControlEvidence(frame)) return;
            if (pendingApplicationFrame != null) { quarantinedGap = true; return; }
            if (quarantinedFrame != null) {
                var previous = ControlFrameCodec.decode(quarantinedFrame);
                if (previous.equals(frame)) return;
                // One retained body is the hard bound. A second authenticated frame proves information was lost.
                quarantinedGap = true;
                if (frame.sequence() < previous.sequence()) return;
            }
            quarantinedFrame = wire;
        } catch (RuntimeException invalid) { /* Unauthenticated traffic cannot displace a retained frame or record a gap. */ }
    }

    /** Signature/physical-session evidence only. This deliberately does not authorize dispatch or a replacement CAS. */
    private boolean authenticatedControlEvidence(ControlFrameCodec.Frame frame) {
        var writer = snapshot.writer(); var subject = snapshot.subject(); var grant = snapshot.grant();
        long generation = attempt;
        var key = keys.resolve(frame.authentication().keyId());
        long now = clock.nowMillis();
        if (grant == null || now >= grant.sessionExpiresAt() || !ownsActiveWriter() || !currentKey(key) || frame.direction() != ControlFrameCodec.Direction.PROVIDER_TO_HOST
                || !frame.audience().equals(subject.audience()) || !frame.instanceId().equals(subject.instanceId()) || frame.generation() != subject.generation()
                || !frame.sessionId().equals(writer.sessionId()) || frame.sessionEpoch() != writer.sessionEpoch() || !frame.connectionId().equals(writer.connectionId())
                || !frame.capabilities().equals(grant.capabilities()) || frame.sentAt() > now + 30000 || frame.sentAt() < key.validFrom()
                || frame.sentAt() < grant.activatedAt() - 30000 || frame.expiresAt() > key.validUntil() || frame.expiresAt() > grant.sessionExpiresAt()) return false;
        try {
            var verifier = java.security.Signature.getInstance("SHA384withECDSAinP1363Format");
            verifier.initVerify(key.key()); verifier.update(ControlFrameCodec.signingBytes(frame));
            return verifier.verify(ControlJson.base64(frame.authentication().signature(), 96, false)) && currentKey(key)
                    && generation == attempt && snapshot.writer().equals(writer) && snapshot.subject().equals(subject)
                    && snapshot.grant().equals(grant) && ownsActiveWriter() && state != State.CLOSED && state != State.UNRESOLVED;
        } catch (GeneralSecurityException failure) { return false; }
    }

    private void deliverPending() {
        if (cancelBeforeSynchronization()) return;
        expireAuthority();
        var pending = snapshot.pending(); var grant = snapshot.grant();
        boolean initialHeartbeat = pendingSynchronization != null && pendingSynchronization.current()
                && pending != null && pending.intent().operation().equals("heartbeat");
        if (pending == null || operationInFlight || outcomeAcknowledgementIo != null || grant == null || state != State.READY && state != State.AUTHORITY_EXPIRED && !initialHeartbeat
                || pending.receipt() != null && !pending.receipt().disposition().equals("unknown") || clock.nowMillis() >= grant.sessionExpiresAt()) return;
        if (state == State.AUTHORITY_EXPIRED && !forceHttp && !snapshot.writer().transport().equals("https")) {
            if (pendingAuthority == null && !synchronizationInFlight && authorityRetry == null) beginAuthorityRefresh();
            return;
        }
        operationInFlight = true;
        try {
            var writer = snapshot.writer(); long now = clock.nowMillis();
            boolean useHttp = forceHttp || writer.transport().equals("https") || state != State.READY && !initialHeartbeat
                    || pending.bodyBytes().length > ControlLifecycleCodec.MAX_WS_BODY_BYTES;
            long operationExpiresAt = Math.min(now + config.proofMillis(), grant.sessionExpiresAt());
            if (initialHeartbeat) operationExpiresAt = Math.min(operationExpiresAt, pendingSynchronization.deadline);
            if (useHttp) {
                URI endpoint = config.operations().get(pending.intent().operation());
                var proof = new ControlHttpCodec.Request(1, config.audience(), "POST", target(endpoint), now, operationExpiresAt, pending.intent(),
                        writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), writer.transport(), grant.capabilities(), authentication(snapshot.currentKey()));
                proof = ControlHttpCodec.sign(proof, snapshot.currentKey().keyPair().getPrivate());
                final var signedProof = proof;
                var bodyGuard = resultGuard(writer, grant, attempt, signedProof.expiresAt(), null, pendingSynchronization);
                watch(() -> io.operation(endpoint, signedProof, pending.bodyBytes()), signedProof.expiresAt(), reply -> {
                    checkedReply(reply, endpoint, "POST", ControlResultCodec.MAX_ENVELOPE_BYTES);
                    if (reply.status() != 200) throw ControlJson.invalid("operational HTTPS response");
                    acceptResult(ControlResultCodec.decode(reply.body()), bodyGuard);
                });
            } else {
                byte[] payload = ControlLifecycleCodec.encodeWsRequest(pending.intent(), pending.bodyBytes()).getBytes(StandardCharsets.UTF_8);
                var frame = new ControlFrameCodec.Frame(1, "lifecycle.request", id(), ++outgoingSequence, ControlFrameCodec.Direction.HOST_TO_PROVIDER,
                        config.audience(), snapshot.subject().instanceId(), snapshot.subject().generation(), writer.sessionId(), writer.sessionEpoch(), writer.connectionId(), grant.capabilities(),
                        now, Math.min(operationExpiresAt, authority.response().authorityExpiresAt()), ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload), authentication(snapshot.currentKey()));
                var signed = ControlFrameCodec.sign(frame, ControlFrameCodec.KeyFamily.MACHINE, snapshot.currentKey().keyPair().getPrivate());
                // Local send completion is not a durable receipt. Keep the intent and the one-flight barrier.
                watch(() -> active.link.sendText(ControlFrameCodec.encode(signed)), frame.expiresAt(), ignored -> { });
                long generation = attempt;
                scheduler.schedule(() -> { synchronized (this) { if (attempt == generation && operationInFlight && snapshot.pending() != null && snapshot.pending().intent().equals(pending.intent())) recover(); }}, frame.expiresAt() - now);
            }
        } catch (GeneralSecurityException | RuntimeException failure) { fail(); }
    }

    private Runnable resultGuard(ControlWriterFence writer, ControlClientJournal.Grant grant, long generation,
                                 long deadline, ControlFrameCodec.VerificationKey frameKey, SynchronizationExchange synchronization) {
        return () -> { synchronized (this) {
            if (generation != attempt || state == State.CLOSED || state == State.UNRESOLVED || !writer.equals(snapshot.writer())
                    || !grant.equals(snapshot.grant()) || !writer.keyId().equals(snapshot.currentKey().keyId())
                    || clock.nowMillis() >= deadline || clock.nowMillis() >= grant.sessionExpiresAt() || !ownsActiveWriter()
                    || frameKey != null && (!currentKey(frameKey) || !hasAuthority()) || synchronization != null && !synchronization.current())
                throw new IllegalStateException("Operation result writer, authority or deadline changed");
        } };
    }

    private void acceptResult(ControlResultCodec.Result result, Runnable bodyGuard) {
        var pending = snapshot.pending(); if (pending == null) return;
        var receipt = result.receipt();
        ControlLifecycleCodec.verifyReceipt(receipt, pending.intent());
        if (pending.receipt() != null && pending.receipt().disposition().equals("committed") && !pending.receipt().equals(receipt))
            throw ControlJson.invalid("immutable committed receipt changed");
        operationInFlight = false;
        if (terminalNoCommit(receipt)) {
            // Durable removal retains the sequence floor and original credential.
            // Never expose a response body or promote a rotation candidate here.
            persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), null, snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
            pendingSynchronization = null;
            completeResult(ControlOperationResult.reconciled(receipt));
            return; // Completion can synchronously close, replace, or submit.
        }
        if (!receipt.disposition().equals("committed")) { persistPendingReceipt(receipt); return; }
        if (pending.intent().operation().equals("deregister")) {
            persistPendingReceipt(receipt); stopDeregistered(); return;
        }
        if (pending.candidate() != null) { persistPendingReceipt(receipt); recover(); return; }
        if (outcomeAcknowledgement(pending)) {
            if (pending.receipt() != null && pending.receipt().disposition().equals("committed") && !pending.receipt().equals(receipt)) throw ControlJson.invalid("committed outcome receipt changed");
            persistPendingReceipt(receipt); acknowledgeOutcome(receipt, () -> { }); return;
        }
        long generation = attempt;
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), null, snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
        pendingSynchronization = null;
        try { bodyGuard.run(); }
        catch (IllegalStateException changed) {
            // Commit knowledge survives, but expired/superseded delivery cannot install application state or secrets.
            completeResult(ControlOperationResult.reconciled(receipt));
            if (generation == attempt && (state == State.READY || state == State.SYNCHRONIZING)) authorityUnavailable();
            return;
        }
        completeResult(ControlOperationResult.delivered(result, bodyGuard));
    }

    private boolean outcomeAcknowledgement(ControlClientJournal.Pending pending) {
        return pending != null && pending.intent().operation().equals("outcomes") && io.requiresOutcomeAcknowledgement();
    }
    private boolean committedOutcome(ControlClientJournal.Pending pending) {
        return outcomeAcknowledgement(pending) && pending.receipt() != null && pending.receipt().disposition().equals("committed");
    }
    private void acknowledgeOutcome(ControlLifecycleCodec.Receipt receipt, Runnable continuation) {
        if (outcomeAcknowledgementIo != null) return;
        var pending = snapshot.pending();
        if (!committedOutcome(pending) || !receipt.equals(pending.receipt())) throw new IllegalStateException("Missing owned committed outcomes receipt");
        Object identity = new Object(); outcomeAcknowledgementIo = identity; operationInFlight = true;
        long generation = attempt;
        var timeout = scheduler.schedule(() -> { synchronized (this) {
            if (outcomeAcknowledgementIo == identity && attempt == generation) fail();
        }}, config.proofMillis());
        CompletionStage<Void> work;
        try { work = Objects.requireNonNull(io.acknowledgeCommittedOutcomes(pending.intent(), pending.bodyBytes(), receipt)); }
        catch (RuntimeException failure) { work = CompletableFuture.failedFuture(failure); }
        work.whenComplete((ignored, failure) -> { synchronized (this) {
            if (outcomeAcknowledgementIo != identity) return;
            outcomeAcknowledgementIo = null; timeout.cancel(); operationInFlight = false;
            if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) return;
            if (generation != attempt) {
                // A timed-out hook may still have durably applied the queue. Retry only the idempotent
                // local hook after settlement; never overlap it or retransmit the committed operation.
                cancel(retry); long currentAttempt = attempt;
                retry = scheduler.schedule(() -> { synchronized (this) {
                    if (attempt == currentAttempt && state != State.CLOSED && state != State.UNRESOLVED) recover();
                }}, config.baseBackoffMillis());
                return;
            }
            var current = snapshot.pending();
            if (current == null || !current.intent().equals(pending.intent()) || !receipt.equals(current.receipt())) { halt(); return; }
            if (failure != null) { fail(); return; }
            try {
                persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), null,
                        snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
                pendingSynchronization = null; State priorState = state;
                completeResult(ControlOperationResult.reconciled(receipt));
                if (attempt == generation && state == priorState) continuation.run();
            } catch (RuntimeException unavailable) { fail(); }
        }});
    }

    /** Only a verified provider terminal decision; carrier clocks never call this. */
    private static boolean terminalNoCommit(ControlLifecycleCodec.Receipt receipt) {
        return receipt.disposition().equals("rejected") || receipt.disposition().equals("expired") || receipt.disposition().equals("cancelled");
    }

    private void persistPendingReceipt(ControlLifecycleCodec.Receipt receipt) {
        var pending = snapshot.pending();
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(),
                new ControlClientJournal.Pending(pending.intent(), pending.originalBody(), pending.candidate(), receipt), snapshot.pendingBootstrap(), snapshot.grant(), snapshot.authorityFloor()));
    }
    private void completeResult(ControlOperationResult value) {
        var result = pendingResult; pendingResult = null;
        if (result != null) result.complete(value);
    }

    private ControlSessionCodec.VerifiedResponse verifiedReply(ControlClientIo.HttpReply reply, ControlSessionCodec.Request request) {
        URI endpoint = switch (request.action()) { case "prepare" -> config.prepare(); case "activate" -> config.activate(); case "cancel-intent" -> config.cancelIntent(); default -> config.status(); };
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
        watch(operation, deadline, success, this::fail);
    }

    private <T> void watch(Supplier<CompletionStage<T>> operation, long deadline, Consumer<T> success, Runnable unavailable) {
        long generation = attempt;
        Runnable failed = () -> {
            if (generation != attempt || state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) return;
            try { unavailable.run(); } catch (RuntimeException failure) { fail(); }
        };
        if (deadline <= clock.nowMillis()) { failed.run(); return; }
        final boolean[] done = {false};
        var timeout = scheduler.schedule(() -> { synchronized (this) {
            if (!done[0] && generation == attempt && state != State.CLOSED) { done[0] = true; failed.run(); }
        }}, deadline - clock.nowMillis());
        CompletionStage<T> future;
        try { future = Objects.requireNonNull(operation.get(), "Missing control I/O"); }
        catch (RuntimeException failure) { done[0] = true; timeout.cancel(); failed.run(); return; }
        future.whenComplete((value, failure) -> { synchronized (this) {
            if (done[0]) return; done[0] = true; timeout.cancel();
            if (generation != attempt || state == State.CLOSED || state == State.DEREGISTERED) return;
            if (failure != null || clock.nowMillis() >= deadline) { failed.run(); return; }
            try { success.accept(value); } catch (RuntimeException invalid) { failed.run(); }
        }});
    }

    private boolean hasTerminalReceipt() {
        var pending = snapshot.pending();
        return pending != null && pending.intent().operation().equals("deregister")
                && pending.receipt() != null && pending.receipt().disposition().equals("committed");
    }

    /** The retained committed intent is a terminal journal marker, never a grant or a retry barrier. */
    private void stopDeregistered() {
        if (!hasTerminalReceipt()) throw new IllegalStateException("Missing committed deregistration");
        var receipt = snapshot.pending().receipt();
        state = State.DEREGISTERED; attempt++; operationInFlight = false; if (!resetAuthorityWork()) return; authority = null;
        cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        Candidate previous = active, standby = candidate; active = null; candidate = null;
        // Fence all callbacks before closing either connection; cleanup failure cannot erase commit knowledge.
        try { if (previous != null && previous.link != null) previous.link.close(); }
        finally {
            try { if (standby != null && standby != previous && standby.link != null) standby.link.abort(); }
            finally { completeResult(ControlOperationResult.reconciled(receipt)); }
        }
    }

    private void halt() {
        if (state == State.CLOSED || state == State.DEREGISTERED) return;
        state = State.UNRESOLVED; attempt++; operationInFlight = false; if (!resetAuthorityWork()) return; authority = null;
        cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        Candidate oldCandidate = candidate, oldActive = active; candidate = null; active = null;
        if (oldCandidate != null && oldCandidate.link != null) oldCandidate.link.abort();
        if (oldActive != null && oldActive != oldCandidate && oldActive.link != null) oldActive.link.abort();
        var result = pendingResult; pendingResult = null;
        if (result != null) result.completeExceptionally(new IllegalStateException("Control client halted; durable intent retained for reconciliation"));
    }
    private void fail() {
        if (state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) return;
        attempt++; operationInFlight = false; if (!resetAuthorityWork()) return; authority = null; state = State.BACKOFF;
        Candidate oldCandidate = candidate, oldActive = active; candidate = null; active = null;
        if (oldCandidate != null && oldCandidate.link != null) oldCandidate.link.abort();
        if (oldActive != null && oldActive != oldCandidate && oldActive.link != null) oldActive.link.abort();
        cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        long bound = backoffBound(failures); failures = Math.min(failures + 1, 30);
        double random = jitter.getAsDouble(); if (!Double.isFinite(random) || random < 0 || random >= 1) { halt(); return; }
        long delay = Math.max(50, (long) (bound * (0.5 + random * 0.5))), generation = attempt;
        retry = scheduler.schedule(() -> { synchronized (this) { if (generation == attempt && state == State.BACKOFF) recover(); }}, delay);
    }
    private long backoffBound(int previousFailures) {
        long bound = config.baseBackoffMillis();
        for (int i = 0; i < previousFailures && bound < config.maxBackoffMillis(); i++) bound = Math.min(bound * 2, config.maxBackoffMillis());
        return bound;
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
    private void expireAuthority() { if (state == State.READY && !hasAuthority()) state = State.AUTHORITY_EXPIRED; }
    private void persistBootstrap(ControlSessionCodec.Request request) {
        persist(new ControlClientJournal.Snapshot(snapshot.subject(), snapshot.currentKey(), snapshot.writer(), snapshot.lastSequence(), snapshot.pending(), new ControlClientJournal.Bootstrap(ControlSessionCodec.encode(request)), snapshot.grant(), snapshot.authorityFloor()));
    }
    private void persist(ControlClientJournal.Snapshot next) {
        try {
            if (snapshot != null && snapshot.authorityFloor() != null && next.authorityFloor() == null) throw ControlJson.invalid("removed authority floor");
            if (next.authorityFloor() != null) next.authorityFloor().requireAtLeast(snapshot == null ? null : snapshot.authorityFloor());
            journal.commit(next); snapshot = next;
        }
        catch (IllegalArgumentException invalid) { halt(); throw invalid; }
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
    private String id() { String id = identifiers.get(); ControlJson.opaque(id); ControlJson.identifier(id); return id; }
    private void requireRunning() { if (state == State.STOPPED || state == State.CLOSED || state == State.UNRESOLVED || state == State.DEREGISTERED) throw new IllegalStateException("Control client is not running"); }
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
        if (state == State.CLOSED) return; state = State.CLOSED; attempt++; if (!resetAuthorityWork()) return; cancel(retry); cancel(authorityTimer); cancel(rotationTimer);
        Candidate previous = active, standby = candidate; active = null; candidate = null;
        if (previous != null && previous.link != null) previous.link.close();
        if (standby != null && standby != previous && standby.link != null) standby.link.abort();
        if (pendingResult != null) pendingResult.completeExceptionally(new IllegalStateException("Control client closed; durable intent retained"));
        journal.close();
    }
}
