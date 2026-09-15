package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure bounded root-state transitions. Only ControlledProviderState may save the returned root. */
final class ControlledDiagnosticCompletionQueue {
    static final String FIELD = "controlDiagnosticCompletions";
    static final int CAPACITY = 32;
    private static final long MAX = 9007199254740991L;
    private static final List<String> COUNTERS = List.of("recorded", "unknown", "invalidNative", "nativeDropped", "unsettledBatches");

    static JsonObject empty() {
        var q = new JsonObject(); q.addProperty("version", 1); q.add("pending", new JsonArray());
        for (String key : COUNTERS) q.addProperty(key, 0);
        // No process can establish whether a predecessor lost an unpersisted native/RAM result.
        q.addProperty("priorProcessLossUnknown", true); return q;
    }
    static JsonObject queue(JsonObject root) { return root.has(FIELD) ? root.getAsJsonObject(FIELD).deepCopy() : empty(); }
    static void validate(JsonObject root, ControlClientJournal.Subject subject) {
        if (!root.has(FIELD)) return;
        if (!root.getAsJsonObject("controlApplication").has("diagnosticAdmission")) throw ControlledProviderJson.invalid();
        var q = queue(root); var fields = new HashSet<>(COUNTERS);
        fields.addAll(List.of("version", "pending", "priorProcessLossUnknown"));
        if (q.has("acknowledgement")) fields.add("acknowledgement");
        if (!q.keySet().equals(fields) || ControlledProviderJson.number(q, "version") != 1
                || !q.get("priorProcessLossUnknown").isJsonPrimitive() || !q.getAsJsonPrimitive("priorProcessLossUnknown").isBoolean()
                || !q.get("priorProcessLossUnknown").getAsBoolean()) throw ControlledProviderJson.invalid();
        for (String key : COUNTERS) ControlledProviderJson.number(q, key);
        var pending = q.getAsJsonArray("pending"); if (pending.size() > CAPACITY) throw ControlledProviderJson.invalid();
        var identities = new HashSet<List<Object>>();
        for (var value : pending) {
            var c = ControlDiagnosticCompletionCodec.decodeCompletion(value.toString()); scoped(c, subject);
            if (!identities.add(identity(c))) throw ControlledProviderJson.invalid();
        }
        if (q.has("acknowledgement")) {
            var m = q.getAsJsonObject("acknowledgement");
            if (!m.keySet().equals(Set.of("intentDigest", "receiptDigest", "batchDigest", "sequence", "settled"))) throw ControlledProviderJson.invalid();
            for (String key : List.of("intentDigest", "receiptDigest", "batchDigest")) {
                String digest = ControlledProviderJson.string(m, key);
                if (!ProviderCrypto.base64(Base64.getUrlDecoder().decode(digest)).equals(digest)
                        || Base64.getUrlDecoder().decode(digest).length != 32) throw ControlledProviderJson.invalid();
            }
            ControlledProviderJson.number(m, "sequence");
            if (!m.getAsJsonPrimitive("settled").isBoolean()) throw ControlledProviderJson.invalid();
        }
    }
    static void scoped(ControlDiagnosticCompletionCodec.Completion c, ControlClientJournal.Subject s) {
        if (!c.installation().providerOrigin().equals(s.audience()) || !c.installation().hostId().equals(s.instanceId())
                || c.installation().generation() > s.generation()) throw ControlledProviderJson.invalid();
    }
    static List<Object> identity(ControlDiagnosticCompletionCodec.Completion c) {
        var b = c.installation(); return List.of(b.providerOrigin(), b.hostId(), b.authorityIncarnation(), b.generation(),
                b.nativeOwnerEpoch(), b.nativeIncarnation(), c.attemptIdHex());
    }
    static ControlDiagnosticCompletionCodec.Batch batch(JsonObject root) {
        var pending = queue(root).getAsJsonArray("pending"); if (pending.isEmpty()) return null;
        var list = new ArrayList<ControlDiagnosticCompletionCodec.Completion>();
        for (int i = 0; i < Math.min(4, pending.size()); i++) list.add(ControlDiagnosticCompletionCodec.decodeCompletion(pending.get(i).toString()));
        return new ControlDiagnosticCompletionCodec.Batch(list);
    }
    static JsonObject append(JsonObject root, ControlClientJournal.Subject subject,
                             List<ControlDiagnosticCompletionCodec.Completion> completions, long invalid, long dropped) throws IOException {
        if (completions.size() > 4 || invalid < 0 || invalid > 4 || completions.size() + invalid > 4 || dropped < 0) throw new IOException("Diagnostic drain bound");
        var q = queue(root); var pending = q.getAsJsonArray("pending");
        for (var c : completions) {
            scoped(c, subject); String wire = ControlDiagnosticCompletionCodec.encodeCompletion(c);
            ControlDiagnosticCompletionCodec.Completion duplicate = null;
            for (var value : pending) {
                var prior = ControlDiagnosticCompletionCodec.decodeCompletion(value.toString());
                if (identity(prior).equals(identity(c))) { duplicate = prior; break; }
            }
            if (duplicate != null) { if (!duplicate.equals(c)) increment(q, "invalidNative", 1); continue; }
            if (pending.size() >= CAPACITY) throw new IOException("Diagnostic queue full");
            pending.add(JsonParser.parseString(wire));
        }
        increment(q, "invalidNative", invalid); increment(q, "nativeDropped", dropped);
        var next = root.deepCopy(); next.add(FIELD, q); return next;
    }
    static JsonObject settle(JsonObject root, ControlClientJournal.Subject subject, ControlLifecycleCodec.Intent intent,
                             byte[] original, ControlLifecycleCodec.Receipt receipt,
                             ControlDiagnosticCompletionReceiptCodec.Batch receipts) throws IOException {
        ControlLifecycleCodec.verifyReceipt(receipt, intent); ControlLifecycleCodec.verifyBody(intent, original);
        if (!intent.operation().equals("heartbeat") || !receipt.disposition().equals("committed")
                || !intent.audience().equals(subject.audience()) || !intent.instanceId().equals(subject.instanceId())
                || intent.generation() != subject.generation()) throw new IOException("Unowned diagnostic acknowledgement");
        var submitted = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(new String(original, StandardCharsets.UTF_8));
        if (submitted == null) throw new IOException("Missing original diagnostic batch");
        var q = queue(root); var marker = new JsonObject();
        marker.addProperty("intentDigest", ControlLifecycleCodec.intentDigest(intent));
        marker.addProperty("receiptDigest", ControlFrameCodec.payloadDigest(ControlLifecycleCodec.encodeReceipt(receipt).getBytes(StandardCharsets.UTF_8)));
        marker.addProperty("batchDigest", intent.payloadSha256()); marker.addProperty("sequence", intent.sequence());
        var prior = q.getAsJsonObject("acknowledgement");
        if (prior != null && ControlledProviderJson.number(prior, "sequence") >= intent.sequence()) {
            var same = prior.deepCopy(); same.remove("settled");
            if (!same.equals(marker)) throw new IOException("Diagnostic acknowledgement rollback or mismatch");
            if (prior.get("settled").getAsBoolean() || receipts == null) return root;
        }
        var pending = q.getAsJsonArray("pending");
        if (pending.size() < submitted.completions().size()) throw new IOException("Diagnostic queue prefix missing");
        for (int i = 0; i < submitted.completions().size(); i++) {
            var c = submitted.completions().get(i); scoped(c, subject);
            if (!c.equals(ControlDiagnosticCompletionCodec.decodeCompletion(pending.get(i).toString()))) throw new IOException("Diagnostic queue prefix changed");
        }
        boolean settled = matches(submitted, receipts, receipt.committedAt());
        if (settled) {
            for (var r : receipts.receipts()) increment(q, r.disposition().equals("recorded") ? "recorded" : "unknown", 1);
            for (int i = 0; i < submitted.completions().size(); i++) pending.remove(0);
        } else if (prior == null || ControlledProviderJson.number(prior, "sequence") != intent.sequence()) increment(q, "unsettledBatches", 1);
        marker.addProperty("settled", settled); q.add("acknowledgement", marker);
        var next = root.deepCopy(); next.add(FIELD, q); return next;
    }
    private static boolean matches(ControlDiagnosticCompletionCodec.Batch submitted, ControlDiagnosticCompletionReceiptCodec.Batch receipts, long committedAt) {
        if (receipts == null || receipts.receipts().size() != submitted.completions().size()) return false;
        for (var c : submitted.completions()) {
            var matched = receipts.receipts().stream().filter(r -> r.installation().equals(c.installation()) && r.attemptIdHex().equals(c.attemptIdHex())
                    && r.completionDigest().equals(ControlDiagnosticCompletionCodec.completionDigest(c))).findFirst().orElse(null);
            if (matched == null || matched.receivedAt() > committedAt) return false;
            if (matched.disposition().equals("recorded") && (matched.receivedAt() < c.completedAt()
                    || matched.receivedAt() > c.expiresAt() + ControlDiagnosticCompletionCodec.GRACE_MILLIS)) return false;
        }
        return true;
    }
    private static void increment(JsonObject q, String key, long n) {
        long before = ControlledProviderJson.number(q, key); q.addProperty(key, before + Math.min(n, MAX - before));
    }
}
