package org.cloudburstmc.netty.channel.nethernet.admission;

import io.netty.util.AttributeKey;

/**
 * Token-authenticated context bound to the certificate checked by native DTLS. No credentials.
 */
public record AdmissionPrincipal(String ticketId, String networkId, String callerContextHash, String keyId) {
    public static final AttributeKey<AdmissionPrincipal> KEY =
            AttributeKey.valueOf(AdmissionPrincipal.class, "principal");
}
