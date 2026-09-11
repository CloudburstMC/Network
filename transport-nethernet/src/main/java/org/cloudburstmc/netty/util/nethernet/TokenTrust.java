package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

/**
 * Decides whether the token in an offer's identity assertion is trusted.
 * <p>
 * This is only the first half of validating an offer. Whichever policy is chosen, the assertion's
 * detached JWS over the SDP fingerprints is still verified against the token's {@code cpk}, which
 * is what ties the identity to the DTLS certificate the peer presents.
 *
 * @see IdentityUtils#validateSdp(String, TokenTrust)
 */
@FunctionalInterface
public interface TokenTrust {

    /**
     * Verifies the token against Minecraft's authorization service, which is what a retail client
     * presents. This is the default.
     */
    TokenTrust MINECRAFT_AUTH = identity -> IdentityUtils.validateIdentity(identity).getJwtClaims();

    /**
     * Reads the claims without checking who signed the token, for peers that cannot present a
     * Minecraft-issued one, such as another proxy in the same fleet.
     * <p>
     * The {@code cpk} binding still applies, so the peer must hold the key its token names, but
     * nothing here establishes <i>who</i> the peer is. Pair it with an identity check of your own.
     */
    TokenTrust ANY = identity -> Unverified.CONSUMER.processToClaims(identity.assertion().token());

    /**
     * @param identity The identity taken from the offer
     * @return The token's claims, which must include {@code cpk}
     * @throws Exception If the token is not trusted
     */
    JwtClaims claims(Identity identity) throws Exception;

    /** Holder so the shared consumer is built once. */
    final class Unverified {
        static final JwtConsumer CONSUMER = new JwtConsumerBuilder()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .build();

        private Unverified() {
        }
    }
}
