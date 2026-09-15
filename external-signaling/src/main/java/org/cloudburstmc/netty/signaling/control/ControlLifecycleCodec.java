package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Draft immutable intent and redacted receipt formats, without journals, mutation handlers or transport. */
public final class ControlLifecycleCodec {
    public static final int MAX_HTTP_BODY_BYTES = 65536;
    public static final int MAX_WS_BODY_BYTES = 45056;
    public static final int MAX_INTENT_BYTES = 4096;
    private static final Set<String> OPERATIONS = Set.of("heartbeat", "outcomes", "rotate", "retire", "deregister");

    public record Intent(int version, String audience, String operation, String instanceId, long generation,
                         long sequence, String idempotencyKey, String payloadSha256) { }

    public record Receipt(int version, String intentDigest, String operation, String instanceId, long generation,
                          long sequence, String idempotencyKey, String disposition, Long committedAt,
                          Long commitRevision, String code) { }

    /** The original operation bytes remain opaque. Decode/validate the operation separately before acting. */
    public record WsRequest(Intent intent, String body) {
        public byte[] bodyBytes() { return ControlJson.base64(this.body, MAX_WS_BODY_BYTES, true); }
    }

    private ControlLifecycleCodec() { }

    public static Intent intent(String audience, String operation, String instanceId, long generation,
                                long sequence, String idempotencyKey, byte[] originalBody) {
        validateBody(originalBody, MAX_HTTP_BODY_BYTES);
        Intent value = new Intent(1, audience, operation, instanceId, generation, sequence,
                idempotencyKey, ControlFrameCodec.payloadDigest(originalBody));
        validate(value);
        return value;
    }

    public static String intentDigest(Intent value) {
        validate(value);
        return ProviderCrypto.base64(ProviderCrypto.digest(ProviderCrypto.array("nethernet-control-lifecycle-intent-v1",
                1, value.audience(), value.operation(), value.instanceId(), value.generation(), value.sequence(),
                value.idempotencyKey(), value.payloadSha256())));
    }

    public static Intent decodeIntent(String wire) {
        return readIntent(ControlJson.parse(wire, MAX_INTENT_BYTES));
    }

    public static String encodeIntent(Intent value) { return intentObject(value).toString(); }

    public static String encodeWsRequest(Intent intent, byte[] originalBody) {
        validateBody(originalBody, MAX_WS_BODY_BYTES);
        verifyBody(intent, originalBody);
        JsonObject value = new JsonObject();
        value.add("intent", intentObject(intent));
        value.addProperty("body", ProviderCrypto.base64(originalBody));
        String result = value.toString();
        if (result.getBytes(StandardCharsets.UTF_8).length > ControlFrameCodec.MAX_PAYLOAD_BYTES) throw ControlJson.invalid("WS lifecycle size");
        return result;
    }

    public static WsRequest decodeWsRequest(String wire) {
        JsonObject value = ControlJson.parse(wire, ControlFrameCodec.MAX_PAYLOAD_BYTES);
        ControlJson.fields(value, "intent", "body");
        if (!value.get("intent").isJsonObject()) throw ControlJson.invalid("intent object");
        Intent intent = readIntent(value.getAsJsonObject("intent"));
        String body = ControlJson.string(value, "body");
        byte[] originalBody = ControlJson.base64(body, MAX_WS_BODY_BYTES, true);
        verifyBody(intent, originalBody);
        return new WsRequest(intent, body);
    }

    /** HTTP and WS must both compare original bytes to the same immutable intent before processing. */
    public static void verifyBody(Intent intent, byte[] originalBody) {
        validate(intent);
        validateBody(originalBody, MAX_HTTP_BODY_BYTES);
        if (!ControlFrameCodec.payloadDigest(originalBody).equals(intent.payloadSha256())) throw ControlJson.invalid("intent body digest");
    }

    public static Receipt decodeReceipt(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_INTENT_BYTES);
        ControlJson.fields(value, "version", "intentDigest", "operation", "instanceId", "generation", "sequence",
                "idempotencyKey", "disposition", "committedAt", "commitRevision", "code");
        Receipt receipt = new Receipt(ControlJson.version(value), ControlJson.string(value, "intentDigest"),
                ControlJson.string(value, "operation"), ControlJson.string(value, "instanceId"), ControlJson.number(value, "generation"),
                ControlJson.number(value, "sequence"), ControlJson.string(value, "idempotencyKey"), ControlJson.string(value, "disposition"),
                value.get("committedAt").isJsonNull() ? null : ControlJson.number(value, "committedAt"),
                value.get("commitRevision").isJsonNull() ? null : ControlJson.number(value, "commitRevision"),
                value.get("code").isJsonNull() ? null : ControlJson.string(value, "code"));
        validate(receipt);
        return receipt;
    }

    public static String encodeReceipt(Receipt receipt) {
        validate(receipt);
        JsonObject value = new JsonObject();
        value.addProperty("version", receipt.version());
        value.addProperty("intentDigest", receipt.intentDigest());
        value.addProperty("operation", receipt.operation());
        value.addProperty("instanceId", receipt.instanceId());
        value.addProperty("generation", receipt.generation());
        value.addProperty("sequence", receipt.sequence());
        value.addProperty("idempotencyKey", receipt.idempotencyKey());
        value.addProperty("disposition", receipt.disposition());
        value.add("committedAt", receipt.committedAt() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(receipt.committedAt()));
        value.add("commitRevision", receipt.commitRevision() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(receipt.commitRevision()));
        value.add("code", receipt.code() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(receipt.code()));
        return value.toString();
    }

    /** Association is checked only after the enclosing response proof/current authorization is verified. */
    public static void verifyReceipt(Receipt receipt, Intent intent) {
        validate(receipt);
        validate(intent);
        if (!receipt.intentDigest().equals(intentDigest(intent)) || !receipt.operation().equals(intent.operation())
                || !receipt.instanceId().equals(intent.instanceId()) || receipt.generation() != intent.generation()
                || receipt.sequence() != intent.sequence() || !receipt.idempotencyKey().equals(intent.idempotencyKey())) {
            throw ControlJson.invalid("receipt intent association");
        }
    }

    static Intent readIntent(JsonObject value) {
        ControlJson.fields(value, "version", "audience", "operation", "instanceId", "generation", "sequence", "idempotencyKey", "payloadSha256");
        Intent intent = new Intent(ControlJson.version(value), ControlJson.string(value, "audience"),
                ControlJson.string(value, "operation"), ControlJson.string(value, "instanceId"), ControlJson.number(value, "generation"),
                ControlJson.number(value, "sequence"), ControlJson.string(value, "idempotencyKey"), ControlJson.string(value, "payloadSha256"));
        validate(intent);
        return intent;
    }

    static JsonObject intentObject(Intent intent) {
        validate(intent);
        JsonObject value = new JsonObject();
        value.addProperty("version", intent.version());
        value.addProperty("audience", intent.audience());
        value.addProperty("operation", intent.operation());
        value.addProperty("instanceId", intent.instanceId());
        value.addProperty("generation", intent.generation());
        value.addProperty("sequence", intent.sequence());
        value.addProperty("idempotencyKey", intent.idempotencyKey());
        value.addProperty("payloadSha256", intent.payloadSha256());
        return value;
    }

    private static void validate(Intent intent) {
        if (intent.version() != 1 || !OPERATIONS.contains(intent.operation())) throw ControlJson.invalid("intent version or operation");
        ControlJson.audience(intent.audience());
        ControlJson.identifier(intent.instanceId());
        ControlJson.safe(intent.generation(), true);
        ControlJson.safe(intent.sequence(), true);
        ControlJson.opaque(intent.idempotencyKey());
        ControlJson.digest(intent.payloadSha256());
    }

    private static void validate(Receipt receipt) {
        if (receipt.version() != 1 || !OPERATIONS.contains(receipt.operation())
                || !Set.of("committed", "rejected", "cancelled", "expired", "unknown").contains(receipt.disposition())) throw ControlJson.invalid("receipt version or disposition");
        if (receipt.disposition().equals("cancelled") && (!receipt.operation().equals("heartbeat") || !"native-application-replaced".equals(receipt.code()))) throw ControlJson.invalid("cancelled receipt");
        ControlJson.digest(receipt.intentDigest());
        ControlJson.identifier(receipt.instanceId());
        ControlJson.safe(receipt.generation(), true);
        ControlJson.safe(receipt.sequence(), true);
        ControlJson.opaque(receipt.idempotencyKey());
        if (receipt.disposition().equals("committed")) {
            if (receipt.committedAt() == null || receipt.commitRevision() == null || receipt.code() != null) throw ControlJson.invalid("committed receipt");
            ControlJson.safe(receipt.committedAt(), false);
            ControlJson.safe(receipt.commitRevision(), false);
        } else {
            if (receipt.committedAt() != null || receipt.commitRevision() != null) throw ControlJson.invalid("uncommitted receipt");
            if (receipt.code() != null) ControlJson.identifier(receipt.code());
        }
    }

    private static void validateBody(byte[] body, int maximum) {
        if (body == null || body.length > maximum) throw ControlJson.invalid("operation body size");
        ControlJson.utf8(body);
    }
}
