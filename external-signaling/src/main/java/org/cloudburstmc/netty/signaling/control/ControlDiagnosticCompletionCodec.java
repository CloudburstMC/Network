package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded immutable host observations. Decoding/digests establish neither authentication nor current authority. */
public final class ControlDiagnosticCompletionCodec {
    public static final int MAX_COMPLETION_BYTES = 4096, MAX_BATCH_BYTES = 16_384, MAX_COMPLETIONS = 4;
    public static final long GRACE_MILLIS = 300_000;
    public static final String DOMAIN = "nethernet-control-diagnostic-completion-v1";
    public static final Set<String> REASONS = Set.of("complete", "incomplete", "authority_changed", "native_acceptance_failed",
            "expired_or_withdrawn", "invalid_diagnostic_protocol", "transport_failed", "handshake_timeout", "invalid_channels",
            "invalid_authentication", "invalid_exchange", "invalid_selected_path", "native_stats_unavailable", "native_cleanup_failed",
            "completion_invalidated", "host_closed");

    public record SelectedTuple(int family, String addressHex, int port) {
        public SelectedTuple {
            hex(addressHex, 16);
            if (family != 4 && family != 6 || port < 1 || port > 65535
                    || family == 4 && !addressHex.startsWith("000000000000000000000000")
                    || family == 6 && addressHex.startsWith("00000000000000000000ffff")) throw invalid("tuple");
        }
    }
    public record Target(int family, String addressHex, int port, long candidateRevision) {
        public Target { new SelectedTuple(family, addressHex, port); ControlJson.safe(candidateRevision, true); }
    }
    public record Frames(int sent, int sentBytes, int received, int receivedBytes) {
        public Frames { if (sent < 0 || sentBytes < 0 || received < 0 || receivedBytes < 0) throw invalid("frames"); }
    }
    public record Udp(long reserved, long sent, long sentBytes, long rejected) {
        public Udp { ControlJson.safe(reserved, false); ControlJson.safe(sent, false); ControlJson.safe(sentBytes, false); ControlJson.safe(rejected, false); }
    }
    public record Completion(ControlDiagnosticInstallationCodec.Binding installation, String keyId,
                             String attemptIdHex, String offerDigestHex, String clientFingerprintHex,
                             long expiresAt, Target target, boolean success, String reason, long completedAt,
                             boolean cleanupComplete, String completionDigestHex, SelectedTuple selectedLocal,
                             SelectedTuple selectedRemote, Frames frames, Udp udp) {
        public Completion {
            Objects.requireNonNull(installation); Objects.requireNonNull(target); Objects.requireNonNull(frames);
            if (keyId == null || !keyId.matches("[A-Z0-9]{4}")) throw invalid("key");
            hex(attemptIdHex, 16); hex(offerDigestHex, 32); hex(clientFingerprintHex, 32);
            ControlJson.safe(expiresAt, true); ControlJson.safe(completedAt, true);
            if (expiresAt > 0xffffffffL * 1000 || expiresAt % 1000 != 0 || completedAt > expiresAt + GRACE_MILLIS
                    || reason == null || !REASONS.contains(reason)) throw invalid("time or reason");
            if (completionDigestHex != null) hex(completionDigestHex, 32);
            if (selectedLocal != null && selectedLocal.family() != target.family()
                    || selectedRemote != null && selectedRemote.family() != target.family()) throw invalid("family");
            if (success) {
                if (!"complete".equals(reason) || !cleanupComplete || completedAt >= expiresAt || completionDigestHex == null
                        || selectedLocal == null || selectedRemote == null || frames.sent() < 5 || frames.sent() > 12
                        || frames.sentBytes() < 280 || frames.sentBytes() > 1024 || frames.received() < 6 || frames.received() > 12
                        || frames.receivedBytes() < 497 || frames.receivedBytes() > 1024
                        || udp == null || udp.sent() < 1 || udp.sent() > udp.reserved() || udp.reserved() > 256
                        || udp.rejected() != 0 || udp.sentBytes() < udp.sent() || udp.sentBytes() > udp.sent() * 1200)
                    throw invalid("success");
            } else if (completionDigestHex != null || "complete".equals(reason)) throw invalid("failure");
        }
    }
    public record Batch(List<Completion> completions) {
        public Batch {
            if (completions == null || completions.isEmpty() || completions.size() > MAX_COMPLETIONS) throw invalid("batch size");
            completions = List.copyOf(completions);
            Set<List<Object>> seen = new HashSet<>();
            for (Completion c : completions) {
                var b = c.installation();
                if (!seen.add(List.of(b.providerOrigin(), b.hostId(), b.authorityIncarnation(), b.generation(),
                        b.nativeOwnerEpoch(), b.nativeIncarnation(), c.attemptIdHex()))) throw invalid("duplicate attempt");
            }
        }
    }
    private ControlDiagnosticCompletionCodec() { }

    public static Completion decodeCompletion(String wire) { return readCompletion(ControlJson.parse(wire, MAX_COMPLETION_BYTES)); }
    private static Completion readCompletion(JsonObject o) {
        ControlJson.fields(o, "version", "installation", "keyId", "attemptIdHex", "offerDigestHex", "clientFingerprintHex", "expiresAt", "target",
                "success", "reason", "completedAt", "cleanupComplete", "completionDigestHex", "selectedLocal", "selectedRemote", "frames", "udp");
        ControlJson.version(o);
        JsonObject ack = new JsonObject(); ack.addProperty("version", 1); ack.add("binding", ControlJson.object(o, "installation"));
        var binding = ControlDiagnosticInstallationCodec.decodeAcknowledgement(ack.toString()).binding();
        JsonObject t = ControlJson.object(o, "target"); ControlJson.fields(t, "family", "addressHex", "port", "candidateRevision");
        var target = new Target(integer(t, "family"), ControlJson.string(t, "addressHex"), integer(t, "port"), ControlJson.number(t, "candidateRevision"));
        JsonObject f = ControlJson.object(o, "frames"); ControlJson.fields(f, "sent", "sentBytes", "received", "receivedBytes");
        var frames = new Frames(integer(f, "sent"), integer(f, "sentBytes"), integer(f, "received"), integer(f, "receivedBytes"));
        Udp udp = null;
        if (!o.get("udp").isJsonNull()) {
            JsonObject u = ControlJson.object(o, "udp"); ControlJson.fields(u, "reserved", "sent", "sentBytes", "rejected");
            udp = new Udp(ControlJson.number(u, "reserved"), ControlJson.number(u, "sent"), ControlJson.number(u, "sentBytes"), ControlJson.number(u, "rejected"));
        }
        return new Completion(binding, ControlJson.string(o, "keyId"), ControlJson.string(o, "attemptIdHex"), ControlJson.string(o, "offerDigestHex"),
                ControlJson.string(o, "clientFingerprintHex"), ControlJson.number(o, "expiresAt"), target, bool(o, "success"), ControlJson.string(o, "reason"),
                ControlJson.number(o, "completedAt"), bool(o, "cleanupComplete"), nullableString(o, "completionDigestHex"),
                readTuple(o, "selectedLocal"), readTuple(o, "selectedRemote"), frames, udp);
    }
    private static SelectedTuple readTuple(JsonObject o, String field) {
        if (o.get(field).isJsonNull()) return null;
        JsonObject t = ControlJson.object(o, field); ControlJson.fields(t, "family", "addressHex", "port");
        return new SelectedTuple(integer(t, "family"), ControlJson.string(t, "addressHex"), integer(t, "port"));
    }
    public static String encodeCompletion(Completion c) {
        Objects.requireNonNull(c);
        JsonObject o = new JsonObject(); o.addProperty("version", 1);
        o.add("installation", JsonParser.parseString(ControlDiagnosticInstallationCodec.encodeAcknowledgement(
                new ControlDiagnosticInstallationCodec.Acknowledgement(c.installation()))).getAsJsonObject().get("binding"));
        o.addProperty("keyId", c.keyId()); o.addProperty("attemptIdHex", c.attemptIdHex()); o.addProperty("offerDigestHex", c.offerDigestHex());
        o.addProperty("clientFingerprintHex", c.clientFingerprintHex()); o.addProperty("expiresAt", c.expiresAt());
        Target t = c.target(); JsonObject target = tuple(new SelectedTuple(t.family(), t.addressHex(), t.port()));
        target.addProperty("candidateRevision", t.candidateRevision()); o.add("target", target);
        o.addProperty("success", c.success()); o.addProperty("reason", c.reason()); o.addProperty("completedAt", c.completedAt());
        o.addProperty("cleanupComplete", c.cleanupComplete()); o.addProperty("completionDigestHex", c.completionDigestHex());
        o.add("selectedLocal", c.selectedLocal() == null ? JsonNull.INSTANCE : tuple(c.selectedLocal()));
        o.add("selectedRemote", c.selectedRemote() == null ? JsonNull.INSTANCE : tuple(c.selectedRemote()));
        JsonObject f = new JsonObject(); f.addProperty("sent", c.frames().sent()); f.addProperty("sentBytes", c.frames().sentBytes());
        f.addProperty("received", c.frames().received()); f.addProperty("receivedBytes", c.frames().receivedBytes()); o.add("frames", f);
        JsonObject u = null;
        if (c.udp() != null) { u = new JsonObject(); u.addProperty("reserved", c.udp().reserved()); u.addProperty("sent", c.udp().sent());
            u.addProperty("sentBytes", c.udp().sentBytes()); u.addProperty("rejected", c.udp().rejected()); }
        o.add("udp", u); return bounded(o.toString(), MAX_COMPLETION_BYTES);
    }
    public static byte[] completionPreimage(Completion c) {
        return ("[\"" + DOMAIN + "\"," + encodeCompletion(c) + "]").getBytes(StandardCharsets.UTF_8);
    }
    /** Identity only; not a signature, receipt or permission to probe. */
    public static String completionDigest(Completion c) { return ControlFrameCodec.payloadDigest(completionPreimage(c)); }
    public static Batch decodeBatch(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_BATCH_BYTES); ControlJson.fields(o, "version", "completions"); ControlJson.version(o);
        if (!o.get("completions").isJsonArray() || o.getAsJsonArray("completions").size() > MAX_COMPLETIONS) throw invalid("batch");
        var entries = new ArrayList<Completion>();
        for (var entry : o.getAsJsonArray("completions")) {
            if (!entry.isJsonObject()) throw invalid("completion");
            entries.add(decodeCompletion(entry.toString()));
        }
        return new Batch(entries);
    }
    public static String encodeBatch(Batch batch) {
        Objects.requireNonNull(batch); JsonObject o = new JsonObject(); o.addProperty("version", 1); JsonArray list = new JsonArray();
        for (Completion c : batch.completions()) list.add(JsonParser.parseString(encodeCompletion(c)));
        o.add("completions", list); return bounded(o.toString(), MAX_BATCH_BYTES);
    }
    private static JsonObject tuple(SelectedTuple t) {
        JsonObject o = new JsonObject(); o.addProperty("family", t.family()); o.addProperty("addressHex", t.addressHex()); o.addProperty("port", t.port()); return o;
    }
    private static boolean bool(JsonObject o, String field) {
        if (!o.get(field).isJsonPrimitive() || !o.getAsJsonPrimitive(field).isBoolean()) throw invalid(field);
        return o.get(field).getAsBoolean();
    }
    private static String nullableString(JsonObject o, String field) { return o.get(field).isJsonNull() ? null : ControlJson.string(o, field); }
    private static int integer(JsonObject o, String field) {
        long n = ControlJson.number(o, field); if (n < 0 || n > Integer.MAX_VALUE) throw invalid(field); return (int) n;
    }
    private static void hex(String value, int bytes) { if (value == null || !value.matches("[0-9a-f]{" + bytes * 2 + "}")) throw invalid("hex"); }
    private static String bounded(String wire, int maximum) {
        if (wire.length() > maximum || wire.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid("size"); return wire;
    }
    private static IllegalArgumentException invalid(String reason) { return ControlJson.invalid("diagnostic completion " + reason); }
}
