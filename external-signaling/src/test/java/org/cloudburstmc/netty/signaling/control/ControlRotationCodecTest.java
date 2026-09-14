package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlRotationCodecTest {
    static JsonObject fixture() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.rotation.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static ControlRotationCodec.Context context(JsonObject value) {
        return new ControlRotationCodec.Context(value.get("audience").getAsString(), value.get("instanceId").getAsString(),
                value.get("generation").getAsLong(), value.get("oldKeyId").getAsString(), value.get("idempotencyKey").getAsString());
    }
    static KeyPair candidate() throws Exception {
        var key = fixture().getAsJsonObject("key");
        return new KeyPair(ProviderCrypto.publicKey(key.getAsJsonObject("publicKeyJwk")), ProviderCrypto.privateKey(key.get("privateKeyPkcs8").getAsString()));
    }
    @Test void verifiesIndependentVectorsAndAllIdentityBindings() throws Exception {
        for (var item : fixture().getAsJsonArray("vectors")) {
            var vector = item.getAsJsonObject(); var expected = context(vector.getAsJsonObject("context"));
            var body = ControlRotationCodec.verify(vector.get("body").toString(), expected);
            assertEquals(vector.get("signingText").getAsString(), new String(ControlRotationCodec.signingBytes(body, expected), StandardCharsets.UTF_8));
            var signed = ControlRotationCodec.create(body.newKeyId(), candidate(), expected);
            assertEquals(signed, ControlRotationCodec.verify(ControlRotationCodec.encode(signed), expected));
            for (String field : List.of("audience", "instanceId", "generation", "oldKeyId", "idempotencyKey")) {
                var changed = vector.getAsJsonObject("context").deepCopy();
                if (field.equals("generation")) changed.addProperty(field, 4);
                else changed.addProperty(field, field.equals("audience") ? "https://other.example" : "changed_identifier_0001");
                assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.verify(vector.get("body").toString(), context(changed)), field);
            }
            var changed = vector.getAsJsonObject("body").deepCopy(); changed.addProperty("newKeyId", "another_chosen_key_0001");
            assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.verify(changed.toString(), expected));
        }
    }
    @Test void rejectsPrivateMaterialAliasesAndMismatchedCandidateKeys() throws Exception {
        var vector = fixture().getAsJsonArray("vectors").get(0).getAsJsonObject();
        String wire = vector.get("body").toString();
        assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.decode(wire.replace("\"version\":1", "\"version\":1e0")));
        assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.decode(wire.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1")));
        var changed = vector.getAsJsonObject("body").deepCopy(); changed.getAsJsonObject("publicKeyJwk").addProperty("d", "private");
        assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.decode(changed.toString()));
        var expected = context(vector.getAsJsonObject("context"));
        var wrongPair = new KeyPair(candidate().getPublic(), ProviderCrypto.generate().getPrivate());
        assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.create("new_chosen_key_0001", wrongPair, expected));
        assertThrows(IllegalArgumentException.class, () -> ControlRotationCodec.create("new_chosen_key_0001", candidate(),
                new ControlRotationCodec.Context(expected.audience(), expected.instanceId(), expected.generation(), "new_chosen_key_0001", expected.idempotencyKey())));
    }
    public static void main(String[] args) throws Exception {
        JsonArray output = new JsonArray();
        for (var item : fixture().getAsJsonArray("vectors")) {
            var vector = item.getAsJsonObject(); var expected = context(vector.getAsJsonObject("context"));
            var body = ControlRotationCodec.create(vector.getAsJsonObject("body").get("newKeyId").getAsString(), candidate(), expected);
            JsonObject value = new JsonObject(); value.addProperty("name", vector.get("name").getAsString()); value.addProperty("proof", body.proof());
            value.addProperty("signingText", new String(ControlRotationCodec.signingBytes(body, expected), StandardCharsets.UTF_8)); output.add(value);
        }
        System.out.println(output);
    }
}
