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

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Untrusted fields from one incoming ICE attempt. Packet bytes remain native.
 */
public record AdmissionRequest(String localUfrag, String remoteUfrag, InetSocketAddress address) {
    public AdmissionRequest {
        if (!iceString(localUfrag, 4, 256) || !iceString(remoteUfrag, 4, 256)) {
            throw new IllegalArgumentException("ICE username fragments");
        }
        Objects.requireNonNull(address);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("Resolved source address required");
        }
    }

    /**
     * Whether a peer-supplied ICE credential is {@code min} to {@code max} ice-chars
     * (RFC 8445: ALPHA / DIGIT / '+' / '/'). Null and out-of-range values are rejected.
     */
    static boolean iceString(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '+' && c != '/') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "AdmissionRequest[redacted]";
    }
}
