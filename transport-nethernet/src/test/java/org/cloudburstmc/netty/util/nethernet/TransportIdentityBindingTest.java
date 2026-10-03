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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportIdentityBindingTest {
    private static final String OFFER = String.join("\r\n",
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "a=fingerprint:sha-256 1B:2C:3D:4E:5F:60:71:82:93:A4:B5:C6:D7:E8:F9:0A"
                    + ":1B:2C:3D:4E:5F:60:71:82:93:A4:B5:C6:D7:E8:F9:0A",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "c=IN IP4 0.0.0.0",
            "");

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    /** A channel carrying the identity the transport validated from the offer that opened it. */
    private static Channel openedBy(KeyPair pair) throws Exception {
        String offer = new OperatorIdentity(pair.getPrivate(), pair.getPublic(), null, "proxy.test")
                .forPlayer("1234567891234678", "someone").withAssertion(OFFER);
        JwtClaims claims = IdentityUtils.validateSdp(offer, TokenTrust.ANY);
        PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"),
                claims.getClaimValueAsString("xname"), "42", null, claims);
        Channel channel = new NetherNetChildChannel(null, null, null, null);
        channel.attr(NetherNetChildChannel.PLAYER_INFO).set(player);
        TransportIdentityBinding.install(channel, TransportIdentityBinding.forPlayer(player));
        return channel;
    }

    @Test
    void acceptsAChainSignedByTheKeyThatOpenedTheTransport() throws Exception {
        KeyPair pair = keyPair();

        assertNull(TransportIdentityBinding.mismatch(openedBy(pair), pair.getPublic()));
    }

    @Test
    void refusesAChainSignedByAnyOtherKey() throws Exception {
        // A chain whose key never took part in opening this transport
        KeyPair transport = keyPair();
        KeyPair stolen = keyPair();

        String mismatch = TransportIdentityBinding.mismatch(openedBy(transport), stolen.getPublic());

        assertNotNull(mismatch);
        assertTrue(mismatch.contains("does not match"), mismatch);
    }

    @Test
    void refusesATransportThatCarriesNoBindingAtAll() throws Exception {
        // An NXS admission that bound no identity reaches us like this, and must not log anyone in
        String mismatch = TransportIdentityBinding.mismatch(
                new NetherNetChildChannel(null, null, null, null), keyPair().getPublic());

        assertNotNull(mismatch);
        assertTrue(mismatch.contains("no validated identity binding"), mismatch);
    }

    @Test
    void spendsABindingOnTheFirstLoginItDecides() throws Exception {
        KeyPair pair = keyPair();
        Channel channel = openedBy(pair);

        assertNull(TransportIdentityBinding.mismatch(channel, pair.getPublic()));
        assertNotNull(TransportIdentityBinding.mismatch(channel, pair.getPublic()),
                "a second chain on the same transport has nothing left to bind to");
    }

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
