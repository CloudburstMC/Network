package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

public final class ClientAssertionFixtures {
    public static final String ISSUER = "https://issuer.test/";
    public static final String AUDIENCE = "test-game-server";
    public static final String DIGEST = "AB:".repeat(31) + "AB";
    public static final String CANONICAL = "{\"fingerprint\":[{\"algorithm\":\"sha-256\",\"digest\":\"" + DIGEST + "\"}]}";
    public static final KeyPair ISSUER_KEY = keyPair("RSA", null);
    public static final KeyPair CLIENT_KEY = keyPair("EC", "secp384r1");

    private ClientAssertionFixtures() { }

    public static KeyPair keyPair(String algorithm, String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            if (curve != null) generator.initialize(new ECGenParameterSpec(curve));
            else generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static JwtClaims claims() {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(ISSUER);
        claims.setAudience(AUDIENCE);
        claims.setSubject("player-1");
        claims.setIssuedAt(NumericDate.fromSeconds(Instant.now().getEpochSecond() - 1));
        claims.setExpirationTime(NumericDate.fromSeconds(Instant.now().getEpochSecond() + 3600));
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(CLIENT_KEY.getPublic().getEncoded()));
        claims.setClaim("xid", "1234567890");
        return claims;
    }

    public static ClientAssertionValidator validator() {
        return new ClientAssertionValidator(ISSUER_KEY.getPublic(), ISSUER, AUDIENCE);
    }

    public static String validOffer() throws Exception {
        return offer(claims());
    }

    public static String offer(JwtClaims claims) throws Exception {
        return offer(signedToken(claims), fingerprints(CANONICAL, CLIENT_KEY.getPrivate(),
                AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384));
    }

    public static String signedToken(JwtClaims claims) throws Exception {
        return sign(claims.toJson(), ISSUER_KEY.getPrivate(), AlgorithmIdentifiers.RSA_USING_SHA256);
    }

    public static String sign(String payload, Key key, String algorithm) throws Exception {
        JsonWebSignature signature = new JsonWebSignature();
        signature.setAlgorithmHeaderValue(algorithm);
        signature.setKey(key);
        signature.setPayload(payload);
        return signature.getCompactSerialization();
    }

    public static String fingerprints(String payload, Key key, String algorithm) throws Exception {
        String[] signed = sign(payload, key, algorithm).split("\\.", -1);
        return signed[0] + ".." + signed[2];
    }

    public static String offer(String token, String fingerprints) {
        String identity = new Identity(new Identity.Idp("issuer.test", "default"),
                new Identity.Assertion(token, fingerprints)).toBase64();
        return "v=0\r\na=fingerprint:sha-256 " + DIGEST + "\r\na=identity:" + identity
                + "\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
    }
}
