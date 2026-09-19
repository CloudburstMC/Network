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

import io.netty.channel.embedded.EmbeddedChannel;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportIdentityBindingTest {
    @Test
    void builtInSignalingUsesTheSameOneShotCanonicalComparison() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var original = (ECPublicKey) generator.generateKeyPair().getPublic();
        var claims = new JwtClaims();
        claims.setStringClaim("cpk", Base64.getEncoder().encodeToString(original.getEncoded()));
        var player = new PlayerInfo("same-xuid", "fixture", "42", null, claims);
        ECPublicKey otherProvider = new ECPublicKey() {
            @Override
            public ECPoint getW() {
                return original.getW();
            }

            @Override
            public ECParameterSpec getParams() {
                return original.getParams();
            }

            @Override
            public String getAlgorithm() {
                return "EC";
            }

            @Override
            public String getFormat() {
                return "provider-specific";
            }

            @Override
            public byte[] getEncoded() {
                return null;
            }
        };
        assertNotEquals(original, otherProvider);
        var verifier = TransportIdentityBinding.forPlayer(player);
        assertNull(verifier.mismatch(otherProvider));
        assertNotNull(verifier.mismatch(original));
        assertNotNull(TransportIdentityBinding.forPlayer(player).mismatch(generator.generateKeyPair().getPublic()));
        assertNotNull(TransportIdentityBinding.mismatch((PlayerInfo) null, original));
    }

    @Test
    void invalidCurveAndOffCurvePointAreRejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        assertThrows(IllegalArgumentException.class,
                () -> IdentityPublicKey.canonical(generator.generateKeyPair().getPublic()));
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var key = (ECPublicKey) generator.generateKeyPair().getPublic();
        ECPublicKey invalid = new ECPublicKey() {
            @Override
            public ECPoint getW() {
                return new ECPoint(BigInteger.ZERO, BigInteger.ZERO);
            }

            @Override
            public ECParameterSpec getParams() {
                return key.getParams();
            }

            @Override
            public String getAlgorithm() {
                return "EC";
            }

            @Override
            public String getFormat() {
                return "X.509";
            }

            @Override
            public byte[] getEncoded() {
                return key.getEncoded();
            }
        };
        assertThrows(IllegalArgumentException.class, () -> IdentityPublicKey.canonical(invalid));
    }

    @Test
    void nonNetherNetChannelsContinueUsingTheirExistingAuthentication() {
        var channel = new EmbeddedChannel();
        try {
            assertNull(TransportIdentityBinding.mismatch(channel, null));
            assertNull(TransportIdentityBinding.acceptForwardedIdentity(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
