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
    /** Extract the original root-property bytes after full syntax and duplicate validation. */
    static String rootProperty(String wire, String property, int maximum) {
        parse(wire, maximum);
        int depth = 0;
        for (int index = 0; index < wire.length(); index++) {
            char token = wire.charAt(index);
            if (token == '{' || token == '[') depth++;
            else if (token == '}' || token == ']') depth--;
            else if (token == '"') {
                int start = index, end = stringEnd(wire, index);
                String key = depth == 1 ? JsonParser.parseString(wire.substring(start, end + 1)).getAsString() : null;
                index = end;
                int colon = whitespace(wire, end + 1);
                if (depth != 1 || !property.equals(key) || colon >= wire.length() || wire.charAt(colon) != ':') continue;
                start = whitespace(wire, colon + 1);
                char first = wire.charAt(start);
                if (first == '"') return wire.substring(start, stringEnd(wire, start) + 1);
                if (first != '{' && first != '[') {
                    end = start;
                    while (end < wire.length() && wire.charAt(end) != ',' && wire.charAt(end) != '}') end++;
                    return wire.substring(start, end);
                }
                int nested = 0;
                for (end = start; end < wire.length(); end++) {
                    char current = wire.charAt(end);
                    if (current == '"') end = stringEnd(wire, end);
                    else if (current == '{' || current == '[') nested++;
                    else if ((current == '}' || current == ']') && --nested == 0) return wire.substring(start, end + 1);
                }
                throw invalid();
            }
        }
        return null;
    }
    private static int stringEnd(String wire, int start) {
        for (int index = start + 1; index < wire.length(); index++) {
            if (wire.charAt(index) == '\\') index++;
            else if (wire.charAt(index) == '"') return index;
        }
        throw invalid();
    }
    private static int whitespace(String wire, int start) {
        while (start < wire.length() && " \t\r\n".indexOf(wire.charAt(start)) >= 0) start++;
        return start;
    }
    static String string(JsonObject object, String name) {
        var value = object.get(name); if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return value.getAsString();
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid controlled application state"); }
    private ControlledProviderJson() { }
}
