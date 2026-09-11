package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.util.nethernet.Identity;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Builds offers that carry a self signed identity, for tests that are not about the trust anchor. */
final class TestOffers {

    private static final String SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
            + "a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    private TestOffers() {
    }

    static String selfSigned() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = generator.generateKeyPair();

        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        claims.setClaim("xid", "2535000000000000");
        claims.setClaim("xname", "Probe");
        claims.setIssuedAtToNow();

        String token = sign(pair, claims.toJson());
        String[] parts = sign(pair, IdentityUtils.getCanonicalFingerprintJson(SDP)).split("\\.");

        Identity identity = new Identity(new Identity.Idp("example.test", "default"),
                new Identity.Assertion(token, parts[0] + ".." + parts[2]));
        return SDP.replace("m=application", "a=identity:" + identity.toBase64() + "\r\nm=application");
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(payload);
        jws.setKey(pair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        return jws.getCompactSerialization();
    }
}
