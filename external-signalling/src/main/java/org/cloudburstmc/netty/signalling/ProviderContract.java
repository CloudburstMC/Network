package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Canonical schema is owned by docs/external-signalling; JVM and other implementations consume the same resource. */
final class ProviderContract {
    private static final JsonObject SCHEMA;
    static {
        try (var in = ProviderContract.class.getResourceAsStream("/nxs-v1.schema.json")) {
            SCHEMA = JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static void require(String document, JsonObject value) {
        JsonObject schema = SCHEMA.getAsJsonObject("$defs").getAsJsonObject(document);
        for (JsonElement field : schema.getAsJsonArray("required")) if (!value.has(field.getAsString()) || value.get(field.getAsString()).isJsonNull()) throw new IllegalArgumentException("Missing required provider field: " + field.getAsString());
        if (schema.has("properties")) for (var p : schema.getAsJsonObject("properties").entrySet()) {
            JsonObject property = p.getValue().getAsJsonObject();
            if (property.has("const") && !property.get("const").equals(value.get(p.getKey()))) throw new IllegalArgumentException("Unsupported provider field: " + p.getKey());
        }
    }
    static java.util.List<String> operations() { return SCHEMA.getAsJsonArray("x-operations").asList().stream().map(JsonElement::getAsString).toList(); }
    static Object[] contextValues(JsonObject context) {
        List<String> values = new ArrayList<>();
        for (JsonElement key : SCHEMA.getAsJsonArray("x-context-order")) values.add(context.get(key.getAsString()).getAsString());
        if (SCHEMA.has("x-optional-context-order")) for (JsonElement key : SCHEMA.getAsJsonArray("x-optional-context-order")) if (context.has(key.getAsString())) values.add(context.get(key.getAsString()).getAsString());
        return values.toArray();
    }
}
