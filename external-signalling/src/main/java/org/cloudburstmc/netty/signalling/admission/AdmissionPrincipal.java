package org.cloudburstmc.netty.signalling.admission;

import io.netty.util.AttributeKey;

/**
 * Token-authenticated context bound to the certificate checked by native DTLS. No credentials.
 */
public record AdmissionPrincipal(String ticketId, String networkId, String identityBindingHex, String keyId) {
    public static final AttributeKey<AdmissionPrincipal> KEY =
            AttributeKey.valueOf(AdmissionPrincipal.class, "principal");
}
