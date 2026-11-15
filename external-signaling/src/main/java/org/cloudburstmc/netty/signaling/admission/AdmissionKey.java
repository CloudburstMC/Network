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

package org.cloudburstmc.netty.signaling.admission;

/**
 * A provider-issued admission key epoch. Tokens under it are accepted until {@code retireAfter}.
 */
public record AdmissionKey(String keyId, String secret, long retireAfter) {
    public AdmissionKey(String keyId, String secret) {
        this(keyId, secret, Long.MAX_VALUE);
    }

    /**
     * A secret is 43 base64url characters encoding 32 random bytes. Derivations use the characters'
     * bytes, never the decoded value.
     */
    public static boolean validSecret(String secret) {
        return secret != null && secret.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
    }

    @Override
    public String toString() {
        return "AdmissionKey[keyId=" + keyId + "]";
    }
}
