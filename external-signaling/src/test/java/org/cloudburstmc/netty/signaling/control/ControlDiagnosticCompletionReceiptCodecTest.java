package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticCompletionReceiptCodecTest {
    private static JsonObject fixtures() throws Exception {
        Path p = Path.of("docs/external-signaling/control-v1.diagnostic-completion-receipt.fixtures.json");
        if (!Files.exists(p)) p = Path.of("..").resolve(p);
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }
    private static JsonObject fresh() throws Exception { return fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject().getAsJsonObject("receipt"); }
    @TestFactory Stream<DynamicTest> independentCanonicalReceiptVectors() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : fixtures().getAsJsonArray("vectors")) {
            JsonObject v = item.getAsJsonObject(); tests.add(DynamicTest.dynamicTest(v.get("name").getAsString(), () -> {
                String wire = v.get("wire").getAsString();
                assertEquals(wire, ControlDiagnosticCompletionReceiptCodec.encodeReceipt(ControlDiagnosticCompletionReceiptCodec.decodeReceipt(wire)));
                assertEquals(v.get("wireSha256Hex").getAsString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire.getBytes(StandardCharsets.UTF_8))));
            }));
        }
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> independentInvalidReceiptVectors() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : fixtures().getAsJsonArray("invalid")) {
            JsonObject v = item.getAsJsonObject(); tests.add(DynamicTest.dynamicTest(v.get("name").getAsString(), () ->
                    assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionReceiptCodec.decodeReceipt(v.get("wire").getAsString()))));
        }
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> independentBatchVectors() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : fixtures().getAsJsonArray("batches")) {
            JsonObject v = item.getAsJsonObject(); tests.add(DynamicTest.dynamicTest(v.get("name").getAsString(), () -> {
                String wire = v.get("wire").getAsString();
                assertEquals(wire, ControlDiagnosticCompletionReceiptCodec.encodeBatch(ControlDiagnosticCompletionReceiptCodec.decodeBatch(wire)));
            }));
        }
        return tests.stream();
    }
    @Test void immutableOriginalReceiptSurvivesCallerMutationAndRetainsSubmittedConflictDigest() throws Exception {
        var json = fresh(); var r = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(json.toString());
        var list = new ArrayList<>(List.of(r)); var b = new ControlDiagnosticCompletionReceiptCodec.Batch(list);
        var queued = new ArrayList<Runnable>(); var pending = CompletableFuture.supplyAsync(() -> ControlDiagnosticCompletionReceiptCodec.encodeBatch(b), queued::add);
        list.clear(); json.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", 999); json.addProperty("receivedAt", 1);
        json.addProperty("completionDigest", "A".repeat(43)); json.addProperty("disposition", "expired");
        queued.get(0).run(); assertEquals(List.of(r), ControlDiagnosticCompletionReceiptCodec.decodeBatch(pending.join()).receipts());
        assertThrows(UnsupportedOperationException.class, () -> b.receipts().clear());
        var conflict = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(fixtures().getAsJsonArray("vectors").get(4).getAsJsonObject().get("wire").getAsString());
        assertEquals("conflict", conflict.disposition()); assertEquals("A".repeat(43), conflict.completionDigest());
        assertNotEquals(r.completionDigest(), conflict.completionDigest());
    }
    @Test void duplicateAttemptCannotHideBehindDifferentDigestDispositionOrProfileRebind() throws Exception {
        var original = fresh(); var r = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(original.toString());
        var changed = fresh(); changed.addProperty("completionDigest", "A".repeat(43)); changed.addProperty("disposition", "conflict");
        changed.getAsJsonObject("installation").addProperty("hostProfileRevision", "hpr_other_profile");
        var conflict = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(changed.toString());
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(r, conflict)));
        changed.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", r.installation().nativeOwnerEpoch() + 1);
        assertEquals(2, new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(r, ControlDiagnosticCompletionReceiptCodec.decodeReceipt(changed.toString()))).receipts().size());
    }
    @Test void strictClosedBoundsAndReceiptDirection() throws Exception {
        var r = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(fresh().toString());
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionReceiptCodec.Batch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionReceiptCodec.decodeReceipt(" ".repeat(2049)));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionReceiptCodec.decodeBatch(" ".repeat(8705)));
        var entries = new ArrayList<ControlDiagnosticCompletionReceiptCodec.Receipt>();
        for (int n = 0; n < 5; n++) entries.add(new ControlDiagnosticCompletionReceiptCodec.Receipt(r.installation(), String.valueOf(n).repeat(32), r.completionDigest(), r.receivedAt(), r.disposition()));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionReceiptCodec.Batch(entries));
        for (String wire : List.of("null", "{}", "{\"version\":1,\"receipts\":null}", "{\"version\":1,\"receipts\":[]}",
                "{\"version\":1,\"completions\":[]}", "{\"version\":1,\"receipts\":[],\"receipts\":[]}"))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionReceiptCodec.decodeBatch(wire));
    }
    @Test void syntaxDoesNotInferExpiryDurabilityOrTransportSuccessFromDisposition() throws Exception {
        var r = ControlDiagnosticCompletionReceiptCodec.decodeReceipt(fresh().toString());
        for (String disposition : ControlDiagnosticCompletionReceiptCodec.DISPOSITIONS) {
            var receipt = new ControlDiagnosticCompletionReceiptCodec.Receipt(r.installation(), r.attemptIdHex(), r.completionDigest(), 1, disposition);
            assertEquals(receipt, ControlDiagnosticCompletionReceiptCodec.decodeReceipt(ControlDiagnosticCompletionReceiptCodec.encodeReceipt(receipt)));
        }
        // The codec has no original report/current clock and therefore supplies no expiry or success verdict.
        var maximum = new ControlDiagnosticCompletionReceiptCodec.Receipt(r.installation(), r.attemptIdHex(), r.completionDigest(), 9007199254740991L, "unmatched");
        assertEquals(maximum, ControlDiagnosticCompletionReceiptCodec.decodeReceipt(ControlDiagnosticCompletionReceiptCodec.encodeReceipt(maximum)));
    }
}
