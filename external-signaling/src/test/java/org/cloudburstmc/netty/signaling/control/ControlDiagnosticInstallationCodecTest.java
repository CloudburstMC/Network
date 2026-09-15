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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticInstallationCodecTest {
    private static JsonArray vectors() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.diagnostic-installation.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("vectors");
    }
    private static JsonObject input() throws Exception { return vectors().get(0).getAsJsonObject().getAsJsonObject("installation"); }
    private static JsonObject binding(JsonObject input) { return input.getAsJsonObject("binding"); }
    private static JsonObject endpoint(JsonObject input, int index) { return input.getAsJsonArray("endpoints").get(index).getAsJsonObject(); }
    private static JsonObject epoch(JsonObject input, int index) { return input.getAsJsonArray("keys").get(index).getAsJsonObject(); }
    private static JsonObject answerKey(JsonObject input) { return input.getAsJsonObject("answerCatalog").getAsJsonArray("keys").get(0).getAsJsonObject(); }

    @TestFactory Stream<DynamicTest> independentPythonVectorsMatchWirePreimageDigestAndAcknowledgement() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : vectors()) {
            var vector = item.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest(vector.get("name").getAsString(), () -> {
                var installation = ControlDiagnosticInstallationCodec.decodeInstallation(vector.get("wire").getAsString());
                assertEquals(vector.get("wire").getAsString(), ControlDiagnosticInstallationCodec.encodeInstallation(installation));
                assertEquals(vector.get("preimageUtf8").getAsString(), new String(ControlDiagnosticInstallationCodec.installationPreimage(installation), StandardCharsets.UTF_8));
                assertEquals(vector.get("sha256").getAsString(), ControlDiagnosticInstallationCodec.installationDigest(installation));
                assertSame(installation, ControlDiagnosticInstallationCodec.verifyInstallation(installation));
                var acknowledgement = ControlDiagnosticInstallationCodec.decodeAcknowledgement(vector.get("acknowledgementWire").getAsString());
                assertEquals(installation.binding(), acknowledgement.binding());
                assertEquals(vector.get("acknowledgementWire").getAsString(), ControlDiagnosticInstallationCodec.encodeAcknowledgement(acknowledgement));
                for (var epoch : installation.keys()) assertFalse(vector.get("preimageUtf8").getAsString().contains(epoch.secret()));
            }));
        }
        return tests.stream();
    }

    @Test void claimedDigestIsNotHashedAndWrongClaimCannotVerify() throws Exception {
        JsonObject json = input();
        var original = ControlDiagnosticInstallationCodec.readInstallation(json);
        binding(json).addProperty("installationSha256", "A".repeat(43));
        var changed = ControlDiagnosticInstallationCodec.readInstallation(json);
        assertEquals(ControlDiagnosticInstallationCodec.installationDigest(original), ControlDiagnosticInstallationCodec.installationDigest(changed));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.verifyInstallation(changed));
    }

    @Test void ownedRecordsSurviveCallerMutationBeforeAnAsynchronousConsumerAndRedactSecrets() throws Exception {
        JsonObject json = input();
        var decoded = ControlDiagnosticInstallationCodec.readInstallation(json);
        var keys = new ArrayList<>(decoded.keys()); var endpoints = new ArrayList<>(decoded.endpoints());
        var answerKeys = new ArrayList<>(decoded.answerCatalog().keys());
        var catalog = new ControlDiagnosticInstallationCodec.AnswerCatalog(decoded.answerCatalog().providerOrigin(),
                decoded.answerCatalog().notBefore(), decoded.answerCatalog().expiresAt(), answerKeys);
        var installation = new ControlDiagnosticInstallationCodec.Installation(decoded.binding(), decoded.notBefore(), decoded.expiresAt(),
                decoded.activeKeyId(), keys, endpoints, catalog);
        var queued = new ArrayList<Runnable>();
        var result = CompletableFuture.supplyAsync(() -> ControlDiagnosticInstallationCodec.verifyInstallation(installation), queued::add);
        binding(json).addProperty("hostId", "changed"); epoch(json, 0).addProperty("secret", "changed".repeat(8));
        endpoint(json, 0).addProperty("port", 65535); answerKey(json).addProperty("keyId", "changed");
        keys.clear(); endpoints.clear(); answerKeys.clear();
        queued.get(0).run();
        assertEquals(decoded, result.join());
        assertThrows(UnsupportedOperationException.class, () -> installation.keys().clear());
        assertThrows(UnsupportedOperationException.class, () -> installation.endpoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> installation.answerCatalog().keys().clear());
        for (var epoch : installation.keys()) {
            assertFalse(epoch.toString().contains(epoch.secret()));
            assertFalse(installation.toString().contains(epoch.secret()));
            assertFalse(installation.keys().toString().contains(epoch.secret()));
        }
        byte[] preimage = ControlDiagnosticInstallationCodec.installationPreimage(installation); preimage[0] = 0;
        assertEquals(decoded.binding().installationSha256(), ControlDiagnosticInstallationCodec.installationDigest(installation));
    }

    @Test void independentFamilyExpiryDoesNotShortenGlobalOrOtherFamilyAuthority() throws Exception {
        var installation = ControlDiagnosticInstallationCodec.readInstallation(input());
        assertEquals(installation.notBefore() + 90_000, installation.endpoints().get(0).expiresAt());
        assertEquals(installation.notBefore() + 300_000, installation.endpoints().get(1).expiresAt());
        assertEquals(installation.endpoints().get(1).expiresAt(), installation.expiresAt());
        assertEquals(installation, ControlDiagnosticInstallationCodec.verifyInstallation(installation));
    }

    private record Mutation(String name, Consumer<JsonObject> change) { }
    @TestFactory Stream<DynamicTest> bindingPurposeOrderingAndAbsoluteDeadlinesRejectInvalidDocuments() throws Exception {
        JsonObject source = input(); long start = source.get("notBefore").getAsLong(), end = source.get("expiresAt").getAsLong();
        List<Mutation> mutations = List.of(
                new Mutation("HTTP control origin is not diagnostic origin", j -> { binding(j).addProperty("providerOrigin", "http://localhost"); j.getAsJsonObject("answerCatalog").addProperty("providerOrigin", "http://localhost"); }),
                new Mutation("diagnostic origin exceeds256", j -> { String origin = "https://" + String.join(".", "a".repeat(63), "a".repeat(63), "a".repeat(63), "a".repeat(60)); binding(j).addProperty("providerOrigin", origin); j.getAsJsonObject("answerCatalog").addProperty("providerOrigin", origin); }),
                new Mutation("noncanonical origin", j -> binding(j).addProperty("providerOrigin", "https://PROVIDER.example")),
                new Mutation("host alphabet differs from control identifier", j -> binding(j).addProperty("hostId", "host:one")),
                new Mutation("zero provider generation", j -> binding(j).addProperty("generation", 0)),
                new Mutation("zero native owner", j -> binding(j).addProperty("nativeOwnerEpoch", 0)),
                new Mutation("invalid native incarnation", j -> binding(j).addProperty("nativeIncarnation", "AB".repeat(16))),
                new Mutation("noncanonical profile digest", j -> binding(j).addProperty("hostProfileSha256", "A".repeat(42) + "B")),
                new Mutation("zero policy revision", j -> binding(j).addProperty("policyRevision", 0)),
                new Mutation("wrong answer key purpose", j -> answerKey(j).addProperty("family", "provider-control")),
                new Mutation("compressed answer key", j -> answerKey(j).addProperty("publicPointHex", "02" + "00".repeat(96))),
                new Mutation("wrong answer catalog origin", j -> j.getAsJsonObject("answerCatalog").addProperty("providerOrigin", "https://other.example")),
                new Mutation("repeated epoch ID", j -> epoch(j, 1).addProperty("keyId", epoch(j, 0).get("keyId").getAsString())),
                new Mutation("reversed epoch ordering", j -> { var a = j.getAsJsonArray("keys"); var first = a.get(0); a.set(0, a.get(1)); a.set(1, first); }),
                new Mutation("missing active epoch", j -> j.addProperty("activeKeyId", "D003")),
                new Mutation("active epoch starts after installation", j -> epoch(j, 1).addProperty("notBefore", start + 1)),
                new Mutation("active epoch retires before installation", j -> epoch(j, 1).addProperty("retireAt", end - 1)),
                new Mutation("epoch secret too short", j -> epoch(j, 0).addProperty("secret", "x".repeat(31))),
                new Mutation("epoch secret too long", j -> epoch(j, 0).addProperty("secret", "x".repeat(257))),
                new Mutation("epoch secret contains space", j -> epoch(j, 0).addProperty("secret", "x".repeat(32) + " ")),
                new Mutation("installation exceeds300seconds", j -> j.addProperty("expiresAt", end + 1)),
                new Mutation("empty installation lifetime", j -> j.addProperty("expiresAt", start)),
                new Mutation("endpoint expired at installation", j -> endpoint(j, 0).addProperty("expiresAt", start)),
                new Mutation("endpoint outlives installation", j -> endpoint(j, 0).addProperty("expiresAt", end + 1)),
                new Mutation("catalog starts late", j -> j.getAsJsonObject("answerCatalog").addProperty("notBefore", start + 1)),
                new Mutation("catalog expires early", j -> j.getAsJsonObject("answerCatalog").addProperty("expiresAt", end - 1)),
                new Mutation("no covering answer key", j -> answerKey(j).addProperty("validUntil", end - 1)),
                new Mutation("zero endpoint revision", j -> endpoint(j, 0).addProperty("candidateRevision", 0)),
                new Mutation("shared endpoint revision", j -> endpoint(j, 1).addProperty("candidateRevision", endpoint(j, 0).get("candidateRevision").getAsLong())),
                new Mutation("reversed endpoint ordering", j -> { var a = j.getAsJsonArray("endpoints"); var first = a.get(0); a.set(0, a.get(1)); a.set(1, first); }),
                new Mutation("duplicate numeric endpoint", j -> { var duplicate = endpoint(j, 0).deepCopy(); duplicate.addProperty("candidateRevision", 102); j.getAsJsonArray("endpoints").set(1, duplicate); }),
                new Mutation("mapped address in IPv6 family", j -> endpoint(j, 1).addProperty("addressHex", "00000000000000000000ffffc633640a")),
                new Mutation("IPv4 padding is not zero", j -> endpoint(j, 0).addProperty("addressHex", "100000000000000000000000c633640a")),
                new Mutation("unsupported endpoint type", j -> endpoint(j, 0).addProperty("candidateType", "relay")),
                new Mutation("oversized endpoint port before int cast", j -> endpoint(j, 0).addProperty("port", 4294986428L)),
                new Mutation("unknown nested property", j -> endpoint(j, 0).addProperty("trusted", true))
        );
        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            JsonObject changed = source.deepCopy(); mutation.change().accept(changed);
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.readInstallation(changed));
        }));
    }

    @Test void exactByteBoundsDuplicatePropertiesAndOriginalIntegerSpellingAreEnforced() throws Exception {
        String wire = vectors().get(0).getAsJsonObject().get("wire").getAsString();
        for (String number : List.of("7.0", "7e0", "-0", "9007199254740992", "18446744073709551616", "\"7\""))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.decodeInstallation(wire.replace("\"generation\":7", "\"generation\":" + number)), number);
        for (String bad : List.of(wire.replace("\"generation\":7", "\"generation\":7,\"generation\":7"),
                wire.replace("\"hostId\":", "\"hostId\":\"duplicate\",\"\\u0068ostId\":"),
                wire.replace("\"version\":1", "\"version\":1,\"version\":1"), " ".repeat(ControlDiagnosticInstallationCodec.MAX_INSTALLATION_BYTES + 1) + wire))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.decodeInstallation(bad));
        JsonObject fractional = input(); binding(fractional).addProperty("generation", 7.5);
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.readInstallation(fractional));
        String ack = vectors().get(0).getAsJsonObject().get("acknowledgementWire").getAsString();
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.decodeAcknowledgement(" ".repeat(2049) + ack));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.decodeAcknowledgement(ack.replace("\"generation\":7", "\"generation\":7.0")));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.decodeAcknowledgement(ack.replace("\"version\":1", "\"version\":1,\"version\":1")));
    }

    @Test void metadataLimitsAllowEightKeysAndThirtyTwoEndpointsWithoutTruncation() throws Exception {
        JsonObject json = vectors().get(4).getAsJsonObject().getAsJsonObject("installation");
        assertEquals(32, ControlDiagnosticInstallationCodec.readInstallation(json).endpoints().size());
        var epochs = json.getAsJsonArray("keys");
        for (int index = 3; index <= 8; index++) { JsonObject key = epoch(json, 1).deepCopy(); key.addProperty("keyId", "D00" + index); epochs.add(key); }
        var answers = json.getAsJsonObject("answerCatalog").getAsJsonArray("keys");
        for (int index = 2; index <= 8; index++) { JsonObject key = answerKey(json).deepCopy(); key.addProperty("keyId", "diagnostic_answer_" + index); answers.add(key); }
        var accepted = ControlDiagnosticInstallationCodec.readInstallation(json);
        assertEquals(8, accepted.keys().size()); assertEquals(8, accepted.answerCatalog().keys().size()); assertEquals(32, accepted.endpoints().size());
        JsonObject tooMany = json.deepCopy(); tooMany.getAsJsonArray("endpoints").add(endpoint(json, 0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.readInstallation(tooMany));
        JsonObject ninthEpoch = epoch(json, 1).deepCopy(); ninthEpoch.addProperty("keyId", "D009"); epochs.add(ninthEpoch);
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.readInstallation(json)); epochs.remove(8);
        JsonObject ninthAnswer = answerKey(json).deepCopy(); ninthAnswer.addProperty("keyId", "diagnostic_answer_9"); answers.add(ninthAnswer);
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticInstallationCodec.readInstallation(json));
    }
}
