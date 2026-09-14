package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/** Bounded draft-control JSON parsing; never use this for an opaque operation's original body. */
final class ControlJson {
    private ControlJson() { }

    static JsonObject parse(String wire, int maximum) {
        if (wire == null || wire.length() > maximum || wire.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid("size");
        if (wire.startsWith("\uFEFF")) throw invalid("JSON byte order mark");
        try (JsonReader reader = new JsonReader(new StringReader(wire))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid("object or trailing data");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException failure) {
            throw new IllegalArgumentException("Invalid control JSON", failure);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw invalid("depth");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (names.size() >= 32 || !names.add(name)) throw invalid("duplicate field or object size");
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray values = new JsonArray();
                while (reader.hasNext()) {
                    if (values.size() >= 32) throw invalid("array size");
                    values.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield values;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String token = reader.nextString();
                if (!token.matches("0|[1-9][0-9]{0,15}")) throw invalid("integer token");
                long number = Long.parseLong(token);
                safe(number, false);
                yield new JsonPrimitive(number);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw invalid("value");
        };
    }

    static void fields(JsonObject object, String... expected) {
        if (!object.keySet().equals(Set.of(expected))) throw invalid("fields");
    }

    static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(field);
        return value.getAsString();
    }

    static long number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid(field);
        return value.getAsLong();
    }

    static int version(JsonObject object) {
        if (number(object, "version") != 1) throw invalid("version");
        return 1;
    }

    static JsonObject object(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw invalid(field);
        return value.getAsJsonObject();
    }

    static List<String> strings(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) throw invalid(field);
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid(field);
            result.add(item.getAsString());
        }
        return List.copyOf(result);
    }

    static void safe(long number, boolean positive) {
        if (number < (positive ? 1 : 0) || number > ControlFrameCodec.MAX_SAFE_INTEGER) throw invalid("safe integer");
    }

    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw invalid("identifier");
    }

    static void opaque(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) throw invalid("opaque identifier");
    }

    static void audience(String value) {
        if (value == null || value.length() > 2048 || URI.create(value).getPort() > 65535
                || !value.equals(ProviderCrypto.origin(URI.create(value)))) throw invalid("audience");
    }

    static byte[] base64(String value, int maximum, boolean emptyAllowed) {
        if (value == null || value.length() > (maximum * 4L + 2) / 3
                || !(emptyAllowed ? value.matches("[A-Za-z0-9_-]*") : value.matches("[A-Za-z0-9_-]+"))) throw invalid("base64url");
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length > maximum || !ProviderCrypto.base64(bytes).equals(value)) throw invalid("base64url");
        return bytes;
    }

    static void digest(String value) {
        if (base64(value, 32, false).length != 32) throw invalid("digest");
    }

    static void utf8(byte[] value) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value));
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw invalid("UTF-8");
        }
    }

    static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid control " + reason);
    }
}
