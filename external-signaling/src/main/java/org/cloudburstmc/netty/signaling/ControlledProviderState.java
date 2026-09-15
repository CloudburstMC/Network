package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/** Application storage under the existing root lock; the child journal alone owns machine credentials and sequence. */
final class ControlledProviderState implements AutoCloseable {
    static final String MODE = "nethernet-control-v1";
    interface Save { void write(JsonObject value) throws IOException; }
    private final Save save;
    private JsonObject root;
    final FileControlClientJournal journal;
    final ControlClientJournal.Snapshot initial;

    static boolean hasMarker(JsonObject root) { return root.has("controlMode"); }
    static ControlledProviderState open(ProviderStateStore store, ProviderControlConfiguration config) throws IOException {
        return open(store, config, store::write);
    }
    static ControlledProviderState open(ProviderStateStore store, ProviderControlConfiguration config, Save save) throws IOException {
        var root = store.readControlled();
        if (!root.has("registration") || !config.routes().audience().equals(ControlledProviderJson.string(root, "provider")))
            throw new IOException("Controlled mode requires an existing reconciled registration");
        var registration = root.getAsJsonObject("registration");
        long generation = ControlledProviderJson.number(registration, "leaseGeneration");
        var subject = new ControlClientJournal.Subject(config.routes().audience(), ControlledProviderJson.string(registration, "instanceId"), generation);
        if (!subject.audience().equals(ControlledProviderJson.string(registration, "provider"))
                || !"nxs-admission-v1".equals(ControlledProviderJson.string(registration, "profile"))) throw new IOException("Controlled registration binding mismatch");
        var directory = store.directory().resolve("control-session");
        if (hasMarker(root) && (!MODE.equals(ControlledProviderJson.string(root, "controlMode"))
                || !Files.exists(directory.resolve("provider-state.json")))) throw new IOException("Controlled journal missing or unsupported");
        var journal = new FileControlClientJournal(directory);
        try {
            var retained = journal.read().orElse(null);
            if (hasMarker(root)) {
                if (retained == null || !retained.subject().equals(subject) || root.has("privateKey") || root.has("sequence")
                        || registration.has("keyId")) throw new IOException("Conflicting controlled identity storage");
                validate(root.getAsJsonObject("controlApplication"), generation);
                return new ControlledProviderState(save, ownerMode(save, root, config), journal, retained);
            }
            if (config.migrationSeed().generation() != generation || root.has("pendingPrivateKey") || root.has("pendingPublicKeyJwk"))
                throw new IOException("Legacy state needs explicit reconciliation before controlled migration");
            var jwk = root.getAsJsonObject("publicKeyJwk");
            // Normalize only public fields; Credential verifies that this material matches the stored private key.
            var publicJwk = new JsonObject();
            for (String name : List.of("crv", "kty", "x", "y")) publicJwk.addProperty(name, ControlledProviderJson.string(jwk, name));
            var key = new ControlClientJournal.Credential(ControlledProviderJson.string(registration, "keyId"), publicJwk.toString(),
                    ControlledProviderJson.string(root, "privateKey"));
            long sequence = ControlledProviderJson.number(root, "sequence");
            var imported = new ControlClientJournal.Snapshot(subject, key, new ControlWriterFence("legacy-http", 0, "", "", key.keyId(), 1), sequence, null, null, null);
            if (retained != null && !retained.equals(imported)) throw new IOException("Interrupted control migration is not a pristine matching import");
            if (retained == null) journal.commit(imported);
            var application = new JsonObject(); application.addProperty("version", 1); application.addProperty("generation", generation);
            application.addProperty("appliedRevision", config.migrationSeed().appliedRevision());
            application.addProperty("reportedState", config.migrationSeed().reportedState());
            if (config.nativeOwnership() == ProviderControlConfiguration.NativeOwnership.ISSUED)
                application.addProperty("nativeOwnership", ControlledNativeOwner.MODE);
            if (config.candidatePublication() == ProviderControlConfiguration.CandidatePublication.MAINTAINED)
                application.addProperty("candidatePublication", "maintained-v1");
            if (config.diagnostics() == ProviderControlConfiguration.Diagnostics.ENABLED)
                application.addProperty("diagnosticAdmission", "install-v1");
            var keys = new JsonArray();
            if (root.has("ticketKeys")) for (var value : root.getAsJsonArray("ticketKeys")) {
                var previous = value.getAsJsonObject(); var item = new JsonObject();
                item.addProperty("keyId", ControlledProviderJson.string(previous, "keyId"));
                item.addProperty("secret", ControlledProviderJson.string(previous, "secret")); item.addProperty("notBefore", 0);
                if (previous.has("notBefore") && ControlledProviderJson.number(previous, "notBefore") != 0) throw new IOException("Unsupported legacy epoch start");
                if (previous.has("retireAfter") && !previous.get("retireAfter").getAsString().equals(Long.toString(Long.MAX_VALUE)))
                    item.addProperty("acceptUntil", ControlledProviderJson.number(previous, "retireAfter"));
                keys.add(item);
            }
            application.add("keys", keys);
            if (root.has("keyRequestId")) application.addProperty("keyRequestId", ControlledProviderJson.string(root, "keyRequestId"));
            validate(application, generation);
            root = root.deepCopy(); root.addProperty("controlMode", MODE); root.add("controlApplication", application);
            for (String name : List.of("privateKey", "publicKeyJwk", "pendingPrivateKey", "pendingPublicKeyJwk", "sequence", "ticketKeys", "keyRequestId")) root.remove(name);
            root.getAsJsonObject("registration").remove("keyId"); root.getAsJsonObject("registration").remove("ticketKey");
            save.write(root.deepCopy());
            return new ControlledProviderState(save, root, journal, imported);
        } catch (Throwable failure) {
            journal.close(); if (failure instanceof IOException error) throw error;
            throw new IOException("Controlled state could not be opened", failure);
        }
    }
    private ControlledProviderState(Save save, JsonObject root, FileControlClientJournal journal, ControlClientJournal.Snapshot initial) {
        ControlledDiagnosticCompletionQueue.validate(root, initial.subject());
        this.save = save; this.root = root.deepCopy(); this.journal = journal; this.initial = initial;
    }
    private static JsonObject ownerMode(Save save, JsonObject root, ProviderControlConfiguration config) throws IOException {
        var application = root.getAsJsonObject("controlApplication");
        boolean retained = application.has("nativeOwnership"), enabled = config.nativeOwnership() == ProviderControlConfiguration.NativeOwnership.ISSUED;
        if (retained && !enabled) throw new IOException("Persisted issued native ownership requires its explicit configuration");
        if (enabled && !retained) {
            root = root.deepCopy(); root.getAsJsonObject("controlApplication").addProperty("nativeOwnership", ControlledNativeOwner.MODE);
            save.write(root.deepCopy());
        }
        boolean maintained = config.candidatePublication() == ProviderControlConfiguration.CandidatePublication.MAINTAINED;
        boolean retainedPublication = root.getAsJsonObject("controlApplication").has("candidatePublication");
        if (retainedPublication && !maintained) throw new IOException("Persisted maintained publication requires its explicit configuration");
        if (maintained && !retainedPublication) {
            root = root.deepCopy(); root.getAsJsonObject("controlApplication").addProperty("candidatePublication", "maintained-v1");
            save.write(root.deepCopy());
        }
        boolean diagnostics = config.diagnostics() == ProviderControlConfiguration.Diagnostics.ENABLED;
        boolean retainedDiagnostics = root.getAsJsonObject("controlApplication").has("diagnosticAdmission");
        if (retainedDiagnostics && !diagnostics) throw new IOException("Persisted diagnostic installation requires its explicit configuration");
        if (diagnostics && !retainedDiagnostics) {
            root = root.deepCopy(); root.getAsJsonObject("controlApplication").addProperty("diagnosticAdmission", "install-v1");
            save.write(root.deepCopy());
        }
        return root;
    }
    JsonObject application() { return root.getAsJsonObject("controlApplication").deepCopy(); }
    void saveApplication(JsonObject application) throws IOException {
        application = ControlledProviderJson.parse(application.toString(), 65536); validate(application, initial.subject().generation());
        var old = root.getAsJsonObject("controlApplication");
        if (ControlledProviderJson.number(application, "appliedRevision") < ControlledProviderJson.number(old, "appliedRevision")) throw new IOException("Application revision rollback");
        if (old.has("nativeOwnership") && !old.get("nativeOwnership").equals(application.get("nativeOwnership"))) throw new IOException("Native ownership mode rollback");
        if (old.has("candidatePublication") && !old.get("candidatePublication").equals(application.get("candidatePublication"))) throw new IOException("Candidate publication mode rollback");
        if (old.has("diagnosticAdmission") && !old.get("diagnosticAdmission").equals(application.get("diagnosticAdmission"))) throw new IOException("Diagnostic installation mode rollback");
        if (old.has("diagnosticInstallation")) {
            if (!application.has("diagnosticInstallation")) throw new IOException("Diagnostic installation floor removed");
            var prior = ControlDiagnosticInstallationCodec.decodeInstallation(old.get("diagnosticInstallation").toString());
            var next = ControlDiagnosticInstallationCodec.decodeInstallation(application.get("diagnosticInstallation").toString());
            var before = prior.binding(); var after = next.binding();
            if (!before.providerOrigin().equals(after.providerOrigin()) || !before.hostId().equals(after.hostId())
                    || !before.authorityIncarnation().equals(after.authorityIncarnation()) || after.generation() != before.generation()
                    || after.nativeOwnerEpoch() < before.nativeOwnerEpoch() || after.policyRevision() < before.policyRevision()
                    || after.policyRevision() == before.policyRevision() && !prior.equals(next))
                throw new IOException("Diagnostic installation rollback or revision conflict");
        }
        if (old.has("nativeOwnerReceipt")) {
            var prior = old.getAsJsonObject("nativeOwnerReceipt"); var nextReceipt = application.getAsJsonObject("nativeOwnerReceipt");
            if (nextReceipt == null || ControlledProviderJson.number(nextReceipt, "sequence") < ControlledProviderJson.number(prior, "sequence")
                    || ControlledProviderJson.number(nextReceipt, "sequence") == ControlledProviderJson.number(prior, "sequence") && !nextReceipt.equals(prior))
                throw new IOException("Native ownership receipt rollback");
        }
        var next = root.deepCopy(); next.add("controlApplication", application); writeRoot(next);
    }
    void acknowledgeNativeOwner(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) throws IOException {
        if (!intent.audience().equals(initial.subject().audience()) || !intent.instanceId().equals(initial.subject().instanceId())
                || intent.generation() != initial.subject().generation()) throw new IOException("Unowned native adoption receipt");
        var marker = ControlledNativeOwner.marker(intent, originalBody, receipt);
        var application = application();
        if (!application.has("nativeOwnership")) throw new IOException("Native ownership not opted in");
        if (marker.equals(application.get("nativeOwnerReceipt"))) return;
        application.add("nativeOwnerReceipt", marker); saveApplication(application);
    }
    JsonObject diagnosticCompletionState() { return ControlledDiagnosticCompletionQueue.queue(root); }
    int diagnosticCompletionCapacity() {
        int count = diagnosticCompletionState().getAsJsonArray("pending").size();
        int bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return Math.max(0, Math.min(4, Math.min(32 - count, (262144 - 32768 - bytes) / 4097)));
    }
    ControlDiagnosticCompletionCodec.Batch diagnosticCompletionBatch() { return ControlledDiagnosticCompletionQueue.batch(root); }
    void appendDiagnosticCompletions(List<ControlDiagnosticCompletionCodec.Completion> completions, long invalid, long dropped) throws IOException {
        if (!application().has("diagnosticAdmission")) throw new IOException("Diagnostic reporting not opted in");
        var next = ControlledDiagnosticCompletionQueue.append(root, initial.subject(), completions, invalid, dropped);
        if (!next.equals(root)) writeRoot(next);
    }
    void acknowledgeDiagnosticCompletions(ControlLifecycleCodec.Intent intent, byte[] original, ControlLifecycleCodec.Receipt receipt,
                                          ControlDiagnosticCompletionReceiptCodec.Batch receipts) throws IOException {
        if (!application().has("diagnosticAdmission")) throw new IOException("Diagnostic reporting not opted in");
        var next = ControlledDiagnosticCompletionQueue.settle(root, initial.subject(), intent, original, receipt, receipts);
        if (!next.equals(root)) writeRoot(next);
    }
    int eventCapacity() {
        int count = root.has("pendingEvents") ? root.getAsJsonArray("pendingEvents").size() : 0;
        int bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // Each sanitized event is at most 1024 bytes plus its comma. Reserve metadata growth/ACK marker space.
        int reserve = root.getAsJsonObject("controlApplication").has("diagnosticAdmission") ? 176128 : 4096;
        return Math.max(0, Math.min(256, Math.min(1000 - count, (262144 - reserve - bytes) / 1025)));
    }
    void appendEvents(List<JsonObject> events) throws IOException {
        if (events.size() > 256) throw new IOException("Native outcome batch exceeds limit");
        if (events.isEmpty()) return;
        var next = root.deepCopy(); var pending = next.has("pendingEvents") ? next.getAsJsonArray("pendingEvents") : new JsonArray();
        if (pending.size() + events.size() > 1000) throw new IOException("Controlled outcome queue exceeds limit");
        for (var event : events) {
            var safe = new JsonObject();
            for (String name : List.of("stage", "ticketId", "occurredAt", "reason")) if (event.has(name) && !event.get(name).isJsonNull()) safe.addProperty(name, ControlledProviderJson.string(event, name));
            if (!safe.has("stage") || !safe.has("ticketId") || !safe.has("occurredAt")) throw new IOException("Invalid native outcome");
            pending.add(ControlledProviderJson.parse(safe.toString(), 1024));
        }
        next.add("pendingEvents", pending); writeRoot(next);
    }
    JsonObject outcomeBatch() {
        var body = new JsonObject(); var batch = new JsonArray();
        if (root.has("pendingEvents")) {
            var pending = root.getAsJsonArray("pendingEvents");
            int bytes = 16;
            for (int index = 0; index < Math.min(100, pending.size()); index++) {
                int size = pending.get(index).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                if (bytes + size > ControlLifecycleCodec.MAX_WS_BODY_BYTES) break;
                batch.add(pending.get(index).deepCopy()); bytes += size;
            }
        }
        body.add("events", batch); return body;
    }
    void acknowledgeOutcomes(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) throws IOException {
        ControlLifecycleCodec.verifyReceipt(receipt, intent);
        if (!intent.operation().equals("outcomes") || !receipt.disposition().equals("committed")
                || !intent.audience().equals(initial.subject().audience()) || !intent.instanceId().equals(initial.subject().instanceId())
                || intent.generation() != initial.subject().generation() || !intent.payloadSha256().equals(ControlFrameCodec.payloadDigest(originalBody))) throw new IOException("Unowned outcome acknowledgement");
        var marker = new JsonObject(); marker.addProperty("intentDigest", ControlLifecycleCodec.intentDigest(intent));
        marker.addProperty("receiptDigest", ControlFrameCodec.payloadDigest(ControlLifecycleCodec.encodeReceipt(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        marker.addProperty("batchDigest", intent.payloadSha256()); marker.addProperty("sequence", intent.sequence());
        var prior = root.getAsJsonObject("controlOutcomeAcknowledgement");
        if (prior != null) {
            if (prior.equals(marker)) return;
            if (ControlledProviderJson.number(prior, "sequence") >= intent.sequence()) throw new IOException("Outcome acknowledgement rollback or mismatch");
        }
        var body = ControlledProviderJson.parse(new String(originalBody, java.nio.charset.StandardCharsets.UTF_8), ControlLifecycleCodec.MAX_HTTP_BODY_BYTES);
        var expected = body.getAsJsonArray("events"); var next = root.deepCopy(); var pending = next.getAsJsonArray("pendingEvents");
        if (expected == null || expected.isEmpty() || expected.size() > 100 || pending == null || pending.size() < expected.size()) throw new IOException("Controlled outcome queue changed");
        for (int index = 0; index < expected.size(); index++) if (!pending.get(index).equals(expected.get(index))) throw new IOException("Controlled outcome batch changed");
        for (int index = 0; index < expected.size(); index++) pending.remove(0);
        next.add("controlOutcomeAcknowledgement", marker); writeRoot(next);
    }
    private void writeRoot(JsonObject next) throws IOException {
        if (next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) throw new IOException("Controlled storage exceeds limit");
        save.write(next.deepCopy()); root = next;
    }
    JsonObject registration(ControlClientJournal.Snapshot current) {
        if (!initial.subject().equals(current.subject())) throw new IllegalStateException("Controlled subject changed");
        var value = root.getAsJsonObject("registration").deepCopy(); value.addProperty("keyId", current.currentKey().keyId()); return value;
    }
    private static void validate(JsonObject application, long generation) {
        if (application == null || ControlledProviderJson.number(application, "version") != 1
                || ControlledProviderJson.number(application, "generation") != generation
                || !Set.of("serving", "draining", "closed").contains(ControlledProviderJson.string(application, "reportedState"))) throw ControlledProviderJson.invalid();
        ControlledProviderJson.number(application, "appliedRevision");
        var keys = application.getAsJsonArray("keys"); if (keys == null || keys.size() > 8) throw ControlledProviderJson.invalid();
        var names = new HashSet<String>();
        for (var value : keys) {
            var item = value.getAsJsonObject(); String id = ControlledProviderJson.string(item, "keyId"), secret = ControlledProviderJson.string(item, "secret");
            if (!id.matches("[A-Z0-9]{4}") || !names.add(id) || secret.length() < 32 || secret.length() > 256 || secret.indexOf(0) >= 0
                    || ControlledProviderJson.number(item, "notBefore") != 0) throw ControlledProviderJson.invalid();
            if (item.has("acceptUntil")) ControlledProviderJson.number(item, "acceptUntil");
        }
        if (application.has("keyRequestId") && !ControlledProviderJson.string(application, "keyRequestId").matches("[A-Za-z0-9_-]{16,128}")) throw ControlledProviderJson.invalid();
        if (application.has("basis")) {
            var basis = ControlStateCodec.decodeAppliedBasis(ControlledProviderJson.string(application, "basis"));
            if (basis.generation() != generation) throw ControlledProviderJson.invalid();
        }
        if (application.has("policy")) ControlStateCodec.decodeTicketPolicy(ControlledProviderJson.string(application, "policy"));
        if (application.has("nativeOwnership") && !ControlledNativeOwner.MODE.equals(ControlledProviderJson.string(application, "nativeOwnership"))) throw ControlledProviderJson.invalid();
        if (application.has("candidatePublication") && (!application.has("nativeOwnership") || !"maintained-v1".equals(ControlledProviderJson.string(application, "candidatePublication")))) throw ControlledProviderJson.invalid();
        if (application.has("diagnosticAdmission") && (!application.has("nativeOwnership") || !"install-v1".equals(ControlledProviderJson.string(application, "diagnosticAdmission")))) throw ControlledProviderJson.invalid();
        if (application.has("diagnosticInstallation")) {
            var document = ControlDiagnosticInstallationCodec.verifyInstallation(ControlDiagnosticInstallationCodec.decodeInstallation(application.get("diagnosticInstallation").toString()));
            if (!application.has("diagnosticAdmission") || document.binding().generation() != generation) throw ControlledProviderJson.invalid();
        }
        if (application.has("candidateLeaseReceipt")) {
            if (!application.has("candidatePublication")) throw ControlledProviderJson.invalid();
            var receipt = CandidateLeaseCodec.decodeReceipt(application.get("candidateLeaseReceipt").toString());
            if (!application.has("profile") || !application.has("profileRevision")
                    || !receipt.hostProfileRevision().equals(ControlledProviderJson.string(application, "profileRevision"))
                    || !receipt.profileSha256().equals(CandidateLeaseCodec.profileDigest(CandidateLeaseCodec.readProfile(application.getAsJsonObject("profile")))))
                throw ControlledProviderJson.invalid();
        }
        if (application.has("nativeOwnerReceipt")) {
            if (!application.has("nativeOwnership")) throw ControlledProviderJson.invalid();
            ControlledNativeOwner.validateMarker(application.getAsJsonObject("nativeOwnerReceipt"));
        }
    }
    @Override public void close() throws IOException { journal.close(); }
}
