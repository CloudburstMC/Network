package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Bounded owned application JSON. Numbers remain lexical until a typed field validates them. */
final class ControlledProviderJson {
    static JsonObject parse(String wire, int maximum) {
        if (wire == null || wire.startsWith("\uFEFF") || wire.getBytes(StandardCharsets.UTF_8).length > maximum) throw invalid();
        try (var reader = new JsonReader(new StringReader(wire))) {
            reader.setStrictness(Strictness.STRICT);
            var result = read(reader, 0);
            if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return result.getAsJsonObject();
        } catch (IOException | IllegalStateException error) { throw invalid(); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw invalid();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var object = new JsonObject(); var names = new HashSet<String>();
                while (reader.hasNext()) {
                    String name = reader.nextName(); if (!names.add(name)) throw invalid(); object.add(name, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var array = new JsonArray(); while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid();
        };
    }
    static long number(JsonObject object, String name) {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.toString().matches("0|[1-9][0-9]{0,15}")) throw invalid();
        long number = Long.parseLong(value.toString()); if (number > 9007199254740991L) throw invalid(); return number;
    }
    static String string(JsonObject object, String name) {
        var value = object.get(name); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid controlled application state"); }
    private ControlledProviderJson() { }
}
