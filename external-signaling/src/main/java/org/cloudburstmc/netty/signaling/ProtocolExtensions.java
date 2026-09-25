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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * Bounded, authenticated metadata whose optional contents are interpreted by applications.
 */
public final class ProtocolExtensions {
    public static final int MAX_BYTES = 16_384;
    public static final int MAX_ENTRIES = 16;

    private ProtocolExtensions() {
    }

    public static void validate(JsonObject document) {
        if (!document.has("extensions")) {
            return;
        }

        JsonElement value = document.get("extensions");
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Invalid extensions object");
        }

        JsonObject extensions = value.getAsJsonObject();
        if (extensions.size() > MAX_ENTRIES
                || extensions.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Extensions exceed limits");
        }

        for (var entry : extensions.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*){1,7}") || entry.getKey().length() > 128
                    || !entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException("Invalid extension namespace");
            }

            JsonObject extension = entry.getValue().getAsJsonObject();
            if (!extension.has("version") || !extension.get("version").isJsonPrimitive()
                    || !extension.getAsJsonPrimitive("version").isNumber() || !extension.get("version").getAsString()
                    .matches("[1-9][0-9]{0,8}")
                    || !extension.has("critical") || !extension.get("critical").isJsonPrimitive()
                    || !extension.getAsJsonPrimitive("critical").isBoolean() || !extension.has("data")
                    || !extension.get("data").isJsonObject()) {
                throw new IllegalArgumentException("Invalid extension envelope");
            }

            // This core implements no mandatory extension. Applications cannot silently weaken core checks.
            if (extension.get("critical").getAsBoolean()) {
                throw new IllegalArgumentException("Unsupported required extension: " + entry.getKey());
            }
        }
    }

    public static JsonObject copy(JsonObject document) {
        validate(document);
        return document.has("extensions") ? document.getAsJsonObject("extensions").deepCopy() : new JsonObject();
    }
}
