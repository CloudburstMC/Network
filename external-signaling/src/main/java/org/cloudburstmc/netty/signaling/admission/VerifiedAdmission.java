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

import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Trusted validator output. Never log credentials or reconstructed SDP.
 */
public record VerifiedAdmission(String tokenId, String localUfrag, String localPassword,
                                String remoteUfrag, String remotePassword, String remoteFingerprint,
                                int remoteSctpPort, int remoteMaxMessageSize, long expiresAt,
                                String networkId, String identityBindingHex, String keyId, IdentityKeyVerifier identityVerifier) implements AdmissionContext {
    /**
     * The envelope bounds the client password: 186 bytes less a 12 byte nonce, a 16 byte tag and
     * the 67 byte fixed prefix leaves 91. Narrower than the 256 ICE itself permits.
     */
    static final int MAX_CLIENT_PASSWORD = 91;

    private static final Pattern TOKEN_ID = Pattern.compile("[0-9a-f]{32}");

    public VerifiedAdmission {
        Objects.requireNonNull(identityVerifier, "identityVerifier");
        if (tokenId == null || !TOKEN_ID.matcher(tokenId).matches()) {
            throw new IllegalArgumentException("tokenId");
        }

        if (!AdmissionRequest.iceString(localUfrag, 4, 256) || !AdmissionRequest.iceString(remoteUfrag, 4, 256)
                || !AdmissionRequest.iceString(localPassword, 22, 256)
                || !AdmissionRequest.iceString(remotePassword, 22, MAX_CLIENT_PASSWORD)) {
            throw new IllegalArgumentException("ICE identity");
        }

        if (!DtlsFingerprint.valid(remoteFingerprint)) {
            throw new IllegalArgumentException("DTLS fingerprint");
        }

        if (remoteSctpPort < 1 || remoteSctpPort > 65535 || remoteMaxMessageSize < 1
                || remoteMaxMessageSize > NetherNetFrameDecoder.MESSAGE_LIMIT) {
            throw new IllegalArgumentException("SCTP parameters");
        }
    }

    public String remoteDescription() {
        return "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n" +
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=setup:actpass\r\n"
                +
                "a=ice-ufrag:" + remoteUfrag + "\r\na=ice-pwd:" + remotePassword + "\r\na=fingerprint:"
                + remoteFingerprint +
                "\r\na=sctp-port:" + remoteSctpPort + "\r\na=max-message-size:" + remoteMaxMessageSize + "\r\n";
    }

    @Override
    public String toString() {
        return "VerifiedAdmission[tokenId=" + tokenId + "]";
    }
}
