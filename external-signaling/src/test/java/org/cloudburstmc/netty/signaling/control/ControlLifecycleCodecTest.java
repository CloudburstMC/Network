package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlLifecycleCodecTest {
    static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.lifecycle.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    @Test
    void matchesIndependentIntentDigestsAndPreservesBodyBytesAcrossCarriers() throws Exception {
        for (var value : fixtures().getAsJsonArray("vectors")) {
            var vector = value.getAsJsonObject();
            var intent = ControlLifecycleCodec.decodeIntent(vector.get("intent").toString());
            byte[] body = vector.get("bodyUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
            assertEquals(vector.get("intentDigest").getAsString(), ControlLifecycleCodec.intentDigest(intent));
            ControlLifecycleCodec.verifyBody(intent, body); // HTTPS keeps these original bytes.
            var ws = ControlLifecycleCodec.decodeWsRequest(ControlLifecycleCodec.encodeWsRequest(intent, body));
            assertEquals(intent, ws.intent());
            assertArrayEquals(body, ws.bodyBytes());
            assertEquals(ControlLifecycleCodec.intentDigest(intent), ControlLifecycleCodec.intentDigest(ws.intent()));
            var receipt = ControlLifecycleCodec.decodeReceipt(vector.get("receipt").toString());
            ControlLifecycleCodec.verifyReceipt(receipt, intent);
            assertEquals(receipt, ControlLifecycleCodec.decodeReceipt(ControlLifecycleCodec.encodeReceipt(receipt)));
        }
    }

    @Test
    void largerBodiesRetainTheHttpIntentButCannotSilentlyChangeForWs() {
        byte[] body = new byte[ControlLifecycleCodec.MAX_WS_BODY_BYTES + 1];
        java.util.Arrays.fill(body, (byte) 'x');
        var intent = ControlLifecycleCodec.intent("https://provider.example", "heartbeat", "instance_01", 3, 1,
                "persisted_intent_01", body);
        ControlLifecycleCodec.verifyBody(intent, body);
        assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.encodeWsRequest(intent, body));
        assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.verifyBody(intent,
                java.util.Arrays.copyOf(body, ControlLifecycleCodec.MAX_WS_BODY_BYTES)));
        assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.intent("https://provider.example", "heartbeat",
                "instance_01", 3, 1, "persisted_intent_01", new byte[ControlLifecycleCodec.MAX_HTTP_BODY_BYTES + 1]));
    }

    @Test
    void eachSemanticChangeGetsAnotherIntentAndCannotReuseAReceipt() throws Exception {
        var vector = fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject();
        var original = vector.getAsJsonObject("intent");
        var receipt = ControlLifecycleCodec.decodeReceipt(vector.get("receipt").toString());
        for (String field : List.of("audience", "operation", "instanceId", "generation", "sequence", "idempotencyKey", "payloadSha256")) {
            var changed = original.deepCopy();
            switch (field) {
                case "audience" -> changed.addProperty(field, "https://other.example");
                case "operation" -> changed.addProperty(field, "rotate");
                case "generation", "sequence" -> changed.addProperty(field, changed.get(field).getAsLong() + 1);
                case "payloadSha256" -> changed.addProperty(field, ControlFrameCodec.payloadDigest(new byte[0]));
                default -> changed.addProperty(field, "changed_identifier_01");
            }
            var intent = ControlLifecycleCodec.decodeIntent(changed.toString());
            assertNotEquals(receipt.intentDigest(), ControlLifecycleCodec.intentDigest(intent));
            assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.verifyReceipt(receipt, intent));
        }
    }

    @Test
    void receiptsCannotCarrySecretsCurrentSnapshotsOrAFalseCommit() throws Exception {
        var vector = fixtures().getAsJsonArray("vectors").get(1).getAsJsonObject();
        var original = vector.getAsJsonObject("receipt");
        for (String field : List.of("ticketKey", "secret", "response", "result", "current", "connectivityReport")) {
            var changed = original.deepCopy();
            changed.addProperty(field, "forbidden-in-immutable-receipt");
            assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.decodeReceipt(changed.toString()));
        }
        for (String disposition : List.of("rejected", "expired", "unknown")) {
            var changed = original.deepCopy();
            changed.addProperty("disposition", disposition);
            assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.decodeReceipt(changed.toString()));
            changed.add("committedAt", com.google.gson.JsonNull.INSTANCE);
            changed.add("commitRevision", com.google.gson.JsonNull.INSTANCE);
            assertEquals(disposition, ControlLifecycleCodec.decodeReceipt(changed.toString()).disposition());
        }
    }

    @Test
    void duplicateAndLossyFieldsCannotChangeIntentIdentity() throws Exception {
        String original = fixtures().getAsJsonArray("vectors").get(0).getAsJsonObject().get("intent").toString();
        for (String changed : List.of(original.replace("\"generation\":3", "\"generation\":3,\"\\u0067eneration\":3"),
                original.replace("\"generation\":3", "\"generation\":3.000000000000001"),
                original.replace("\"generation\":3", "\"generation\":3e0"),
                original.replace("\"generation\":3", "\"generation\":\"3\""),
                original.replace("\"version\":1", "\"version\":1,\"keyId\":\"changed_authentication_key\""))) {
            assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.decodeIntent(changed));
        }
    }
}
