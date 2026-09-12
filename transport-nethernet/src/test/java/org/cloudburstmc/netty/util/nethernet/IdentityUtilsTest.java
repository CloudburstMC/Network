package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assertion binds a peer's key to the DTLS fingerprints of the description it sent. Break that
 * and a captured assertion can be replayed over someone else's transport, so each way it can fail
 * has to refuse.
 */
class IdentityUtilsTest {

    private static final String DIGEST = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
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

    /** An offer whose assertion covers {@code signedOver}, carried in a description of {@code sdp}. */
    private static String offer(String sdp, String signedOver) throws Exception {
        KeyPair pair = keyPair();
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        claims.setClaim("xid", "2535000000000000");
        claims.setClaim("xname", "Probe");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(5);

        String[] parts = sign(pair, IdentityUtils.getCanonicalFingerprintJson(signedOver)).split("\\.");
        Identity identity = new Identity(new Identity.Idp("example.test", "default"),
                new Identity.Assertion(sign(pair, claims.toJson()), parts[0] + ".." + parts[2]));
        return sdp.replace("m=application", "a=identity:" + identity.toBase64() + "\r\nm=application");
    }

    private static String description(String fingerprintLine) {
        return "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n" + fingerprintLine
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
    }

    @Test
    void acceptsAnAssertionOverTheFingerprintsItWasSent() throws Exception {
        String sdp = description("a=fingerprint:sha-256 " + DIGEST + "\r\n");

        assertEquals("2535000000000000",
                IdentityUtils.validateSdp(offer(sdp, sdp), TokenTrust.ANY).getClaimValueAsString("xid"));
    }

    @Test
    void refusesAnAssertionOverSomeoneElsesFingerprints() throws Exception {
        // The replay: a captured assertion carried on a transport its signer never opened
        String signedOver = description("a=fingerprint:sha-256 " + DIGEST + "\r\n");
        String sent = description("a=fingerprint:sha-256 " + DIGEST.replace("AA:BB", "BB:AA") + "\r\n");

        JoseException refused = assertThrows(JoseException.class,
                () -> IdentityUtils.validateSdp(offer(sent, signedOver), TokenTrust.ANY));

        assertTrue(refused.getMessage().contains("signature mismatch"));
    }

    @Test
    void refusesADescriptionWithNoFingerprintToBindTo() throws Exception {
        String sdp = description("");

        JoseException refused = assertThrows(JoseException.class,
                () -> IdentityUtils.validateSdp(offer(sdp, sdp), TokenTrust.ANY));

        assertTrue(refused.getMessage().contains("no fingerprints"));
    }

    @Test
    void refusesAFingerprintLineItCannotRead() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentityUtils.getCanonicalFingerprintJson("a=fingerprint:sha-256\r\n"), "no digest");
        assertThrows(IllegalArgumentException.class,
                () -> IdentityUtils.getCanonicalFingerprintJson("a=fingerprint:sha-256 " + DIGEST + " extra\r\n"),
                "more than an algorithm and a digest");
    }

    @Test
    void readsEveryFingerprintInTheOrderTheyWereSent() {
        String sdp = description("a=fingerprint:sha-256 " + DIGEST + "\r\na=fingerprint:sha-1 AA:BB\r\n");

        assertEquals("{\"fingerprint\":[{\"algorithm\":\"sha-256\",\"digest\":\"" + DIGEST + "\"},"
                + "{\"algorithm\":\"sha-1\",\"digest\":\"AA:BB\"}]}", IdentityUtils.getCanonicalFingerprintJson(sdp));
    }

    @Test
    void readsAnEmptyListWhenThereAreNone() {
        assertEquals("{\"fingerprint\":[]}", IdentityUtils.getCanonicalFingerprintJson(description("")));
    }
}
