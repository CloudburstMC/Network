package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlFrameCodecTest {
    static Path fixturePath() {
        Path path = Path.of("docs/external-signaling/control-v1.frames.fixtures.json");
        return Files.exists(path) ? path : Path.of("..").resolve(path);
    }

    static JsonObject fixtures() throws Exception {
        return JsonParser.parseString(Files.readString(fixturePath())).getAsJsonObject();
    }

    static ControlFrameCodec.Context context(JsonObject vector) {
        var frame = ControlFrameCodec.decode(vector.get("frame").toString());
        var context = vector.getAsJsonObject("context");
        return new ControlFrameCodec.Context(frame.direction(), frame.audience(), frame.instanceId(), frame.generation(),
                frame.sessionId(), frame.sessionEpoch(), frame.connectionId(), frame.capabilities(), frame.sequence(),
                context.get("now").getAsLong(), context.get("authorityExpiresAt").getAsLong(), context.get("clockSkewMillis").getAsLong());
    }

    static ControlFrameCodec.VerificationKey key(JsonObject fixture, JsonObject vector) throws Exception {
        String family = vector.get("keyFamily").getAsString();
        var key = fixture.getAsJsonObject("keys").getAsJsonObject(family);
        var context = vector.getAsJsonObject("context");
        return new ControlFrameCodec.VerificationKey(family.equals("machine") ? ControlFrameCodec.KeyFamily.MACHINE
                : ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, key.get("keyId").getAsString(),
                ProviderCrypto.publicKey(key.getAsJsonObject("publicKeyJwk")),
                context.get("keyValidFrom").getAsLong(), context.get("keyValidUntil").getAsLong());
    }

    @Test
    void verifiesIndependentNodeVectorsAndExactCanonicalBytes() throws Exception {
        JsonObject fixture = fixtures();
        for (var value : fixture.getAsJsonArray("vectors")) {
            JsonObject vector = value.getAsJsonObject();
            var frame = ControlFrameCodec.verify(vector.get("frame").toString(), context(vector), key(fixture, vector));
            assertEquals(vector.get("signingText").getAsString(),
                    new String(ControlFrameCodec.signingBytes(frame), StandardCharsets.UTF_8));
            assertEquals(vector.get("payloadUtf8").getAsString(), new String(frame.payloadBytes(), StandardCharsets.UTF_8));
            assertEquals(frame, ControlFrameCodec.decode(ControlFrameCodec.encode(frame)));
            assertThrows(UnsupportedOperationException.class, () -> frame.capabilities().add("addressed"));
        }
    }

    @Test
    void everyBoundMetadataMutationInvalidatesProof() throws Exception {
        JsonObject fixture = fixtures(), vector = fixture.getAsJsonArray("vectors").get(1).getAsJsonObject();
        JsonObject original = vector.getAsJsonObject("frame");
        List<JsonObject> mutations = new ArrayList<>();
        for (String field : List.of("type", "id", "audience", "instanceId", "sessionId", "connectionId")) {
            JsonObject changed = original.deepCopy();
            changed.addProperty(field, switch (field) {
                case "type" -> "assisted.cancel";
                case "audience" -> "https://other.example";
                default -> "changed_identifier_0001";
            });
            mutations.add(changed);
        }
        for (String field : List.of("sequence", "generation", "sessionEpoch", "sentAt", "expiresAt")) {
            JsonObject changed = original.deepCopy();
            changed.addProperty(field, changed.get(field).getAsLong() + 1);
            mutations.add(changed);
        }
        JsonObject capabilities = original.deepCopy();
        JsonArray selected = new JsonArray();
        List.of("addressed", "assisted-diagnostic", "assisted-gameplay", "request-response").forEach(selected::add);
        capabilities.add("capabilities", selected);
        mutations.add(capabilities);
        JsonObject key = original.deepCopy();
        key.getAsJsonObject("authentication").addProperty("keyId", "another_key");
        mutations.add(key);
        JsonObject payload = original.deepCopy();
        byte[] bytes = "{\"changed\":true}".getBytes(StandardCharsets.UTF_8);
        payload.addProperty("payload", ProviderCrypto.base64(bytes));
        payload.addProperty("payloadSha256", ControlFrameCodec.payloadDigest(bytes));
        mutations.add(payload);
        JsonObject direction = original.deepCopy();
        direction.addProperty("direction", "host-to-provider");
        direction.addProperty("type", "assisted.answer");
        mutations.add(direction);
        for (var changed : mutations) {
            // Even a receiver mistakenly taking its expected context from the changed envelope cannot
            // make the signature verify. Context validation independently rejects replay below.
            JsonObject alteredVector = vector.deepCopy();
            alteredVector.add("frame", changed);
            assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(changed.toString(),
                    context(alteredVector), key(fixture, vector)), changed.toString());
        }
    }

    @Test
    void rejectsDuplicatesUnknownFieldsWrongTypesAndNonStrictJson() throws Exception {
        String wire = fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject().get("frame").toString();
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode("\uFEFF" + wire));
        for (String invalid : List.of(
                wire.replace("\"version\":1", "\"version\":1,\"version\":1"),
                wire.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"),
                wire.replace("\"scheme\":", "\"unknown\":1,\"scheme\":"),
                wire.replace("\"keyId\":", "\"scheme\":\"nxs-control-es384-v1\",\"keyId\":"),
                wire.replace("\"sequence\":1", "\"sequence\":\"1\""),
                wire.replace("\"sequence\":1", "\"sequence\":1.5"),
                wire.replace("\"sequence\":1", "\"sequence\":1.0"),
                wire.replace("\"sequence\":1", "\"sequence\":1e0"),
                wire.replace("\"sequence\":1", "\"sequence\":-0"),
                wire.replace("\"sequence\":1", "\"sequence\":1.00000000000000000001"),
                wire.replace("\"sequence\":1", "\"sequence\":9007199254740992"),
                wire.replace("\"sequence\":1", "\"sequence\":0"),
                wire.replace("\"version\":1", "\"version\":2"),
                wire.replace("\"version\":1", "version:1"),
                wire.replace("\"version\":1", "\"version\":1,\"extra\":[]"),
                wire + "{}", wire + "/*comment*/", "[" + wire + "]")) {
            assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(invalid));
        }
    }

    @Test
    void rejectsDigestAliasInvalidUtf8DerAndOversizedPayloadBeforeVerification() throws Exception {
        JsonObject fixture = fixtures(), vector = fixture.getAsJsonArray("vectors").get(0).getAsJsonObject();
        JsonObject original = vector.getAsJsonObject("frame");
        List<JsonObject> invalid = new ArrayList<>();
        for (String payload : List.of("Zg=", "Zh", "A", ProviderCrypto.base64(new byte[]{(byte) 0xff}),
                ProviderCrypto.base64(new byte[ControlFrameCodec.MAX_PAYLOAD_BYTES + 1]))) {
            JsonObject changed = original.deepCopy();
            changed.addProperty("payload", payload);
            invalid.add(changed);
        }
        JsonObject paddedDigest = original.deepCopy();
        paddedDigest.addProperty("payloadSha256", paddedDigest.get("payloadSha256").getAsString() + "=");
        invalid.add(paddedDigest);
        JsonObject digestAlias = original.deepCopy();
        String digest = digestAlias.get("payloadSha256").getAsString();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
        int end = alphabet.indexOf(digest.charAt(digest.length() - 1));
        digestAlias.addProperty("payloadSha256", digest.substring(0, digest.length() - 1) + alphabet.charAt(end + 1));
        invalid.add(digestAlias);
        Signature der = Signature.getInstance("SHA384withECDSA");
        der.initSign(ProviderCrypto.privateKey(fixture.getAsJsonObject("keys").getAsJsonObject("machine").get("privateKeyPkcs8").getAsString()));
        der.update(vector.get("signingText").getAsString().getBytes(StandardCharsets.UTF_8));
        JsonObject derFrame = original.deepCopy();
        derFrame.getAsJsonObject("authentication").addProperty("signature", ProviderCrypto.base64(der.sign()));
        invalid.add(derFrame);
        for (JsonObject changed : invalid) assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(changed.toString()));
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(" ".repeat(ControlFrameCodec.MAX_FRAME_BYTES + 1)));
    }

    @Test
    void exactSequencePhysicalBindingAndDirectionPreventReplay() throws Exception {
        JsonObject fixture = fixtures(), vector = fixture.getAsJsonArray("vectors").get(0).getAsJsonObject();
        var expected = context(vector);
        for (var changed : List.of(
                new ControlFrameCodec.Context(expected.direction(), expected.audience(), expected.instanceId(), expected.generation(), expected.sessionId(), expected.sessionEpoch(), expected.connectionId(), expected.capabilities(), expected.expectedSequence() + 1, expected.now(), expected.authorityExpiresAt(), 1000),
                new ControlFrameCodec.Context(expected.direction(), expected.audience(), expected.instanceId(), expected.generation(), expected.sessionId(), expected.sessionEpoch(), "replacement_connection_01", expected.capabilities(), expected.expectedSequence(), expected.now(), expected.authorityExpiresAt(), 1000),
                new ControlFrameCodec.Context(ControlFrameCodec.Direction.PROVIDER_TO_HOST, expected.audience(), expected.instanceId(), expected.generation(), expected.sessionId(), expected.sessionEpoch(), expected.connectionId(), expected.capabilities(), expected.expectedSequence(), expected.now(), expected.authorityExpiresAt(), 1000))) {
            assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(vector.get("frame").toString(), changed, key(fixture, vector)));
        }
    }

    @Test
    void strictDeadlinesCannotBorrowSkewFromAParentOrRetiredKey() throws Exception {
        JsonObject fixture = fixtures(), vector = fixture.getAsJsonArray("vectors").get(0).getAsJsonObject();
        var context = context(vector);
        var key = key(fixture, vector);
        var frame = ControlFrameCodec.decode(vector.get("frame").toString());
        for (long now : List.of(frame.expiresAt(), frame.expiresAt() + 1, frame.sentAt() - 1001)) {
            var changed = new ControlFrameCodec.Context(context.direction(), context.audience(), context.instanceId(), context.generation(), context.sessionId(), context.sessionEpoch(), context.connectionId(), context.capabilities(), context.expectedSequence(), now, context.authorityExpiresAt(), 1000);
            assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(vector.get("frame").toString(), changed, key));
        }
        var shortened = new ControlFrameCodec.Context(context.direction(), context.audience(), context.instanceId(), context.generation(), context.sessionId(), context.sessionEpoch(), context.connectionId(), context.capabilities(), context.expectedSequence(), context.now(), frame.expiresAt() - 1, 30000);
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(vector.get("frame").toString(), shortened, key));
        var retiring = new ControlFrameCodec.VerificationKey(key.family(), key.keyId(), key.key(), key.validFrom(), frame.expiresAt() - 1);
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(vector.get("frame").toString(), context, retiring));
        JsonObject overlong = vector.getAsJsonObject("frame").deepCopy();
        overlong.addProperty("expiresAt", frame.sentAt() + 60001);
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(overlong.toString()));
    }

    @Test
    void providerControlKeysAreSeparateAndMustUseP384() throws Exception {
        JsonObject fixture = fixtures(), vector = fixture.getAsJsonArray("vectors").get(1).getAsJsonObject();
        var key = key(fixture, vector);
        var misclassified = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.MACHINE, key.keyId(), key.key(), key.validFrom(), key.validUntil());
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.verify(vector.get("frame").toString(), context(vector), misclassified));
        var p256 = KeyPairGenerator.getInstance("EC");
        p256.initialize(new ECGenParameterSpec("secp256r1"));
        assertThrows(IllegalArgumentException.class, () -> new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL,
                key.keyId(), p256.generateKeyPair().getPublic(), key.validFrom(), key.validUntil()));
    }

    @Test
    void capabilitiesAreSortedExactAndDiagnosticIsNotGameplay() throws Exception {
        JsonObject fixture = fixtures();
        JsonObject diagnostic = fixture.getAsJsonArray("vectors").get(2).getAsJsonObject().getAsJsonObject("frame");
        for (List<String> capabilities : List.of(List.of("addressed", "request-response"),
                List.of("assisted-diagnostic", "request-response"),
                List.of("addressed", "assisted-gameplay", "request-response"),
                List.of("request-response", "addressed", "assisted-diagnostic"),
                List.of("addressed", "assisted-diagnostic", "assisted-diagnostic", "request-response"))) {
            JsonObject changed = diagnostic.deepCopy();
            JsonArray values = new JsonArray();
            capabilities.forEach(values::add);
            changed.add("capabilities", values);
            assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(changed.toString()));
        }
        JsonObject bootstrap = diagnostic.deepCopy();
        bootstrap.addProperty("type", "session.hello");
        assertThrows(IllegalArgumentException.class, () -> ControlFrameCodec.decode(bootstrap.toString()));
    }

    /** Standalone cross-language check: writes newly Java-signed public fixture messages to stdout. */
    public static void main(String[] args) throws Exception {
        JsonObject fixture = fixtures();
        JsonArray output = new JsonArray();
        for (var item : fixture.getAsJsonArray("vectors")) {
            var vector = item.getAsJsonObject();
            var frame = ControlFrameCodec.decode(vector.get("frame").toString());
            var key = fixture.getAsJsonObject("keys").getAsJsonObject(vector.get("keyFamily").getAsString());
            var signed = ControlFrameCodec.sign(frame, key(fixture, vector).family(), ProviderCrypto.privateKey(key.get("privateKeyPkcs8").getAsString()));
            ControlFrameCodec.verify(ControlFrameCodec.encode(signed), context(vector), key(fixture, vector));
            JsonObject row = new JsonObject();
            row.addProperty("name", vector.get("name").getAsString());
            row.addProperty("signingText", new String(ControlFrameCodec.signingBytes(signed), StandardCharsets.UTF_8));
            row.addProperty("signature", signed.authentication().signature());
            output.add(row);
        }
        System.out.println(output);
    }
}
