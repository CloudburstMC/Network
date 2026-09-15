package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticCompletionCodecTest {
    private static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.diagnostic-completion.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static JsonObject fresh() throws Exception { return fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject().getAsJsonObject("completion"); }
    @TestFactory Stream<DynamicTest> independentCanonicalWireAndDigestVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (var item : fixtures().getAsJsonArray("vectors")) {
            JsonObject vector = item.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(vector.get("name").getAsString(), () -> {
                var c = ControlDiagnosticCompletionCodec.decodeCompletion(vector.get("wire").getAsString());
                assertEquals(vector.get("wire").getAsString(), ControlDiagnosticCompletionCodec.encodeCompletion(c));
                String preimage = vector.get("preimageUtf8").getAsString();
                assertEquals(preimage, new String(ControlDiagnosticCompletionCodec.completionPreimage(c), StandardCharsets.UTF_8));
                assertEquals(vector.get("sha256").getAsString(), Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(preimage.getBytes(StandardCharsets.UTF_8))));
                assertEquals(vector.get("sha256").getAsString(), ControlDiagnosticCompletionCodec.completionDigest(c));
            }));
        }
        return tests.stream();
    }
    @TestFactory Stream<DynamicTest> sharedInvalidWireVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (var item : fixtures().getAsJsonArray("invalid")) {
            JsonObject vector = item.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(vector.get("name").getAsString(), () ->
                    assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(vector.get("wire").getAsString()))));
        }
        return tests.stream();
    }
    @Test void closedNestedShapesExactIntegerTokensAndFieldOrdering() throws Exception {
        String wire = fresh().toString();
        for (String bad : List.of(wire.replace("\"policyRevision\":21", "\"policyRevision\":20,\"policy\\u0052evision\":21"),
                wire.replace("\"sent\":5", "\"sent\":5e0"), wire.replace("\"sent\":5", "\"sent\":-0"),
                wire.replace("\"sent\":5", "\"sent\":2147483648"), wire.replace("\"nativeOwnerEpoch\":3", "\"nativeOwnerEpoch\":9007199254740993")))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(bad));
        var json = fresh(); json.getAsJsonObject("installation").addProperty("secret", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(json.toString()));
        var original = ControlDiagnosticCompletionCodec.decodeCompletion(wire);
        JsonObject reversed = new JsonObject(); var entries = new ArrayList<>(fresh().entrySet());
        java.util.Collections.reverse(entries); entries.forEach(e -> reversed.add(e.getKey(), e.getValue()));
        assertEquals(wire, ControlDiagnosticCompletionCodec.encodeCompletion(ControlDiagnosticCompletionCodec.decodeCompletion(reversed.toString())));
        assertEquals(original, ControlDiagnosticCompletionCodec.decodeCompletion(reversed.toString()));
    }
    @Test void successRequiresActualCapsCleanupTupleAndOriginalDeadline() throws Exception {
        List<Consumer<JsonObject>> mutations = List.of(c -> c.getAsJsonObject("frames").addProperty("sent", 4),
                c -> c.getAsJsonObject("frames").addProperty("received", 5), c -> c.getAsJsonObject("frames").addProperty("sentBytes", 1025),
                c -> c.getAsJsonObject("frames").addProperty("receivedBytes", 1025), c -> c.getAsJsonObject("udp").addProperty("reserved", 257),
                c -> c.getAsJsonObject("udp").addProperty("sent", 22), c -> c.getAsJsonObject("udp").addProperty("sentBytes", 24001),
                c -> c.getAsJsonObject("udp").addProperty("rejected", 1), c -> c.addProperty("cleanupComplete", false),
                c -> c.add("completionDigestHex", null), c -> c.add("selectedRemote", null),
                c -> c.addProperty("completedAt", c.get("expiresAt").getAsLong()));
        for (var mutate : mutations) { var c = fresh(); mutate.accept(c);
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(c.toString())); }
    }
    @Test void failureRetainsOverrunCountersAndFixedGraceButNeverDirectionsOrSuccessDigest() throws Exception {
        JsonArray vectors = fixtures().getAsJsonArray("vectors");
        var c = ControlDiagnosticCompletionCodec.decodeCompletion(vectors.get(3).getAsJsonObject().get("wire").getAsString());
        assertFalse(c.success()); assertEquals(13, c.frames().received()); assertEquals(2, c.udp().rejected()); assertNull(c.completionDigestHex());
        JsonObject late = vectors.get(5).getAsJsonObject().getAsJsonObject("completion");
        assertEquals(late.get("expiresAt").getAsLong() + 300000, ControlDiagnosticCompletionCodec.decodeCompletion(late.toString()).completedAt());
        late.addProperty("completedAt", late.get("completedAt").getAsLong() + 1);
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(late.toString()));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.Udp(1, 1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.Udp(1, 1, 1, 9007199254740992L));
    }
    @Test void originalExpiryAndAdmittingBindingRemainOwnedAcrossQueuedConsumer() throws Exception {
        var json = fresh(); var c = ControlDiagnosticCompletionCodec.decodeCompletion(json.toString());
        var list = new ArrayList<>(List.of(c)); var batch = new ControlDiagnosticCompletionCodec.Batch(list);
        String expected = ControlDiagnosticCompletionCodec.completionDigest(c); var queued = new ArrayList<Runnable>();
        var pending = CompletableFuture.supplyAsync(() -> ControlDiagnosticCompletionCodec.encodeBatch(batch), queued::add);
        list.clear(); json.addProperty("expiresAt", json.get("expiresAt").getAsLong() + 1000);
        json.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", 999);
        json.getAsJsonObject("selectedLocal").addProperty("port", 65535);
        queued.get(0).run(); assertEquals(List.of(c), ControlDiagnosticCompletionCodec.decodeBatch(pending.join()).completions());
        assertEquals(expected, ControlDiagnosticCompletionCodec.completionDigest(c));
        assertThrows(UnsupportedOperationException.class, () -> batch.completions().clear());
    }
    @Test void batchCapsDuplicateIdentityAndSelectedTupleSyntax() throws Exception {
        var c = ControlDiagnosticCompletionCodec.decodeCompletion(fresh().toString());
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.Batch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.Batch(List.of(c, c)));
        List<ControlDiagnosticCompletionCodec.Completion> entries = new ArrayList<>();
        for (int n = 0; n < 5; n++) { var json = fresh(); json.addProperty("attemptIdHex", String.valueOf(n).repeat(32));
            entries.add(ControlDiagnosticCompletionCodec.decodeCompletion(json.toString())); }
        assertEquals(4, ControlDiagnosticCompletionCodec.decodeBatch(ControlDiagnosticCompletionCodec.encodeBatch(
                new ControlDiagnosticCompletionCodec.Batch(entries.subList(0, 4)))).completions().size());
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.Batch(entries));
        var otherOwner = fresh(); otherOwner.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", 4);
        assertEquals(2, new ControlDiagnosticCompletionCodec.Batch(List.of(c, ControlDiagnosticCompletionCodec.decodeCompletion(otherOwner.toString()))).completions().size());
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeCompletion(" ".repeat(4097)));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionCodec.decodeBatch(" ".repeat(16385)));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.SelectedTuple(4, "ffff000000000000000000000a000002", 1));
        assertThrows(IllegalArgumentException.class, () -> new ControlDiagnosticCompletionCodec.SelectedTuple(6, "00000000000000000000ffffc633640a", 1));
    }
    @Test void everyNativeIdentityProofTupleCounterAndTimingFieldIsDigested() throws Exception {
        List<Consumer<JsonObject>> mutations = List.of(c -> c.getAsJsonObject("installation").addProperty("hostId", "other_host"),
                c -> c.getAsJsonObject("installation").addProperty("authorityIncarnation", "other:incarnation"),
                c -> c.getAsJsonObject("installation").addProperty("generation", 8), c -> c.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", 4),
                c -> c.getAsJsonObject("installation").addProperty("nativeIncarnation", "ff".repeat(16)),
                c -> c.getAsJsonObject("installation").addProperty("hostProfileRevision", "other:profile"),
                c -> c.getAsJsonObject("installation").addProperty("hostProfileSha256", "A".repeat(43)),
                c -> c.getAsJsonObject("installation").addProperty("hostFingerprintHex", "ff".repeat(32)),
                c -> c.getAsJsonObject("installation").addProperty("policyRevision", 22),
                c -> c.getAsJsonObject("installation").addProperty("installationSha256", "A".repeat(43)), c -> c.addProperty("keyId", "D003"),
                c -> c.addProperty("attemptIdHex", "ff".repeat(16)), c -> c.addProperty("expiresAt", 1800000061000L),
                c -> c.addProperty("completedAt", 1800000005001L), c -> c.getAsJsonObject("target").addProperty("candidateRevision", 103),
                c -> c.addProperty("offerDigestHex", "ff".repeat(32)), c -> c.addProperty("clientFingerprintHex", "ff".repeat(32)),
                c -> c.addProperty("completionDigestHex", "ff".repeat(32)), c -> c.getAsJsonObject("selectedLocal").addProperty("port", 19133),
                c -> c.getAsJsonObject("selectedRemote").addProperty("port", 45001), c -> c.getAsJsonObject("frames").addProperty("sent", 6),
                c -> c.getAsJsonObject("udp").addProperty("sentBytes", 8001));
        String original = ControlDiagnosticCompletionCodec.completionDigest(ControlDiagnosticCompletionCodec.decodeCompletion(fresh().toString()));
        for (var mutation : mutations) { var c = fresh(); mutation.accept(c);
            assertNotEquals(original, ControlDiagnosticCompletionCodec.completionDigest(ControlDiagnosticCompletionCodec.decodeCompletion(c.toString()))); }
    }
}
