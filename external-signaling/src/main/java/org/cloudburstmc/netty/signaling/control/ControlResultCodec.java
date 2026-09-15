package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** An immutable receipt plus separately delivered application bytes. Does not grant authority or implement application state. */
public final class ControlResultCodec {
    public static final int MAX_ENVELOPE_BYTES = 65536;
    public static final int MAX_BODY_BYTES = 45056;
    public static final int MAX_BODY_DEPTH = 16;

    /** The encoded body is immutable and may contain a one-time secret: never journal or log it. */
    public record Result(int version, ControlLifecycleCodec.Receipt receipt, String body) {
        public Result {
            if (version != 1 || receipt == null) throw ControlJson.invalid("result shape");
            ControlLifecycleCodec.decodeReceipt(ControlLifecycleCodec.encodeReceipt(receipt));
            validateBody(ControlJson.base64(body, MAX_BODY_BYTES, false));
            if (!receipt.disposition().equals("committed") && !body.equals("e30")) throw ControlJson.invalid("uncommitted result body");
        }
        public byte[] bodyBytes() { return ControlJson.base64(body, MAX_BODY_BYTES, false); }
        @Override public String toString() { return "ControlOperationResult[receipt=" + receipt + ", body=redacted]"; }
    }
    private ControlResultCodec() { }

    public static Result create(ControlLifecycleCodec.Receipt receipt, byte[] originalBody) {
        if (originalBody == null || originalBody.length > MAX_BODY_BYTES) throw ControlJson.invalid("result body size");
        byte[] owned = originalBody.clone(); validateBody(owned);
        return new Result(1, receipt, ProviderCrypto.base64(owned));
    }
    /** Syntax/ownership only. Authenticate the enclosing frame or exact HTTPS exchange before using this result. */
    public static Result decode(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(value, "version", "receipt", "body");
        return new Result(ControlJson.version(value), ControlLifecycleCodec.decodeReceipt(ControlJson.object(value, "receipt").toString()),
                ControlJson.string(value, "body"));
    }
    public static String encode(Result result) {
        JsonObject value = new JsonObject(); value.addProperty("version", result.version());
        value.add("receipt", ControlJson.parse(ControlLifecycleCodec.encodeReceipt(result.receipt()), ControlLifecycleCodec.MAX_INTENT_BYTES));
        value.addProperty("body", result.body()); String wire = value.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) throw ControlJson.invalid("result envelope size");
        return wire;
    }
    /** The existing receipt/intent domain and association are unchanged. Does not grant authority. */
    public static Result verify(Result result, ControlLifecycleCodec.Intent intent) {
        ControlLifecycleCodec.verifyReceipt(result.receipt(), intent); return result;
    }

    private static void validateBody(byte[] body) {
        if (body.length > MAX_BODY_BYTES) throw ControlJson.invalid("result body size");
        ControlJson.utf8(body); String wire = new String(body, StandardCharsets.UTF_8);
        if (wire.startsWith("\uFEFF")) throw ControlJson.invalid("result body BOM");
        try (JsonReader reader = new JsonReader(new StringReader(wire))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw ControlJson.invalid("result body object");
            readBody(reader, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw ControlJson.invalid("result body trailing data");
        } catch (IOException | IllegalStateException failure) { throw ControlJson.invalid("result body JSON"); }
    }
    private static void readBody(JsonReader reader, int depth) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                if (depth > MAX_BODY_DEPTH) throw ControlJson.invalid("result body depth");
                reader.beginObject(); Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    if (!names.add(reader.nextName())) throw ControlJson.invalid("result body duplicate field");
                    readBody(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                if (depth > MAX_BODY_DEPTH) throw ControlJson.invalid("result body depth");
                reader.beginArray(); while (reader.hasNext()) readBody(reader, depth + 1); reader.endArray();
            }
            // Application numbers may be negative, fractional or exponential. Preserve their original bytes.
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw ControlJson.invalid("result body value");
        }
    }
}
