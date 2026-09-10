package org.cloudburstmc.netty.signalling.admission;

/**
 * Trusted validator output. Never log credentials or reconstructed SDP.
 */
public record VerifiedAdmission(String tokenId, String localUfrag, String localPassword,
                                String remoteUfrag, String remotePassword, String remoteFingerprint,
                                int remoteSctpPort, int remoteMaxMessageSize, long expiresAt,
                                String networkId, String callerContextHash, String keyId) {
    public VerifiedAdmission {
        if (tokenId == null || !tokenId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("tokenId");
        }
        if (!AdmissionRequest.iceString(localUfrag, 4, 256) || !AdmissionRequest.iceString(remoteUfrag, 4, 256) ||
                !AdmissionRequest.iceString(localPassword, 22, 256) || !AdmissionRequest.iceString(remotePassword, 22,
                256)) {
            throw new IllegalArgumentException("ICE identity");
        }
        if (remoteFingerprint == null || !remoteFingerprint.matches("sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}")) {
            throw new IllegalArgumentException("DTLS fingerprint");
        }
        if (remoteSctpPort < 1 || remoteSctpPort > 65535 || remoteMaxMessageSize < 1 || remoteMaxMessageSize > 262144) {
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
