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

package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.lang.JoseException;
import org.jose4j.lang.UnresolvableKeyException;

import java.security.Key;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;

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
    TokenTrust MINECRAFT_AUTH = new TokenTrust() {
        @Override
        public JwtClaims claims(Identity identity) throws Exception {
            return IdentityUtils.validateIdentity(identity).getJwtClaims();
        }

        @Override
        public void prepare() {
            IdentityUtils.prefetchKeys();
        }
    };

    /**
     * Reads the claims without checking who signed the token, for peers that cannot present a
     * Minecraft-issued one, such as another proxy in the same fleet.
     * <p>
     * The {@code cpk} binding still applies, so the peer must hold the key its token names, and the
     * token must carry an expiry and be within it. Neither bounds what the token <i>says</i>: the
     * peer signs its own, so {@code xid}, {@code xname} and the expiry itself are whatever it chose.
     * Nothing here establishes <i>who</i> the peer is, so pair it with an identity check of your
     * own.
     */
    TokenTrust ANY = identity -> Unverified.CONSUMER.processToClaims(identity.assertion().token());

    /**
     * Trusts only a token signed by one of {@code keys} that also names that key as its
     * {@code cpk}. This is how a client confirms a server it knows: pin the public key of the
     * identity the server answers with. A server's own token carries no expiry and none is
     * required here, the key is the anchor; an expiry that is present still has to be current.
     *
     * @param keys The public keys to accept, more than one across a rotation
     * @return The trust
     */
    static TokenTrust pinnedTo(PublicKey... keys) {
        return new Pinned(List.of(keys));
    }

    /**
     * @param identity The identity taken from the offer
     * @return The token's claims, which must include {@code cpk}
     * @throws Exception If the token is not trusted
     */
    JwtClaims claims(Identity identity) throws Exception;

    /**
     * Does whatever makes the first {@link #claims} call fast, such as fetching keys. Called once
     * when signaling binds, off the event loop, and expected to swallow its own failures.
     */
    default void prepare() {
    }

    /** Accepts a token signed by, and naming, one of a fixed set of keys. */
    final class Pinned implements TokenTrust {
        private static final String ALG = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384;

        private final List<PublicKey> keys;
        private final JwtConsumer consumer;

        Pinned(List<PublicKey> keys) {
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("No key to pin");
            }
            this.keys = keys;
            this.consumer = new JwtConsumerBuilder()
                    .setVerificationKeyResolver(this::resolve)
                    .setJwsAlgorithmConstraints(new AlgorithmConstraints(ConstraintType.PERMIT, ALG))
                    .setSkipDefaultAudienceValidation()
                    .build();
        }

        @Override
        public JwtClaims claims(Identity identity) throws Exception {
            JwtClaims claims = consumer.processToClaims(identity.assertion().token());
            String cpk = claims.getClaimValueAsString("cpk");
            if (cpk == null || !holds(IdentityUtils.decodePublicKey(cpk))) {
                throw new JoseException("The token names a key that is not pinned");
            }
            return claims;
        }

        // The tokens carry no key id, so the pinned keys are tried in turn
        private Key resolve(JsonWebSignature jws, List<JsonWebStructure> nesting) throws UnresolvableKeyException {
            if (!ALG.equals(jws.getAlgorithmHeaderValue())) {
                throw new UnresolvableKeyException("The token is not signed with " + ALG);
            }
            for (PublicKey key : keys) {
                jws.setKey(key);
                try {
                    if (jws.verifySignature()) {
                        return key;
                    }
                } catch (JoseException e) {
                    // A key the algorithm cannot take is just not the signer
                }
            }
            throw new UnresolvableKeyException("The token was not signed by a pinned key");
        }

        private boolean holds(PublicKey key) {
            byte[] encoded = key.getEncoded();
            for (PublicKey pinned : keys) {
                if (Arrays.equals(pinned.getEncoded(), encoded)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Holder so the shared consumer is built once. */
    final class Unverified {
        static final JwtConsumer CONSUMER = new JwtConsumerBuilder()
                // Who signed it is not checked, but an expiry still has to be present and current
                .setDisableRequireSignature()
                .setSkipSignatureVerification()
                .setRequireExpirationTime()
                // Who the token is addressed to says nothing about whether it is trusted
                .setSkipDefaultAudienceValidation()
                .build();

        private Unverified() {
        }
    }
}
