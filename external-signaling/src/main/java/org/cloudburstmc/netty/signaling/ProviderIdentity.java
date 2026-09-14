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

import com.google.gson.JsonObject;

import java.net.URI;
import java.security.KeyPair;

/**
 * Bootstrap controllers need the public key before issuing a key-bound attachment grant.
 */
public final class ProviderIdentity {
    private ProviderIdentity() {
    }

    public static JsonObject initialize(ProviderStateStore store, URI provider) throws Exception {
        String origin = ProviderCrypto.origin(provider);
        JsonObject state = store.read();
        if (state.has("provider") && !origin.equals(state.get("provider").getAsString())) {
            throw new IllegalArgumentException("Provider state mismatch");
        }
        if (!state.has("privateKey")) {
            KeyPair pair = ProviderCrypto.generate();
            state.addProperty("provider", origin);
            state.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded()));
            state.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic()));
            state.addProperty("generation", 0);
            state.addProperty("sequence", 0);
            store.write(state);
        }
        return state.getAsJsonObject("publicKeyJwk").deepCopy();
    }
}
