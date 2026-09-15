package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.control.*;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec.*;

/** Serialized diagnostic side of application delivery. Disk material is a floor, never a live ACK. */
final class ControlledDiagnosticApplication {
    private final ControlledProviderState storage;
    private final ProviderTransport transport;
    private final Executor executor;
    private final ControlClientClock clock;
    private final Supplier<JsonObject> state;
    private final Consumer<JsonObject> save;
    private final Supplier<CandidateLeaseCodec.NativeOwner> owner;
    private final Runnable changed;
    private final Consumer<String> notice;
    final boolean enabled;
    private Live live;
    private boolean lossReported;
    private record Live(Installation document, DiagnosticAdmission.Installation handle, CandidateLeaseCodec.NativeOwner owner) { }

    /** A request claim can be released only once its original authenticated response is owned. */
    static final class Claim {
        private final Runnable current;
        private boolean delivered;
        Claim(Runnable current) { this.current = current; }
        void requireCurrent() { if (!delivered) current.run(); }
        void delivered() { requireCurrent(); delivered = true; }
    }
    record Prepared(ControlDiagnosticHeartbeatCodec.Request request, Claim claim) { }

    ControlledDiagnosticApplication(ControlledProviderState storage, ProviderTransport transport, Executor executor,
            ControlClientClock clock, Supplier<JsonObject> state, Consumer<JsonObject> save,
            Supplier<CandidateLeaseCodec.NativeOwner> owner, Runnable changed, Consumer<String> notice) {
        this.storage = storage; this.transport = transport; this.executor = executor; this.clock = clock;
        this.state = state; this.save = save; this.owner = owner; this.changed = changed; this.notice = notice;
        enabled = state.get().has("diagnosticAdmission");
        if (enabled && !transport.supportsDiagnosticAdmission()) throw new IllegalArgumentException("Diagnostic transport required");
    }
    Prepared prepare() {
        var captured = live;
        if (captured == null || !current(captured)) return new Prepared(new ControlDiagnosticHeartbeatCodec.Request(null), null);
        var claim = new Claim(() -> requireCurrent(captured));
        return new Prepared(new ControlDiagnosticHeartbeatCodec.Request(new Acknowledgement(captured.document.binding())), claim);
    }
    Claim retain(ControlDiagnosticHeartbeatCodec.Request request) {
        if (request.installed() == null) return null;
        var captured = live;
        if (!enabled || captured == null || !request.installed().binding().equals(captured.document.binding()) || !current(captured))
            throw new ControlClientIo.ReconciliationRequired("Retained diagnostic ACK has no original live installation");
        return new Claim(() -> requireCurrent(captured));
    }
    boolean lost() {
        if (live != null && !current(live) && !lossReported) { lossReported = true; return true; }
        return false;
    }
    private boolean current(Live captured) {
        try { requireCurrent(captured); return true; } catch (RuntimeException expired) { return false; }
    }
    private void requireCurrent(Live captured) {
        if (live != captured || !Objects.equals(owner.get(), captured.owner)) throw new IllegalStateException("Diagnostic owner changed");
        requireBinding(captured.document, captured.owner);
        captured.handle.requireCurrent();
        if (transport.captureDiagnosticInstallation().orElse(null) != captured.handle)
            throw new IllegalStateException("Diagnostic native installation changed");
    }
    CompletionStage<Void> apply(ControlDiagnosticHeartbeatCodec.Response response, Runnable guard,
            ProviderTransport.CandidateLeaseSnapshot candidates) {
        guard.run();
        var prior = live;
        // A player profile/key rebind only retires the full-install ACK capture. Preserve admitted
        // diagnostic sessions for the native atomic replacement, which retains their original bounds.
        if (prior != null && !Objects.equals(owner.get(), prior.owner)) {
            live = null;
            return transport.withdrawDiagnosticPolicy(prior.handle).thenComposeAsync(ignored -> {
                guard.run(); return applyCurrent(response, guard, candidates);
            }, executor);
        }
        return applyCurrent(response, guard, candidates);
    }
    private CompletionStage<Void> applyCurrent(ControlDiagnosticHeartbeatCodec.Response response, Runnable guard,
            ProviderTransport.CandidateLeaseSnapshot candidates) {
        guard.run();
        // Null is also the legitimate secret-free historical receipt representation.
        if (!enabled || response == null || response.expected() == null) return CompletableFuture.completedFuture(null);
        Installation document = response.expected();
        final CandidateLeaseCodec.NativeOwner capturedOwner = owner.get();
        try {
            verifyInstallation(document); requireBinding(document, capturedOwner); requireFloor(document);
            requireCandidateDeadlines(document, candidates);
        } catch (RuntimeException unavailable) { notice.accept("diagnostic_installation_unavailable"); return CompletableFuture.completedFuture(null); }
        if (live != null && live.document.equals(document) && current(live)) return CompletableFuture.completedFuture(null);
        Runnable current = () -> { guard.run(); requireBinding(document, capturedOwner); requireFloor(document); requireCandidateDeadlines(document, candidates); };
        DiagnosticAdmission.Policy policy = nativePolicy(document);
        CompletionStage<DiagnosticAdmission.Installation> installation;
        try { installation = transport.installDiagnosticPolicy(policy, current); }
        catch (RuntimeException unavailable) { installation = CompletableFuture.failedFuture(unavailable); }
        return installation.handleAsync((handle, failure) -> {
            if (failure == null) try {
                current.run(); handle.requireCurrent();
                if (!policy.binding().equals(handle.binding()) || transport.captureDiagnosticInstallation().orElse(null) != handle)
                    throw new IllegalStateException("Diagnostic installation capture mismatch");
                var next = state.get().deepCopy(); next.add("diagnosticInstallation", JsonParser.parseString(encodeInstallation(document)));
                save.accept(next);
                current.run(); handle.requireCurrent();
                if (transport.captureDiagnosticInstallation().orElse(null) != handle) throw new IllegalStateException("Diagnostic installation changed after save");
                live = new Live(document, handle, capturedOwner); lossReported = false; changed.run();
                return CompletableFuture.<Void>completedFuture(null);
            } catch (RuntimeException stale) { failure = stale; }
            // A late continuation cannot remove a successor owned by another operation.
            CompletionStage<Boolean> cleanup = handle == null ? CompletableFuture.completedFuture(false) : transport.withdrawDiagnosticPolicy(handle);
            return cleanup.handleAsync((withdrawn, cleanupFailure) -> {
                guard.run(); notice.accept("diagnostic_installation_unavailable"); return (Void) null;
            }, executor);
        }, executor).thenCompose(Function.identity());
    }
    private void requireBinding(Installation document, CandidateLeaseCodec.NativeOwner capturedOwner) {
        var binding = document.binding(); var subject = storage.initial.subject(); var data = state.get();
        if (capturedOwner == null || !Objects.equals(owner.get(), capturedOwner)
                || !binding.providerOrigin().equals(subject.audience()) || !binding.hostId().equals(subject.instanceId())
                || binding.generation() != subject.generation() || binding.nativeOwnerEpoch() != capturedOwner.epoch()
                || !binding.nativeIncarnation().equals(capturedOwner.nativeIncarnation())
                || !data.has("profile") || !data.has("profileRevision")
                || !binding.hostProfileRevision().equals(ControlledProviderJson.string(data, "profileRevision")))
            throw new IllegalStateException("Diagnostic installation owner or profile mismatch");
        var profile = CandidateLeaseCodec.readProfile(data.getAsJsonObject("profile"));
        if (!binding.hostProfileSha256().equals(CandidateLeaseCodec.profileDigest(profile))
                || !binding.nativeIncarnation().equals(profile.nativeIncarnation())
                || !binding.hostFingerprintHex().equals(profile.dtlsFingerprint().substring(8).replace(":", "").toLowerCase(Locale.ROOT))
                || clock.nowMillis() < document.notBefore() || clock.nowMillis() >= document.expiresAt()
                || document.endpoints().stream().anyMatch(endpoint -> clock.nowMillis() >= endpoint.expiresAt()))
            throw new IllegalStateException("Diagnostic installation profile or deadline mismatch");
    }
    private void requireFloor(Installation document) {
        var data = state.get(); if (!data.has("diagnosticInstallation")) return;
        var prior = decodeInstallation(data.get("diagnosticInstallation").toString());
        var before = prior.binding(); var after = document.binding();
        if (!before.authorityIncarnation().equals(after.authorityIncarnation()) || after.nativeOwnerEpoch() < before.nativeOwnerEpoch()
                || after.policyRevision() < before.policyRevision() || after.policyRevision() == before.policyRevision() && !prior.equals(document))
            throw new IllegalStateException("Diagnostic installation floor changed");
    }
    private void requireCandidateDeadlines(Installation document, ProviderTransport.CandidateLeaseSnapshot candidates) {
        for (Endpoint endpoint : document.endpoints()) if (endpoint.candidateType().equals("srflx")) {
            if (candidates == null) throw new IllegalStateException("Diagnostic reflexive endpoint has no original observation");
            candidates.requireCurrent();
            boolean covered = candidates.observations().stream().anyMatch(observation ->
                    observation.family().equals(endpoint.family() == 4 ? "ipv4" : "ipv6") && observation.port() == endpoint.port()
                    && (endpoint.family() == 4 ? "000000000000000000000000" + observation.addressHex() : observation.addressHex()).equals(endpoint.addressHex())
                    && endpoint.expiresAt() <= observation.expiresAt());
            if (!covered) throw new IllegalStateException("Diagnostic reflexive endpoint outlives original observation");
        }
    }
    static DiagnosticAdmission.Policy nativePolicy(Installation document) {
        var b = document.binding();
        var context = new DiagnosticAdmissionCodec.Context(b.providerOrigin(), b.hostId(), b.nativeIncarnation(), b.generation());
        var binding = new DiagnosticAdmission.Binding(context, b.authorityIncarnation(), b.nativeOwnerEpoch(), b.hostProfileRevision(),
                b.hostProfileSha256(), b.policyRevision(), b.installationSha256(), b.hostFingerprintHex());
        return new DiagnosticAdmission.Policy(binding, document.keys().stream().map(key ->
                new DiagnosticAdmissionCodec.Key(key.keyId(), key.secret(), key.notBefore(), key.retireAt())).toList(),
                document.endpoints().stream().map(endpoint -> new DiagnosticAdmission.Endpoint(new DiagnosticHostPolicy.Endpoint(
                        endpoint.family(), endpoint.addressHex(), endpoint.port(), endpoint.candidateRevision()), endpoint.candidateType(), endpoint.expiresAt())).toList(),
                document.notBefore(), document.expiresAt());
    }
}
