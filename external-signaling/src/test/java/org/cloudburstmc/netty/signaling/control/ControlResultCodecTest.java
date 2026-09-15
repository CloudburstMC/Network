package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlResultCodecTest {
    static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.results.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static JsonObject first() throws Exception { return fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject(); }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String wrapped(byte[] body) throws Exception {
        var result = first().getAsJsonObject("result").deepCopy(); result.addProperty("body", ProviderCrypto.base64(body)); return result.toString();
    }
    @Test
    void matchesIndependentNodeVectorsIncludingWholeProviderFrameSignatureAndOriginalBytes() throws Exception {
        var fixture = fixtures();
        for (var element : fixture.getAsJsonArray("vectors")) {
            var vector = element.getAsJsonObject(); String wire = vector.get("wire").getAsString();
            var result = ControlResultCodec.decode(wire);
            var intent = ControlLifecycleCodec.decodeIntent(vector.get("intent").toString());
            byte[] original = bytes(vector.get("bodyUtf8").getAsString());
            assertEquals(wire, ControlResultCodec.encode(ControlResultCodec.create(result.receipt(), original)));
            assertArrayEquals(original, ControlResultCodec.verify(result, intent).bodyBytes());
            assertEquals(vector.get("intentDigest").getAsString(), ControlLifecycleCodec.intentDigest(intent));
            assertEquals(vector.get("payloadSha256").getAsString(), ControlFrameCodec.payloadDigest(bytes(wire)));
            var frame = ControlFrameCodec.decode(vector.get("frame").toString()); var c = vector.getAsJsonObject("context");
            var key = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL,
                    fixture.getAsJsonObject("providerKey").get("keyId").getAsString(),
                    ProviderCrypto.publicKey(fixture.getAsJsonObject("providerKey").getAsJsonObject("publicKeyJwk")),
                    c.get("keyValidFrom").getAsLong(), c.get("keyValidUntil").getAsLong());
            var context = new ControlFrameCodec.Context(frame.direction(), frame.audience(), frame.instanceId(), frame.generation(),
                    frame.sessionId(), frame.sessionEpoch(), frame.connectionId(), frame.capabilities(), frame.sequence(),
                    c.get("now").getAsLong(), c.get("authorityExpiresAt").getAsLong(), c.get("clockSkewMillis").getAsLong());
            assertArrayEquals(bytes(wire), ControlFrameCodec.verify(vector.get("frame").toString(), context, key).payloadBytes());
            String changed = ControlResultCodec.encode(ControlResultCodec.create(result.receipt(), bytes("{}")));
            if (!changed.equals(wire)) {
                JsonObject tampered = vector.getAsJsonObject("frame").deepCopy();
                tampered.addProperty("payload", ProviderCrypto.base64(bytes(changed)));
                tampered.addProperty("payloadSha256", ControlFrameCodec.payloadDigest(bytes(changed)));
                assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(tampered.toString(), context, key));
            }
        }
    }
    @Test
    void receiptIdentityIsIndependentOfRetryObservations() throws Exception {
        var vectors = fixtures().getAsJsonArray("vectors");
        var a = ControlResultCodec.decode(vectors.get(0).getAsJsonObject().get("wire").getAsString());
        var b = ControlResultCodec.decode(vectors.get(1).getAsJsonObject().get("wire").getAsString());
        assertEquals(a.receipt(), b.receipt()); assertNotEquals(a.body(), b.body());
        assertFalse(a.toString().contains(a.body()));
    }
    @Test
    void enforcesOriginalByteAndEnvelopeLimitsSeparately() throws Exception {
        byte[] body = bytes("{\"x\":\"" + "a".repeat(ControlResultCodec.MAX_BODY_BYTES - 8) + "\"}");
        assertEquals(ControlResultCodec.MAX_BODY_BYTES, body.length);
        String wire = wrapped(body);
        assertArrayEquals(body, ControlResultCodec.decode(wire).bodyBytes());
        assertNotNull(ControlResultCodec.decode(wire + " ".repeat(ControlResultCodec.MAX_ENVELOPE_BYTES - wire.length())));
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wire + " ".repeat(ControlResultCodec.MAX_ENVELOPE_BYTES + 1 - wire.length())));
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.create(ControlResultCodec.decode(wire).receipt(), Arrays.copyOf(body, body.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wrapped(bytes("{\"x\":\"" + "雪".repeat(ControlResultCodec.MAX_BODY_BYTES / 2) + "\"}"))));
    }
    @Test
    void rejectsMissingBodiesAmbiguousFieldsInvalidJsonAndNoncanonicalEncodings() throws Exception {
        for (String body : List.of("", "[]", "null", "true", "{}{}", "\uFEFF{}", "{\"x\":1,\"\\u0078\":2}",
                "{\"a\":{\"x\":1,\"x\":2}}", "{\"secret\":\"do-not-log\"")) {
            assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wrapped(bytes(body))));
        }
        for (byte[] body : List.of(new byte[]{(byte)0xff}, new byte[]{(byte)0xc0, (byte)0xaf})) {
            assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wrapped(body)));
        }
        for (String body : List.of("e30=", "e31", "e30\n", "")) {
            var changed = first().getAsJsonObject("result").deepCopy(); changed.addProperty("body", body);
            assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(changed.toString()));
        }
        var missing = first().getAsJsonObject("result").deepCopy(); missing.remove("body");
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(missing.toString()));
        String wire = first().get("wire").getAsString();
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wire.replaceFirst("\"version\":1", "\"version\":1,\"version\":1")));
        var extra = first().getAsJsonObject("result").deepCopy(); extra.addProperty("secret", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(extra.toString()));
    }
    @Test
    void usesMatchingDepthAndNumericRulesWithoutEnvelopeArrayLimitsForApplicationBodies() throws Exception {
        assertNotNull(ControlResultCodec.decode(wrapped(bytes("{\"x\":".repeat(16) + "-1.25e-2" + "}".repeat(16)))));
        assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.decode(wrapped(bytes("{\"x\":".repeat(17) + "0" + "}".repeat(17)))));
        assertNotNull(ControlResultCodec.decode(wrapped(bytes("{\"values\":[" + "0.5,".repeat(63) + "1]}"))));
    }
    @Test
    void wrongIntentCannotReleaseApplicationDataAndCallerCannotMutateOwnedBytes() throws Exception {
        var v = first(); var result = ControlResultCodec.decode(v.get("wire").getAsString());
        for (String field : List.of("operation", "sequence", "audience")) {
            var changed = v.getAsJsonObject("intent").deepCopy();
            if (field.equals("sequence")) changed.addProperty(field, changed.get(field).getAsLong() + 1);
            else changed.addProperty(field, field.equals("operation") ? "rotate" : "https://other.example");
            var intent = ControlLifecycleCodec.decodeIntent(changed.toString());
            assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.verify(result, intent));
        }
        byte[] body = bytes(v.get("bodyUtf8").getAsString()); var owned = ControlResultCodec.create(result.receipt(), body);
        Arrays.fill(body, (byte)0); byte[] returned = owned.bodyBytes(); Arrays.fill(returned, (byte)0);
        assertArrayEquals(bytes(v.get("bodyUtf8").getAsString()), owned.bodyBytes());
    }
    @Test
    void requiresExactEmptyObjectBodyForAllNoncommittedResults() throws Exception {
        for (String disposition : List.of("rejected", "expired", "unknown")) {
            var receipt = first().getAsJsonObject("result").getAsJsonObject("receipt").deepCopy();
            receipt.addProperty("disposition", disposition); receipt.add("committedAt", com.google.gson.JsonNull.INSTANCE);
            receipt.add("commitRevision", com.google.gson.JsonNull.INSTANCE); receipt.addProperty("code", "unavailable");
            var decoded = ControlLifecycleCodec.decodeReceipt(receipt.toString());
            assertNotNull(ControlResultCodec.create(decoded, bytes("{}")));
            for (String body : List.of("{ }", "{}\n", "{\"accepted\":false}")) {
                assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.create(decoded, bytes(body)));
            }
        }
    }
}
