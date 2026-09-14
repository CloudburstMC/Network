/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling;

import com.google.gson.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Canonical schema is owned by docs/external-signalling; JVM and other implementations consume the same resource.
 */
final class ProviderContract {
    private static final JsonObject SCHEMA;

    static {
        try (var in = ProviderContract.class.getResourceAsStream("/nxs-v1.schema.json")) {
            SCHEMA = JsonParser.parseReader(
                    new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static void require(String document, JsonObject value) {
        JsonObject schema = SCHEMA.getAsJsonObject("$defs").getAsJsonObject(document);
        for (JsonElement field : schema.getAsJsonArray("required")) {
            if (!value.has(field.getAsString()) || value.get(field.getAsString()).isJsonNull()) {
                throw new IllegalArgumentException("Missing required provider field: " + field.getAsString());
            }
        }

        if (schema.has("properties")) {
            for (var p : schema.getAsJsonObject("properties").entrySet()) {
                JsonObject property = p.getValue().getAsJsonObject();
                if (property.has("const") && !property.get("const").equals(value.get(p.getKey()))) {
                    throw new IllegalArgumentException("Unsupported provider field: " + p.getKey());
                }
            }
        }
    }

    static List<String> operations() {
        return SCHEMA.getAsJsonArray("x-operations").asList().stream().map(JsonElement::getAsString).toList();
    }

    static Object[] contextValues(JsonObject context) {
        List<String> values = new ArrayList<>();
        for (JsonElement key : SCHEMA.getAsJsonArray("x-context-order")) {
            values.add(context.get(key.getAsString()).getAsString());
        }

        if (SCHEMA.has("x-optional-context-order")) {
            for (JsonElement key : SCHEMA.getAsJsonArray("x-optional-context-order")) {
                if (context.has(key.getAsString())) {
                    values.add(context.get(key.getAsString()).getAsString());
                }
            }
        }

        return values.toArray();
    }
}
