package org.cloudburstmc.netty.signaling.admission;

import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;

/** Authenticated peer context from a ticket or the provider's live TLS socket. */
interface AdmissionContext {
    String tokenId();

    String localUfrag();

    String localPassword();

    String remoteUfrag();

    String remoteDescription();

    long expiresAt();

    String networkId();

    String identityBindingHex();

    String keyId();

    IdentityKeyVerifier identityVerifier();
}
