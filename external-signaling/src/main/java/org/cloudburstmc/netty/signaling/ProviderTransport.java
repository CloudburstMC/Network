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

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Transport boundary. Provider code performs no native allocation or game packet handling.
 */
public interface ProviderTransport {
    enum ApplyResult {PENDING, APPLIED, REJECTED}

    /**
     * Existing PublishHostProfileRequest, exported from actual bound native metadata.
     */
    CompletionStage<JsonObject> hostProfile();

    /**
     * Atomic snapshot; completion means every supplied key is persisted and usable.
     */
    CompletionStage<Void> installTicketKeys(List<TicketKey> keys);

    /**
     * Apply serving/draining/closed background state before acknowledging its revision.
     */
    CompletionStage<ApplyResult> applyState(String state);

    /**
     * Whether this integration can observe the application join/rejection boundary.
     */
    default boolean supportsGameOutcomes() {
        return false;
    }

    /**
     * Bounded ticket-correlated transport and application observations.
     */
    List<JsonObject> pollEvents();

    CompletionStage<Void> drain();

    CompletionStage<Void> close();

    record TicketKey(String keyId, String secret, long notBefore, long retireAfter) {
        public TicketKey(String keyId, String secret) {
            this(keyId, secret, 0, Long.MAX_VALUE);
        }

        @Override
        public String toString() {
            return "TicketKey[keyId=" + keyId + "]";
        }
    }
}
