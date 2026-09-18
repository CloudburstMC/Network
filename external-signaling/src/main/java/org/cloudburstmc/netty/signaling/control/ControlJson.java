package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounded candidate JSON parsing; never use this for an opaque operation's original body. */
final class ControlJson {
    static final long MAX_SAFE_INTEGER = 9007199254740991L;
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

    static void safe(long number, boolean positive) {
        if (number < (positive ? 1 : 0) || number > MAX_SAFE_INTEGER) throw invalid("safe integer");
    }

    static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid control " + reason);
    }
}
