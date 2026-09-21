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

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinnedTrustTest {

    private static final String SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
            + "a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    @Test
    void acceptsAnAnswerSignedByThePinnedKey() throws Exception {
        OperatorIdentity server = OperatorIdentity.generate("server.test");
        String answer = server.withAssertion(SDP);

        JwtClaims claims = IdentityUtils.validateSdp(answer, TokenTrust.pinnedTo(server.publicKey()));

        assertEquals("server.test", claims.getIssuer());
    }

    @Test
    void acceptsTheServersOwnTokenWhichCarriesNoExpiry() throws Exception {
        OperatorIdentity server = OperatorIdentity.generate("server.test");
        String answer = server.withAssertion(SDP);

        // ANY insists on an expiry, which is right for a self signed player token and wrong here
        assertThrows(InvalidJwtException.class, () -> IdentityUtils.validateSdp(answer, TokenTrust.ANY));
        IdentityUtils.validateSdp(answer, TokenTrust.pinnedTo(server.publicKey()));
    }

    @Test
    void acceptsAnyOfSeveralPinnedKeys() throws Exception {
        OperatorIdentity old = OperatorIdentity.generate("server.test");
        OperatorIdentity current = OperatorIdentity.generate("server.test");
        TokenTrust trust = TokenTrust.pinnedTo(old.publicKey(), current.publicKey());

        IdentityUtils.validateSdp(old.withAssertion(SDP), trust);
        IdentityUtils.validateSdp(current.withAssertion(SDP), trust);
    }

    @Test
    void refusesAnAnswerFromAnotherKey() throws Exception {
        String answer = OperatorIdentity.generate("server.test").withAssertion(SDP);
        TokenTrust trust = TokenTrust.pinnedTo(OperatorIdentity.generate("server.test").publicKey());

        InvalidJwtException refused = assertThrows(InvalidJwtException.class,
                () -> IdentityUtils.validateSdp(answer, trust));
        assertTrue(refused.getMessage().contains("not signed by a pinned key"), refused.getMessage());
    }

    @Test
    void refusesAnAnswerWithoutAnIdentity() throws Exception {
        TokenTrust trust = TokenTrust.pinnedTo(OperatorIdentity.generate("server.test").publicKey());

        assertThrows(JoseException.class, () -> IdentityUtils.validateSdp(SDP, trust));
    }

    @Test
    void refusesATokenNamingAnotherKeyEvenWhenThePinnedOneSignedIt() throws Exception {
        KeyPair pinned = keyPair();
        PublicKey other = keyPair().getPublic();
        String token = sign(pinned, claimsNaming(other, null));
        String answer = OperatorIdentity.fromToken(pinned, token, "server.test").withAssertion(SDP);

        JoseException refused = assertThrows(JoseException.class,
                () -> IdentityUtils.validateSdp(answer, TokenTrust.pinnedTo(pinned.getPublic())));
        assertTrue(refused.getMessage().contains("not pinned"), refused.getMessage());
    }

    @Test
    void honorsAnExpiryWhenTheTokenCarriesOne() throws Exception {
        KeyPair pinned = keyPair();
        NumericDate expired = NumericDate.now();
        expired.addSeconds(-60);
        String token = sign(pinned, claimsNaming(pinned.getPublic(), expired));
        String answer = OperatorIdentity.fromToken(pinned, token, "server.test").withAssertion(SDP);

        assertThrows(InvalidJwtException.class,
                () -> IdentityUtils.validateSdp(answer, TokenTrust.pinnedTo(pinned.getPublic())));
    }

    @Test
    void keyRoundTripsThroughItsTextForm() throws Exception {
        PublicKey key = OperatorIdentity.generate("server.test").publicKey();

        assertEquals(key, IdentityUtils.decodePublicKey(IdentityUtils.encodePublicKey(key)));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static JwtClaims claimsNaming(PublicKey key, NumericDate expiry) {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", IdentityUtils.encodePublicKey(key));
        claims.setIssuer("server.test");
        claims.setIssuedAtToNow();
        if (expiry != null) {
            claims.setExpirationTime(expiry);
        }
        return claims;
    }

    private static String sign(KeyPair signer, JwtClaims claims) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signer.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        return jws.getCompactSerialization();
    }
}
