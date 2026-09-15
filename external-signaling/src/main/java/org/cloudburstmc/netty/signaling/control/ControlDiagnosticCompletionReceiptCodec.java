package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Public upload settlement only; a signed committed lifecycle carrier separately establishes durability. */
public final class ControlDiagnosticCompletionReceiptCodec {
    public static final int MAX_RECEIPT_BYTES = 2048, MAX_BATCH_BYTES = 8704, MAX_RECEIPTS = 4;
    public static final Set<String> DISPOSITIONS = Set.of("recorded", "unmatched", "expired", "conflict");
    public record Receipt(ControlDiagnosticInstallationCodec.Binding installation, String attemptIdHex,
                          String completionDigest, long receivedAt, String disposition) {
        public Receipt {
            Objects.requireNonNull(installation);
            if (attemptIdHex == null || !attemptIdHex.matches("[0-9a-f]{32}")) throw invalid("attempt");
            ControlJson.digest(completionDigest); ControlJson.safe(receivedAt, true);
            if (disposition == null || !DISPOSITIONS.contains(disposition)) throw invalid("disposition");
        }
    }
    public record Batch(List<Receipt> receipts) {
        public Batch {
            if (receipts == null || receipts.isEmpty() || receipts.size() > MAX_RECEIPTS) throw invalid("batch size");
            receipts = List.copyOf(receipts); Set<List<Object>> seen = new HashSet<>();
            for (Receipt r : receipts) {
                var b = r.installation();
                if (!seen.add(List.of(b.providerOrigin(), b.hostId(), b.authorityIncarnation(), b.generation(),
                        b.nativeOwnerEpoch(), b.nativeIncarnation(), r.attemptIdHex()))) throw invalid("duplicate attempt");
            }
        }
    }
    private ControlDiagnosticCompletionReceiptCodec() { }
    public static Receipt decodeReceipt(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_RECEIPT_BYTES);
        ControlJson.fields(o, "installation", "attemptIdHex", "completionDigest", "receivedAt", "disposition");
        JsonObject ack = new JsonObject(); ack.addProperty("version", 1); ack.add("binding", ControlJson.object(o, "installation"));
        return new Receipt(ControlDiagnosticInstallationCodec.decodeAcknowledgement(ack.toString()).binding(),
                ControlJson.string(o, "attemptIdHex"), ControlJson.string(o, "completionDigest"),
                ControlJson.number(o, "receivedAt"), ControlJson.string(o, "disposition"));
    }
    public static String encodeReceipt(Receipt r) {
        Objects.requireNonNull(r); JsonObject o = new JsonObject();
        o.add("installation", JsonParser.parseString(ControlDiagnosticInstallationCodec.encodeAcknowledgement(
                new ControlDiagnosticInstallationCodec.Acknowledgement(r.installation()))).getAsJsonObject().get("binding"));
        o.addProperty("attemptIdHex", r.attemptIdHex()); o.addProperty("completionDigest", r.completionDigest());
        o.addProperty("receivedAt", r.receivedAt()); o.addProperty("disposition", r.disposition());
        return bounded(o.toString(), MAX_RECEIPT_BYTES);
    }
    public static Batch decodeBatch(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_BATCH_BYTES); ControlJson.fields(o, "version", "receipts"); ControlJson.version(o);
        if (!o.get("receipts").isJsonArray() || o.getAsJsonArray("receipts").size() > MAX_RECEIPTS) throw invalid("batch");
        var values = new ArrayList<Receipt>();
        for (var value : o.getAsJsonArray("receipts")) values.add(decodeReceipt(value.toString()));
        return new Batch(values);
    }
    public static String encodeBatch(Batch batch) {
        Objects.requireNonNull(batch); JsonObject o = new JsonObject(); o.addProperty("version", 1); JsonArray values = new JsonArray();
        for (Receipt r : batch.receipts()) values.add(JsonParser.parseString(encodeReceipt(r)));
        o.add("receipts", values); return bounded(o.toString(), MAX_BATCH_BYTES);
    }
    private static String bounded(String wire, int maximum) {
        if (wire.length() > maximum || wire.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid("size"); return wire;
    }
    private static IllegalArgumentException invalid(String reason) { return ControlJson.invalid("diagnostic completion receipt " + reason); }
}
