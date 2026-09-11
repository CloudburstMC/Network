package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenTrustTest {

    private static final String FINGERPRINT = "a=fingerprint:sha-256 "
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(payload);
        jws.setKey(pair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        return jws.getCompactSerialization();
    }

    /**
     * An offer signed by {@code tokenKey}, whose token names {@code namedKey} as the cpk. When the
     * two differ the assertion is claiming a key it does not hold.
     */
    private static String offer(KeyPair tokenKey, KeyPair namedKey) throws Exception {
        return offer(tokenKey, namedKey, null);
    }

    /** The same offer, with the token addressed to {@code audience} when there is one. */
    private static String offer(KeyPair tokenKey, KeyPair namedKey, String audience) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(namedKey.getPublic().getEncoded()));
        claims.setClaim("xid", "2535000000000000");
        claims.setClaim("xname", "Probe");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(5);
        if (audience != null) {
            claims.setAudience(audience);
        }
        String token = sign(tokenKey, claims.toJson());

        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" + FINGERPRINT + "\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
        String[] parts = sign(tokenKey, IdentityUtils.getCanonicalFingerprintJson(sdp)).split("\\.");

        Identity identity = new Identity(new Identity.Idp("example.test", "default"),
                new Identity.Assertion(token, parts[0] + ".." + parts[2]));
        return sdp.replace("m=application", "a=identity:" + identity.toBase64() + "\r\nm=application");
    }

    /** The same offer, with a token that expired an hour ago. */
    private static String expiredOffer(KeyPair pair) throws Exception {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        claims.setClaim("xid", "2535000000000000");
        claims.setIssuedAt(NumericDate.fromSeconds(NumericDate.now().getValue() - 7200));
        claims.setExpirationTime(NumericDate.fromSeconds(NumericDate.now().getValue() - 3600));
        String token = sign(pair, claims.toJson());

        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" + FINGERPRINT + "\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
        String[] parts = sign(pair, IdentityUtils.getCanonicalFingerprintJson(sdp)).split("\\.");

        Identity identity = new Identity(new Identity.Idp("example.test", "default"),
                new Identity.Assertion(token, parts[0] + ".." + parts[2]));
        return sdp.replace("m=application", "a=identity:" + identity.toBase64() + "\r\nm=application");
    }

    @Test
    void anyRejectsAnExpiredToken() throws Exception {
        String offer = expiredOffer(keyPair());

        assertThrows(Exception.class, () -> IdentityUtils.validateSdp(offer, TokenTrust.ANY));
    }

    @Test
    void anyAcceptsASelfSignedToken() throws Exception {
        KeyPair pair = keyPair();
        JwtClaims claims = IdentityUtils.validateSdp(offer(pair, pair), TokenTrust.ANY);

        assertEquals("2535000000000000", claims.getClaimValueAsString("xid"));
        assertEquals("Probe", claims.getClaimValueAsString("xname"));
    }

    @Test
    void anyAcceptsATokenAddressedToTheAuthService() throws Exception {
        KeyPair pair = keyPair();
        // What a retail client presents. Demanding an audience turns every real client away
        String offer = offer(pair, pair, "api://auth-minecraft-services/multiplayer");

        JwtClaims claims = IdentityUtils.validateSdp(offer, TokenTrust.ANY);

        assertEquals("Probe", claims.getClaimValueAsString("xname"));
    }

    @Test
    void minecraftAuthRejectsASelfSignedToken() throws Exception {
        KeyPair pair = keyPair();
        String offer = offer(pair, pair);

        // Not signed by the auth service, so the key cannot be resolved
        assertThrows(Exception.class, () -> IdentityUtils.validateSdp(offer, TokenTrust.MINECRAFT_AUTH));
    }

    @Test
    void anyStillEnforcesTheCpkBinding() throws Exception {
        // The token names a key the signer does not hold, so the fingerprint JWS cannot verify
        String offer = offer(keyPair(), keyPair());

        Exception e = assertThrows(Exception.class, () -> IdentityUtils.validateSdp(offer, TokenTrust.ANY));
        assertTrue(e.getMessage().contains("Fingerprint") || e.getMessage().contains("signature"),
                "expected a fingerprint binding failure, got: " + e.getMessage());
    }

    @Test
    void handsBackTheKeyThePeerProvedItHolds() throws Exception {
        KeyPair pair = keyPair();
        JwtClaims claims = IdentityUtils.validateSdp(offer(pair, pair), TokenTrust.ANY);

        PlayerInfo player = new PlayerInfo(claims.getClaimValueAsString("xid"), "Probe", "42", null, claims);
        // A consumer compares this against whatever identity its own login step presents
        assertArrayEquals(pair.getPublic().getEncoded(), player.clientPublicKey().getEncoded());
    }

    @Test
    void defaultsToMinecraftAuth() throws Exception {
        KeyPair pair = keyPair();
        String offer = offer(pair, pair);

        assertThrows(Exception.class, () -> IdentityUtils.validateSdp(offer));
    }
}
