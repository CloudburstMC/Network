package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Actual native application on one serialized executor. Every asynchronous continuation retains its original owner. */
final class ControlledProviderApplication {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final ControlledProviderState storage;
    private final ProviderTransport transport;
    private final Executor executor;
    private final ControlClientClock clock;
    private final Supplier<ServerStatus> status;
    private final Supplier<ProviderClient.Health> health;
    private final String region;
    private final AtomicLong version = new AtomicLong();
    private final Supplier<String> ids = ControlClientCoordinator.secureIdentifiers();
    private JsonObject data, extensions = new JsonObject(), lastResponse = new JsonObject(), liveProfile;
    private ControlStateCodec.AppliedBasis liveBasis;
    private ProviderTransport.HostProfileSnapshot liveSnapshot;
    private String acceptedDigest;
    private Consumer<Throwable> fatal = ignored -> { };
    private Consumer<String> diagnosticNotice = ignored -> { };
    private final ControlledDiagnosticApplication diagnostics;
    private boolean installed, demand = true, permanentlyDrained, nativeClosed;
    private volatile boolean closed;
    private long nextHeartbeat, nextUpdate, snapshotClock;
    private ServerStatus lastStatus;
    private ProviderClient.Health lastHealth;
    private final boolean issuedOwnership;
    private final boolean maintainedCandidates;
    private ProviderTransport.CandidateLeaseSnapshot latestCandidates, acceptedCandidates;
    private boolean reflexivePublicationAllowed;
    private PendingCandidatePublication pendingCandidatePublication;
    private record PendingCandidatePublication(String bodyDigest, ProviderTransport.CandidateLeaseSnapshot capture,
                                               CandidateLeaseCodec.NativeOwner owner) { }
    private ProviderTransport.NativeIdentitySnapshot nativeIdentity;
    private CandidateLeaseCodec.NativeOwner observedNativeOwner, liveNativeOwner;
    private boolean ownerDiscovered, ownerServingDesired;
    private PendingOwnerClaim pendingOwnerClaim;
    private record PendingOwnerClaim(ControlledNativeOwner.Claim claim, ProviderTransport.HostProfileSnapshot profile,
                                     ProviderTransport.NativeIdentitySnapshot identity) {
        void requireCurrent() { profile.requireCurrent(); identity.requireCurrent(); }
    }

    ControlledProviderApplication(ControlledProviderState storage, ProviderTransport transport, Executor executor,
            ControlClientClock clock, Supplier<ServerStatus> status, Supplier<ProviderClient.Health> health, String region) {
        this.storage = storage; this.transport = transport; this.executor = executor; this.clock = clock;
        this.status = status; this.health = health; this.region = region; this.data = storage.application();
        issuedOwnership = data.has("nativeOwnership");
        maintainedCandidates = data.has("candidatePublication");
        if (maintainedCandidates && !transport.supportsMaintainedCandidateLeases()) throw new IllegalArgumentException("Maintained candidate transport required");
        diagnostics = new ControlledDiagnosticApplication(storage, transport, executor, clock, () -> data, this::save,
                () -> nativeOwnerCurrent() ? liveNativeOwner : null, () -> demand = true, code -> diagnosticNotice.accept(code));
    }
    static boolean requiresNativeCancellation(ControlLifecycleCodec.Intent intent, byte[] originalBody) {
        if (!intent.operation().equals("heartbeat")) return false;
        ControlLifecycleCodec.verifyBody(intent, originalBody);
        var body = ControlledProviderJson.parse(new String(originalBody, StandardCharsets.UTF_8), ControlLifecycleCodec.MAX_HTTP_BODY_BYTES);
        String diagnostic = ControlledProviderJson.rootProperty(new String(originalBody, StandardCharsets.UTF_8), "diagnosticAdmission", ControlLifecycleCodec.MAX_HTTP_BODY_BYTES);
        return body.has("hostProfile") || body.has("candidateLeases") || body.has("applicationAck") || body.has("acceptingPlayers") && body.get("acceptingPlayers").getAsBoolean()
                || diagnostic != null && ControlDiagnosticHeartbeatCodec.decodeRequest(diagnostic).installed() != null;
    }
    CompletionStage<Void> acknowledgeOutcomes(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) {
        byte[] owned = originalBody.clone();
        return CompletableFuture.runAsync(() -> {
            if (closed) throw new IllegalStateException("Application closed");
            try { storage.acknowledgeOutcomes(intent, owned, receipt); }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, executor);
    }
    CompletionStage<Void> acknowledgeNativeOwner(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) {
        byte[] owned = originalBody.clone();
        return CompletableFuture.runAsync(() -> {
            var claim = ControlledNativeOwner.claim(owned); var prepared = pendingOwnerClaim;
            if (claim == null) throw new IllegalStateException("Missing original native claim");
            boolean attach = !closed && prepared != null && prepared.claim().equals(claim);
            if (attach) try { prepared.requireCurrent(); } catch (IllegalStateException retired) { attach = false; }
            try { storage.acknowledgeNativeOwner(intent, owned, receipt); data = storage.application(); }
            catch (IOException failure) { throw new CompletionException(failure); }
            // A historical commit always settles locally, even when its original physical owner has
            // retired. Only this process's exact captured request may establish a live binding.
            if (attach && !closed && pendingOwnerClaim == prepared
                    && (observedNativeOwner == null || observedNativeOwner.epoch() <= claim.issued().epoch())) {
                try { prepared.requireCurrent(); liveNativeOwner = claim.issued(); }
                catch (IllegalStateException retired) { /* Historical receipt remains recorded. */ }
            }
            if (!nativeOwnerCurrent()) ownerDiscovered = false; // A historical commit changed the provider floor; discover it before a new claim.
            if (pendingOwnerClaim == prepared) pendingOwnerClaim = null;
        }, executor);
    }
    void onFatal(Consumer<Throwable> callback) { fatal = Objects.requireNonNull(callback); }
    void onDiagnostic(Consumer<String> callback) { diagnosticNotice = Objects.requireNonNull(callback); }
    void invalidate() { version.incrementAndGet(); }
    void request() { demand = true; }
    /** Runs on the application executor, including while a control response is awaited. */
    void maintainCandidates() {
        if (!maintainedCandidates || closed || nativeClosed || permanentlyDrained) return;
        var next = transport.maintainCandidateLeases(reflexivePublicationAllowed && nativeOwnerCurrent());
        if (latestCandidates == null || !latestCandidates.materialRevision().equals(next.materialRevision())) demand = true;
        latestCandidates = next;
    }
    boolean due() {
        if (closed) return false;
        if (diagnostics.lost()) demand = true;
        if (demand || clock.nowMillis() >= nextHeartbeat || !snapshotCurrent(liveSnapshot) || ownerRequired() && !nativeOwnerCurrent()) return true;
        if (acceptedCandidates != null) try { acceptedCandidates.requireCurrent(); } catch (IllegalStateException expired) { return true; }
        if (clock.nowMillis() < nextUpdate) return false;
        var current = health.get();
        return !Objects.equals(status.get(), lastStatus) || lastHealth == null
                || current.healthy() != lastHealth.healthy() || current.acceptingPlayers() != lastHealth.acceptingPlayers()
                || current.capacity() != lastHealth.capacity() || !Objects.equals(current.protocolVersion(), lastHealth.protocolVersion())
                || !Objects.equals(current.build(), lastHealth.build()) || !Objects.equals(players(current), players(lastHealth));
    }
    private static Integer players(ProviderClient.Health health) { return health.playerCount() == null ? null : health.playerCount().connectedPlayers(); }
    void extensions(JsonObject value) { extensions = value.deepCopy(); demand = true; }
    JsonObject lastResponse() { return lastResponse.deepCopy(); }
    void requestKey() throws IOException {
        if (data.getAsJsonArray("keys").size() >= 8 || data.has("keyRequestId")) throw new IOException("Admission key request is already pending or epochs are full");
        var next = data.deepCopy(); next.addProperty("keyRequestId", ids.get()); save(next); demand = true;
    }
    CompletionStage<ControlSynchronizationResult> synchronize(ControlClientIo.Synchronization exchange) {
        long owner = version.get();
        var pass = new Pass(exchange, owner);
        var operation = CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> {
            // Ownership issuance uses a host-only profile; a subsequent pass may publish reflexive candidates.
            reflexivePublicationAllowed = nativeOwnerCurrent(); maintainCandidates();
            pass.candidates = latestCandidates; pass.check();
            if ((!ownerRequired() || nativeOwnerCurrent()) && liveBasis != null && acceptedDigest != null && acceptedDigest.equals(ControlStateCodec.appliedBasisDigest(liveBasis))
                    && !due() && exchange.pendingHeartbeat().isEmpty()) {
                if (maintainedCandidates && acceptedCandidates != null) {
                    pass.candidates = acceptedCandidates; pass.leaseOwner = liveNativeOwner; pass.leased = true; pass.check();
                }
                return validateLive(pass).thenComposeAsync(valid -> {
                    pass.check(); if (valid) return confirmApplied(pass); demand = true; return initialize(pass);
                }, executor);
            }
            return initialize(pass);
        }, executor);
        return operation.handleAsync((result, failure) -> {
            if (failure == null) {
                try {
                    // Maintenance can retire the captured material while this final handler is queued.
                    pass.check();
                    if (maintainedCandidates && !nativeClosed && !permanentlyDrained) transport.candidateControlSynchronized();
                    pass.check();
                    reflexivePublicationAllowed = nativeOwnerCurrent();
                    return CompletableFuture.completedFuture(result);
                } catch (RuntimeException changed) { failure = changed; }
            }
            // A failed/late transition cannot leave a just-enabled snapshot admitting new players.
            // The coordinator keeps this I/O lane occupied until cleanup actually settles.
            liveBasis = null; liveSnapshot = null; acceptedDigest = null;
            var originalFailure = failure;
            return transport.applyState("draining").handle((ignored, cleanupFailure) -> {
                throw new CompletionException(unwrap(originalFailure));
            }).thenApply(ignored -> result);
        }, executor).thenCompose(Function.identity());
    }
    private CompletionStage<ControlSynchronizationResult> initialize(Pass pass) {
        pass.check(); demand = false;
        if (issuedOwnership && !nativeClosed && !permanentlyDrained && nativeIdentity == null) nativeIdentity = transport.captureNativeIdentity();
        if (ownerRequired() && nativeIdentity != null) nativeIdentity.requireCurrent();
        if (data.getAsJsonArray("keys").isEmpty() && !data.has("keyRequestId")) {
            var next = data.deepCopy(); next.addProperty("keyRequestId", ids.get()); save(next);
        }
        CompletionStage<Void> start = installed || nativeClosed ? CompletableFuture.completedFuture(null) : install(pass, data.deepCopy(), false, null);
        return start.thenComposeAsync(ignored -> round(pass, 0), executor);
    }
    private CompletionStage<ControlSynchronizationResult> round(Pass pass, int count) {
        pass.check();
        if (count >= 6) return CompletableFuture.failedFuture(new IOException("Controlled application exchange bound reached"));
        var retained = pass.exchange.pendingHeartbeat();
        CompletionStage<JsonObject> request = retained.isPresent()
                ? retainedHeartbeat(pass, retained.get())
                : heartbeat(pass);
        return request.thenComposeAsync(body -> {
            pass.check(); byte[] bytes = retained.orElseGet(() -> JSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            if (retained.isEmpty()) {
                pendingOwnerClaim = body.has("nativeOwnerClaim")
                        ? new PendingOwnerClaim(ControlledNativeOwner.claim(bytes), pass.latestSnapshot, nativeIdentity) : null;
                pendingCandidatePublication = body.has("candidateLeases")
                        ? new PendingCandidatePublication(ControlFrameCodec.payloadDigest(bytes), pass.candidates, liveNativeOwner) : null;
            }
            long started = clock.nowMillis();
            return pass.exchange.heartbeat(bytes, pass::check).thenComposeAsync(result -> {
                pass.check();
                if (!result.receipt().disposition().equals("committed")) throw new IllegalStateException("Heartbeat did not commit");
                // Status reconciliation has no body. The old intent is settled; only a new actual heartbeat can deliver state.
                if (!result.hasBody()) return round(pass, count + 1);
                result.requireCurrent(); String originalResponse = new String(result.bodyBytes().orElseThrow(), StandardCharsets.UTF_8);
                var response = ControlledProviderJson.parse(originalResponse, ControlResultCodec.MAX_BODY_BYTES);
                String diagnosticWire = ControlledProviderJson.rootProperty(originalResponse, "diagnosticAdmission", ControlResultCodec.MAX_BODY_BYTES);
                var diagnosticResponse = diagnosticResponse(diagnosticWire);
                // Keep optional private material out of the general application response and notices.
                response.remove("diagnosticAdmission");
                if (diagnosticResponse != null) response.add("diagnosticAdmission", JsonParser.parseString(
                        ControlDiagnosticHeartbeatCodec.encodeResponse(new ControlDiagnosticHeartbeatCodec.Response(null, diagnosticResponse.accepted()))));
                // The old ACK was checked through its actual send and original authenticated response.
                // Deliberate replacement below must not make that historical claim self-invalidating.
                result.requireCurrent(); if (pass.diagnosticClaim != null) pass.diagnosticClaim.delivered(); pass.diagnosticClaim = null;
                return apply(pass, result, body, response, started).thenComposeAsync(ignored ->
                        diagnostics.apply(diagnosticResponse, () -> { pass.check(); result.requireCurrent(); }, pass.candidates), executor).thenComposeAsync(ignored -> {
                    pass.check();
                    // Provider acceptance can survive a cancelled pass whose native application was disabled.
                    // This committed heartbeat must acknowledge the application now installed before READY.
                    if (liveBasis != null && Objects.equals(acceptedDigest, ControlStateCodec.appliedBasisDigest(liveBasis))
                            && Objects.equals(body.get("applicationAck"), applicationAcknowledgement()))
                        return validateLive(pass).thenComposeAsync(valid -> {
                            pass.check(); if (!valid) throw new IllegalStateException("Native application ceased to match its basis");
                            return confirmApplied(pass);
                        }, executor);
                    return round(pass, count + 1);
                }, executor);
            }, executor);
        }, executor);
    }
    private ControlDiagnosticHeartbeatCodec.Response diagnosticResponse(String wire) {
        if (!diagnostics.enabled || wire == null) return null;
        try { return ControlDiagnosticHeartbeatCodec.decodeResponse(wire); }
        catch (IllegalArgumentException unavailable) {
            // A valid authenticated player response remains usable when its optional diagnostic slice is unavailable.
            diagnosticNotice.accept("diagnostic_installation_unavailable"); return null;
        }
    }
    private CompletionStage<JsonObject> retainedHeartbeat(Pass pass, byte[] original) {
        pass.check();
        var body = ControlledProviderJson.parse(new String(original, StandardCharsets.UTF_8), 65536);
        String diagnostic = ControlledProviderJson.rootProperty(new String(original, StandardCharsets.UTF_8), "diagnosticAdmission", 65536);
        if (diagnostic != null) pass.diagnosticClaim = diagnostics.retain(ControlDiagnosticHeartbeatCodec.decodeRequest(diagnostic));
        if (body.has("candidateLeases")) {
            var retained = pendingCandidatePublication;
            if (retained == null || !retained.bodyDigest().equals(ControlFrameCodec.payloadDigest(original))
                    || !nativeOwnerCurrent() || !retained.owner().equals(liveNativeOwner))
                return CompletableFuture.failedFuture(new ControlClientIo.ReconciliationRequired("Retained candidate publication has no original live owner"));
            try { retained.capture().requireCurrent(); }
            catch (IllegalStateException stale) { return CompletableFuture.failedFuture(new ControlClientIo.ReconciliationRequired("Retained candidate publication expired or remapped")); }
            pass.candidates = retained.capture(); pass.leaseOwner = retained.owner(); pass.leased = true;
        }
        if (!body.has("hostProfile")) return retainedApplicationClaim(pass, body);
        // A non-accepting publication still binds the old native incarnation and endpoint set.
        // Only an exact current native profile permits an application-lane retry of its original bytes.
        return CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> {
            pass.check(); return captureProfile(pass);
        }, executor).handleAsync((actual, failure) -> {
            pass.check();
            if (failure != null || actual == null || !actual.equals(body.get("hostProfile")))
                throw new ControlClientIo.ReconciliationRequired("Retained heartbeat host profile is no longer owned");
            return body;
        }, executor).thenComposeAsync(owned -> retainedApplicationClaim(pass, owned), executor);
    }
    private CompletionStage<JsonObject> retainedApplicationClaim(Pass pass, JsonObject body) {
        pass.check();
        // Status reconciliation runs before this lane. Unknown immutable claims from another
        // native instance cannot be retransmitted; a provider terminal cancellation is required.
        if (body.has("applicationAck") || body.has("acceptingPlayers") && body.get("acceptingPlayers").getAsBoolean()) {
            if (liveBasis == null) return CompletableFuture.failedFuture(new ControlClientIo.ReconciliationRequired("Retained heartbeat claims an unowned native application"));
            return validateLive(pass).thenApplyAsync(valid -> {
                pass.check();
                boolean exact = valid && body.has("applicationAck")
                        && body.getAsJsonObject("applicationAck").get("basis").equals(JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(liveBasis)))
                        && Objects.equals(body.getAsJsonObject("applicationAck").get("ticketPolicy"), data.has("policy") ? JsonParser.parseString(string(data, "policy")) : JsonNull.INSTANCE);
                if (!exact) throw new ControlClientIo.ReconciliationRequired("Retained heartbeat native application changed");
                return body;
            }, executor);
        }
        return CompletableFuture.completedFuture(body);
    }
    private CompletionStage<JsonObject> heartbeat(Pass pass) {
        pass.check();
        CompletionStage<Boolean> validation = liveBasis == null || ownerRequired() && !nativeOwnerCurrent()
                ? CompletableFuture.completedFuture(false) : validateLive(pass);
        return validation.thenComposeAsync(valid -> {
            pass.check();
            if (!valid) { liveBasis = null; liveSnapshot = null; acceptedDigest = null; }
            return heartbeatAfterValidation(pass);
        }, executor);
    }
    private CompletionStage<JsonObject> heartbeatAfterValidation(Pass pass) {
        pass.check();
        boolean serving = !permanentlyDrained && !nativeClosed && ("serving".equals(string(data, "reportedState")) || issuedOwnership && ownerServingDesired);
        CompletionStage<JsonObject> profile = serving && !data.getAsJsonArray("keys").isEmpty()
                ? captureProfile(pass) : CompletableFuture.completedFuture(null);
        return profile.thenApplyAsync(actual -> {
            pass.check(); var body = new JsonObject();
            if (actual != null) {
                actual = ControlledProviderJson.parse(actual.toString(), 16384);
                if (issuedOwnership && !nativeOwnerCurrent()) {
                    var ownedProfile = CandidateLeaseCodec.readProfile(actual);
                    nativeIdentity.requireCurrent();
                    if (!nativeIdentity.incarnation().equals(ownedProfile.nativeIncarnation())) throw new IllegalStateException("Native profile identity mismatch");
                    if (ownerDiscovered && !data.has("keyRequestId")) {
                        var claim = new CandidateLeaseCodec.NativeOwnerClaim(observedNativeOwner == null ? 0 : observedNativeOwner.epoch(), ids.get());
                        body.add("hostProfile", actual); body.add("nativeOwnerClaim", JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwnerClaim(claim)));
                    }
                } else {
                    if (!data.has("profile") || !actual.equals(data.getAsJsonObject("profile"))) body.add("hostProfile", actual);
                    else if (data.has("profileRevision")) body.addProperty("hostProfileRevision", string(data, "profileRevision"));
                    if (maintainedCandidates) {
                        if (pass.candidates == null) throw new IllegalStateException("Candidate capture unavailable");
                        pass.leaseOwner = liveNativeOwner; pass.leased = true; pass.check();
                        body.add("candidateLeases", JsonParser.parseString(CandidateLeaseCodec.encodeLeases(
                                pass.candidates.bind(CandidateLeaseCodec.readProfile(actual), pass.leaseOwner))));
                    }
                }
            }
            var installedIds = new JsonArray(); for (var key : data.getAsJsonArray("keys")) installedIds.add(key.getAsJsonObject().get("keyId"));
            if (!installedIds.isEmpty()) body.add("installedKeyIds", installedIds);
            if (data.has("keyRequestId")) body.addProperty("keyRequestId", string(data, "keyRequestId"));
            var observation = health.get();
            body.addProperty("healthy", observation.healthy());
            body.addProperty("acceptingPlayers", observation.acceptingPlayers() && liveBasis != null && liveBasis.state().equals("serving") && !permanentlyDrained && hasAdvertisedCandidates(actual));
            body.addProperty("capacity", observation.capacity()); body.addProperty("load", observation.load());
            body.addProperty("protocolVersion", observation.protocolVersion());
            if (observation.build() != null) body.addProperty("build", observation.build());
            if (observation.playerCount() != null) body.add("playerCount", JSON.toJsonTree(observation.playerCount()));
            if (region != null) body.addProperty("region", region);
            if (!extensions.isEmpty()) body.add("extensions", extensions.deepCopy());
            snapshotClock = Math.max(clock.nowMillis(), snapshotClock + 1); body.addProperty("clockUnixMillis", snapshotClock);
            body.addProperty("checkInVersion", 1); body.addProperty("state", string(data, "reportedState"));
            body.addProperty("appliedStateRevision", number(data, "appliedRevision"));
            body.addProperty("gameOutcomes", transport.supportsGameOutcomes() ? "available" : "unavailable");
            var listing = status.get(); if (listing != null) body.add("serverStatus", JSON.toJsonTree(listing));
            if (liveBasis != null && !body.has("hostProfile")) body.add("applicationAck", applicationAcknowledgement());
            if (diagnostics.enabled) {
                var prepared = diagnostics.prepare(); pass.diagnosticClaim = prepared.claim();
                body.add("diagnosticAdmission", JsonParser.parseString(ControlDiagnosticHeartbeatCodec.encodeRequest(prepared.request())));
            }
            lastHealth = observation; lastStatus = listing; return body;
        }, executor);
    }
    private JsonObject applicationAcknowledgement() {
        var ack = new JsonObject(); ack.addProperty("version", 1);
        ack.add("basis", JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(liveBasis)));
        ack.add("ticketPolicy", data.has("policy") ? JsonParser.parseString(string(data, "policy")) : JsonNull.INSTANCE);
        return ack;
    }
    private CompletionStage<Void> apply(Pass pass, ControlOperationResult result, JsonObject body, JsonObject response, long started) {
        Runnable guard = () -> { pass.check(); result.requireCurrent(); }; guard.run();
        ProtocolExtensions.validate(response);
        if (issuedOwnership) {
            if (!response.has("nativeOwner")) throw new IllegalStateException("Provider does not support issued native ownership");
            var owner = response.get("nativeOwner").isJsonNull() ? null : CandidateLeaseCodec.decodeNativeOwner(response.get("nativeOwner").toString());
            long floor = observedNativeOwner == null ? 0 : observedNativeOwner.epoch();
            var historical = data.has("nativeOwnerReceipt") ? CandidateLeaseCodec.decodeNativeOwner(data.getAsJsonObject("nativeOwnerReceipt").get("owner").toString()) : null;
            if (historical != null) floor = Math.max(floor, historical.epoch());
            if ((owner == null ? 0 : owner.epoch()) < floor) throw new IllegalStateException("Native owner epoch rollback");
            if (owner != null && (observedNativeOwner != null && owner.epoch() == observedNativeOwner.epoch() && !owner.equals(observedNativeOwner)
                    || historical != null && owner.epoch() == historical.epoch() && !owner.equals(historical)))
                throw new IllegalStateException("Native owner tuple changed within its issued epoch");
            if (body.has("nativeOwnerClaim")) {
                var claim = CandidateLeaseCodec.decodeNativeOwnerClaim(body.get("nativeOwnerClaim").toString());
                var profile = CandidateLeaseCodec.readProfile(body.getAsJsonObject("hostProfile"));
                if (owner == null || !CandidateLeaseCodec.matchesClaim(claim, owner, profile.nativeIncarnation())) throw new IllegalStateException("Native owner response differs from original claim");
            }
            observedNativeOwner = owner; ownerDiscovered = true;
            if (liveNativeOwner != null && !liveNativeOwner.equals(owner)) liveNativeOwner = null;
        }
        var app = response.getAsJsonObject("application");
        if (app == null || !app.keySet().equals(Set.of("version", "expectedBasis", "expectedTicketPolicy", "acceptedBasisSha256"))
                || number(app, "version") != 1) throw ControlledProviderJson.invalid();
        var basis = app.get("expectedBasis").isJsonNull() ? null : ControlStateCodec.decodeAppliedBasis(app.get("expectedBasis").toString());
        var policy = app.get("expectedTicketPolicy").isJsonNull() ? null : ControlStateCodec.decodeTicketPolicy(app.get("expectedTicketPolicy").toString());
        String accepted = app.get("acceptedBasisSha256").isJsonNull() ? null : string(app, "acceptedBasisSha256");
        var desired = response.getAsJsonObject("desiredState"); long revision = number(desired, "revision"); String target = string(desired, "state");
        if (revision < number(data, "appliedRevision") || !Set.of("serving", "draining", "closed").contains(target)
                || basis != null && (basis.generation() != storage.initial.subject().generation() || basis.desiredRevision() != revision || !basis.state().equals(target))
                || basis != null && basis.state().equals("serving") && (policy == null || !ControlStateCodec.ticketPolicyDigest(policy).equals(basis.ticketPolicySha256()))
                || basis != null && !basis.state().equals("serving") && policy != null || basis == null && policy != null)
            throw ControlledProviderJson.invalid();
        ownerServingDesired = issuedOwnership && target.equals("serving");
        var next = data.deepCopy(); boolean freshKey = response.has("ticketKey");
        if (body.has("hostProfile")) {
            String profileRevision = string(response, "hostProfileRevision");
            next.add("profile", body.get("hostProfile").deepCopy()); next.addProperty("profileRevision", profileRevision);
            next.remove("candidateLeaseReceipt");
        }
        if (body.has("candidateLeases")) {
            var leases = CandidateLeaseCodec.decodeLeases(body.get("candidateLeases").toString());
            if (!response.has("candidateLeaseReceipt") || !next.has("profileRevision")) throw new IllegalStateException("Candidate lease acceptance receipt required");
            var receipt = CandidateLeaseCodec.decodeReceipt(response.get("candidateLeaseReceipt").toString());
            if (!CandidateLeaseCodec.matches(receipt, string(next, "profileRevision"), leases)) throw new IllegalStateException("Candidate lease receipt association mismatch");
            guard.run(); next.add("candidateLeaseReceipt", JsonParser.parseString(CandidateLeaseCodec.encodeReceipt(receipt)));
        }
        if (freshKey) {
            var key = response.getAsJsonObject("ticketKey"); var request = response.getAsJsonObject("keyRequest");
            if (!body.has("keyRequestId") || request == null || !string(body, "keyRequestId").equals(string(request, "id"))
                    || !string(key, "keyId").equals(string(request, "keyId"))) throw new IllegalStateException("Unbound admission key response");
            if (!next.has("keyRequestId") || !string(next, "keyRequestId").equals(string(body, "keyRequestId"))) throw new IllegalStateException("Stale admission key response");
            for (var old : next.getAsJsonArray("keys")) if (string(old.getAsJsonObject(), "keyId").equals(string(key, "keyId"))) throw new IllegalStateException("Admission key identity reused");
            var installedKey = new JsonObject(); installedKey.addProperty("keyId", string(key, "keyId")); installedKey.addProperty("secret", string(key, "secret")); installedKey.addProperty("notBefore", 0);
            next.getAsJsonArray("keys").add(installedKey); next.remove("keyRequestId"); next.remove("profile"); next.remove("basis");
            next.remove("candidateLeaseReceipt");
            acceptedDigest = null; liveBasis = null; liveSnapshot = null; liveProfile = null;
        } else if (next.has("keyRequestId") && response.has("keyRequest")
                && string(next, "keyRequestId").equals(string(response.getAsJsonObject("keyRequest"), "id"))) {
            next.addProperty("keyRequestId", ids.get()); // Lost one-time material requires a new independent request.
        }
        schedule(response, started); lastResponse = response.deepCopy(); lastResponse.remove("ticketKey");
        if (lastResponse.has("diagnosticAdmission")) lastResponse.getAsJsonObject("diagnosticAdmission").add("expected", JsonNull.INSTANCE);
        if (body.has("candidateLeases")) {
            // A new key deliberately clears the old profile association in next, but the original
            // response's already validated lease deadline still bounds this refresh.
            var receipt = CandidateLeaseCodec.decodeReceipt(response.get("candidateLeaseReceipt").toString());
            long preemptAt = receipt.expiresAt() - CandidateLeaseCodec.CLOCK_SKEW_MILLIS;
            // Once inside the preemption window, retry at the normal check-in cadence. Reusing
            // a past target would otherwise request synchronization on every maintenance tick.
            // The original capture still makes actual expiry or remapping immediately due.
            if (receipt.expiresAt() != 0 && preemptAt > clock.nowMillis()) nextHeartbeat = Math.min(nextHeartbeat, preemptAt);
        }
        if (freshKey || basis == null || issuedOwnership && basis.state().equals("serving") && !nativeOwnerCurrent()) {
            if (issuedOwnership && !nativeOwnerCurrent()) next.remove("basis");
            acceptedDigest = null; liveBasis = null; liveSnapshot = null;
            return install(pass, next, false, guard);
        }
        next.addProperty("basis", ControlStateCodec.encodeAppliedBasis(basis));
        next.addProperty("appliedRevision", basis.desiredRevision()); next.addProperty("reportedState", basis.state());
        if (policy == null) next.remove("policy"); else next.addProperty("policy", ControlStateCodec.encodeTicketPolicy(policy));
        if (!basis.state().equals("serving")) {
            // The owned response now requires no serving profile. Our own close may retire that capture.
            guard.run(); pass.endpointOwner = null; pass.latestSnapshot = null; pass.leased = false; acceptedCandidates = null;
            liveBasis = null; liveSnapshot = null; acceptedDigest = null;
            if (!nativeClosed && !permanentlyDrained) begin(pass, guard);
            return transport.applyState(basis.state()).thenAcceptAsync(applied -> {
                guard.run(); if (applied != ProviderTransport.ApplyResult.APPLIED) throw new IllegalStateException("Nonserving state was not applied");
                save(next); guard.run(); nativeClosed |= basis.state().equals("closed"); liveBasis = basis; liveSnapshot = null; liveProfile = null; acceptedDigest = accepted;
            }, executor);
        }
        if (permanentlyDrained) throw new IllegalStateException("Permanently drained native instance cannot acknowledge serving");
        next.add("keys", exactPolicyKeys(next.getAsJsonArray("keys"), policy));
        if (!next.has("profileRevision") || !basis.hostProfileRevision().equals(string(next, "profileRevision"))) throw new IllegalStateException("Expected profile was not delivered");
        if (basis.equals(liveBasis) && Objects.equals(next.get("policy"), data.get("policy"))) {
            return validateLive(pass).thenComposeAsync(valid -> {
                guard.run();
                if (valid) { save(next); guard.run(); acceptCandidates(pass, body); acceptedDigest = accepted; return CompletableFuture.completedFuture(null); }
                return applyServing(pass, next, basis, accepted, guard).thenRunAsync(() -> { guard.run(); acceptCandidates(pass, body); }, executor);
            }, executor);
        }
        return applyServing(pass, next, basis, accepted, guard).thenRunAsync(() -> { guard.run(); acceptCandidates(pass, body); }, executor);
    }
    private void acceptCandidates(Pass pass, JsonObject body) {
        if (body.has("candidateLeases")) { pass.check(); acceptedCandidates = pass.candidates; }
        else if (body.has("hostProfile")) acceptedCandidates = null;
    }
    private CompletionStage<Void> applyServing(Pass pass, JsonObject next, ControlStateCodec.AppliedBasis basis, String accepted, Runnable guard) {
        return install(pass, next, true, guard).thenComposeAsync(ignored -> captureProfile(pass), executor).thenComposeAsync(actual -> {
            guard.run();
            return transport.applyState("serving").thenComposeAsync(serving -> {
                guard.run();
                if (serving != ProviderTransport.ApplyResult.APPLIED || actual == null || !actual.equals(next.getAsJsonObject("profile"))) {
                    liveBasis = null; liveSnapshot = null; acceptedDigest = null;
                    return transport.applyState("draining").thenRunAsync(() -> {
                        guard.run(); var changed = data.deepCopy(); changed.remove("profile"); changed.remove("basis"); save(changed);
                    }, executor);
                }
                liveBasis = basis; liveSnapshot = pass.latestSnapshot; liveProfile = actual.deepCopy(); acceptedDigest = accepted;
                return CompletableFuture.completedFuture(null);
            }, executor);
        }, executor);
    }
    private CompletionStage<Void> install(Pass pass, JsonObject next, boolean enable, Runnable deliveryGuard) {
        Runnable guard = deliveryGuard == null ? pass::check : deliveryGuard;
        guard.run(); liveBasis = null; liveSnapshot = null; acceptedDigest = null;
        var update = begin(pass, guard); var keys = nativeKeys(next.getAsJsonArray("keys"));
        return transport.installTicketKeys(update, keys).thenComposeAsync(ignored -> {
            guard.run(); save(next); guard.run(); installed = true;
            if (!enable) return CompletableFuture.completedFuture(null);
            // Profile equality must be checked while staging remains disabled, before commit.
            return captureProfile(pass).thenComposeAsync(actual -> {
                guard.run(); if (actual == null || !actual.equals(next.getAsJsonObject("profile"))) return CompletableFuture.completedFuture(null);
                return transport.commitAdmissionUpdate(update, guard).thenComposeAsync(applied -> {
                    guard.run(); if (applied != ProviderTransport.ApplyResult.APPLIED) throw new IllegalStateException("Admission commit refused");
                    return transport.applyState("serving").thenAcceptAsync(serving -> {
                        guard.run(); if (serving != ProviderTransport.ApplyResult.APPLIED) throw new IllegalStateException("Native listener is not serving");
                    }, executor);
                }, executor);
            }, executor);
        }, executor);
    }
    private ProviderTransport.AdmissionUpdate begin(Pass pass, Runnable guard) {
        guard.run(); long captured = System.nanoTime(); long remaining = pass.exchange.deadlineMillis() - clock.nowMillis();
        if (remaining <= 0 || remaining > 300000) throw new IllegalStateException("Application deadline unavailable");
        var update = transport.beginAdmissionUpdate(captured + TimeUnit.MILLISECONDS.toNanos(remaining)); guard.run(); return update;
    }
    private CompletionStage<Boolean> validateLive(Pass pass) {
        pass.check(); var basis = liveBasis; if (basis == null) return CompletableFuture.completedFuture(false);
        if (basis.state().equals("serving")) {
            if (liveSnapshot == null || !snapshotCurrent(liveSnapshot)) return CompletableFuture.completedFuture(false);
            pass.own(liveSnapshot);
        }
        return transport.applyState(basis.state()).thenComposeAsync(applied -> {
            pass.check(); if (applied != ProviderTransport.ApplyResult.APPLIED) return CompletableFuture.completedFuture(false);
            if (!basis.state().equals("serving")) return CompletableFuture.completedFuture(true);
            return captureProfile(pass).thenApplyAsync(profile -> { pass.check(); return profile.equals(liveProfile); }, executor);
        }, executor);
    }
    private CompletionStage<JsonObject> captureProfile(Pass pass) {
        pass.check();
        return transport.captureHostProfile().thenApplyAsync(snapshot -> {
            pass.own(snapshot); return snapshot.profile();
        }, executor);
    }
    private static boolean snapshotCurrent(ProviderTransport.HostProfileSnapshot snapshot) {
        if (snapshot == null) return true;
        try { snapshot.requireCurrent(); return true; }
        catch (IllegalStateException unavailable) { return false; }
    }
    private static boolean hasAdvertisedCandidates(JsonObject profile) {
        return profile == null || !profile.has("version") || !profile.getAsJsonArray("candidates").isEmpty();
    }
    private CompletionStage<ControlSynchronizationResult> confirmApplied(Pass pass) {
        pass.check();
        if (issuedOwnership && liveBasis.state().equals("serving") && !nativeOwnerCurrent()) throw new IllegalStateException("Native ownership not attached");
        return pass.exchange.applied(liveBasis, pass::check).thenApplyAsync(result -> { pass.check(); return result; }, executor);
    }
    private void schedule(JsonObject response, long started) {
        if (!response.has("checkIn")) { nextHeartbeat = started + 10000; nextUpdate = clock.nowMillis() + 1000; return; }
        try {
            var schedule = CheckInSchedule.parse(response); long received = Instant.parse(string(response, "receivedAt")).toEpochMilli();
            nextHeartbeat = Math.min(started + schedule.afterMillis(), Math.max(received, clock.nowMillis())
                    + Math.max(0, number(response.getAsJsonObject("checkIn"), "nextCheckInAt") - Math.max(received, clock.nowMillis())));
            nextUpdate = clock.nowMillis() + schedule.minUpdateIntervalMillis();
        } catch (IOException failure) { throw new CompletionException(unwrap(failure)); }
    }
    static JsonArray exactPolicyKeys(JsonArray available, ControlStateCodec.TicketPolicy policy) {
        var byId = new HashMap<String, JsonObject>(); for (var item : available) byId.put(string(item.getAsJsonObject(), "keyId"), item.getAsJsonObject());
        var ordered = new ArrayList<ControlStateCodec.TicketEpoch>(policy.epochs());
        ordered.sort(Comparator.comparing(epoch -> epoch.keyId().equals(policy.activeKeyId())));
        var result = new JsonArray();
        for (var epoch : ordered) {
            var material = byId.get(epoch.keyId()); if (material == null) throw new IllegalStateException("Required admission epoch material missing");
            if (material.has("acceptUntil") && (epoch.acceptUntil() == null || epoch.acceptUntil() > number(material, "acceptUntil"))) throw new IllegalStateException("Admission epoch cutoff extended");
            var item = material.deepCopy(); item.addProperty("notBefore", epoch.notBefore());
            if (epoch.acceptUntil() == null) item.remove("acceptUntil"); else item.addProperty("acceptUntil", epoch.acceptUntil()); result.add(item);
        }
        return result;
    }
    static List<ProviderTransport.TicketKey> nativeKeys(JsonArray array) {
        var keys = new ArrayList<ProviderTransport.TicketKey>();
        for (var item : array) { var key = item.getAsJsonObject(); keys.add(new ProviderTransport.TicketKey(string(key, "keyId"), string(key, "secret"), number(key, "notBefore"), key.has("acceptUntil") ? number(key, "acceptUntil") : Long.MAX_VALUE)); }
        return List.copyOf(keys);
    }
    CompletionStage<Void> drain() {
        invalidate(); permanentlyDrained = true; liveBasis = null; liveSnapshot = null; acceptedDigest = null;
        return transport.drain().thenRunAsync(() -> { var next = data.deepCopy(); next.addProperty("reportedState", "draining"); save(next); demand = true; }, executor);
    }
    void close() { closed = true; invalidate(); liveBasis = null; liveSnapshot = null; acceptedDigest = null; liveNativeOwner = null; pendingOwnerClaim = null; }
    private boolean ownerRequired() {
        return issuedOwnership && !nativeClosed && !permanentlyDrained && (ownerServingDesired || "serving".equals(string(data, "reportedState")));
    }
    private boolean nativeOwnerCurrent() {
        if (closed || liveNativeOwner == null || nativeIdentity == null || !liveNativeOwner.nativeIncarnation().equals(nativeIdentity.incarnation())) return false;
        try { nativeIdentity.requireCurrent(); return true; }
        catch (IllegalStateException retired) { return false; }
    }
    private void save(JsonObject next) {
        try { storage.saveApplication(next); data = storage.application(); }
        catch (IOException error) {
            closed = true; invalidate(); liveBasis = null; liveSnapshot = null; acceptedDigest = null;
            // Persistence outside synchronization (for example a key request) must also fence admission.
            try { transport.applyState("draining"); } finally { fatal.accept(error); }
            throw new CompletionException(error);
        }
    }
    private final class Pass {
        final ControlClientIo.Synchronization exchange; final long owner;
        ProviderTransport.HostProfileSnapshot endpointOwner, latestSnapshot;
        ProviderTransport.CandidateLeaseSnapshot candidates;
        CandidateLeaseCodec.NativeOwner leaseOwner;
        ControlledDiagnosticApplication.Claim diagnosticClaim;
        boolean leased;
        Pass(ControlClientIo.Synchronization exchange, long owner) { this.exchange = exchange; this.owner = owner; }
        void own(ProviderTransport.HostProfileSnapshot snapshot) {
            check(); Objects.requireNonNull(snapshot).requireCurrent();
            if (endpointOwner == null) endpointOwner = snapshot;
            latestSnapshot = snapshot; check();
        }
        void check() {
            exchange.requireCurrent();
            if (closed || version.get() != owner || clock.nowMillis() >= exchange.deadlineMillis()) throw new IllegalStateException("Application owner expired or changed");
            if (endpointOwner != null) endpointOwner.requireCurrent();
            if (diagnosticClaim != null) diagnosticClaim.requireCurrent();
            if (leased) {
                candidates.requireCurrent();
                if (!nativeOwnerCurrent() || !Objects.equals(leaseOwner, liveNativeOwner)) throw new IllegalStateException("Candidate publication native owner changed");
            }
        }
    }
    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static String string(JsonObject value, String name) { return ControlledProviderJson.string(value, name); }
    private static long number(JsonObject value, String name) { return ControlledProviderJson.number(value, name); }
}
