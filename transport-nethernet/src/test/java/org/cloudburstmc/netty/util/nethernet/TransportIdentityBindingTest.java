package org.cloudburstmc.netty.util.nethernet;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TransportIdentityBindingTest {
    @Test
    void builtInSignallingUsesTheSameOneShotCanonicalComparison() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var original = (ECPublicKey) generator.generateKeyPair().getPublic();
        var claims = new JwtClaims();
        claims.setStringClaim("cpk", Base64.getEncoder().encodeToString(original.getEncoded()));
        var player = new PlayerInfo("same-xuid", "fixture", "42", null, claims);
        ECPublicKey otherProvider = new ECPublicKey() {
            public ECPoint getW() { return original.getW(); }
            public ECParameterSpec getParams() { return original.getParams(); }
            public String getAlgorithm() { return "EC"; }
            public String getFormat() { return "provider-specific"; }
            public byte[] getEncoded() { return null; }
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
        assertThrows(IllegalArgumentException.class, () -> IdentityPublicKey.canonical(generator.generateKeyPair().getPublic()));
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var key = (ECPublicKey) generator.generateKeyPair().getPublic();
        ECPublicKey invalid = new ECPublicKey() {
            public ECPoint getW() { return new ECPoint(java.math.BigInteger.ZERO, java.math.BigInteger.ZERO); }
            public ECParameterSpec getParams() { return key.getParams(); }
            public String getAlgorithm() { return "EC"; }
            public String getFormat() { return "X.509"; }
            public byte[] getEncoded() { return key.getEncoded(); }
        };
        assertThrows(IllegalArgumentException.class, () -> IdentityPublicKey.canonical(invalid));
    }

    @Test
    void nonNetherNetChannelsContinueUsingTheirExistingAuthentication() {
        var channel = new EmbeddedChannel();
        try {
            assertNull(TransportIdentityBinding.mismatch(channel, null));
            assertNull(TransportIdentityBinding.acceptForwardedIdentity(channel));
        } finally { channel.finishAndReleaseAll(); }
    }
}
