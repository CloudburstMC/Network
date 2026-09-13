package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.HmacKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.cloudburstmc.netty.util.nethernet.ClientAssertionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientAssertionValidatorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifiesTrustedTokensAndBothPublicKeyEncodings(boolean jwk) throws Exception {
        JwtClaims claims = claims();
        if (jwk) claims.setClaim("cpk", new EllipticCurveJsonWebKey((ECPublicKey) CLIENT_KEY.getPublic())
                .toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY));
        ClientIdentity identity = validator().validate(offer(claims));
        assertEquals("1234567890", identity.getClaims().get("xid"));
        assertArrayEquals(CLIENT_KEY.getPublic().getEncoded(), identity.getPublicKey().getEncoded());
        assertThrows(UnsupportedOperationException.class, () -> identity.getClaims().put("sub", "other"));
    }

    @Test
    void unreachableTrustSourceIsReportedSeparatelyFromBadAssertions() throws Exception {
        // Port 1 refuses the connection, so the key set fetch fails before any signature check.
        ClientAssertionValidator validator = new ClientAssertionValidator("https://127.0.0.1:1/keys", ISSUER, AUDIENCE);
        TrustSourceUnavailableException outage =
                assertThrows(TrustSourceUnavailableException.class, () -> validator.validate(validOffer()));
        assertTrue(outage.getMessage().startsWith("Trust source unavailable: "));
        assertFalse(outage.getMessage().contains(signedToken(claims())));
    }

    @Test
    void snapshotsNestedClaimsWithoutRetainingMutableCollections() {
        List<String> roles = new ArrayList<>(List.of("player"));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("roles", roles);
        claims.put("optional", null);
        ClientIdentity identity = new ClientIdentity(CLIENT_KEY.getPublic(), claims);
        roles.add("admin");
        claims.clear();
        assertEquals(List.of("player"), identity.getClaims().get("roles"));
        assertTrue(identity.getClaims().containsKey("optional"));
    }

    @Test
    void rejectsModifiedFingerprintAndSignatureFromAnotherClient() throws Exception {
        assertThrows(GeneralSecurityException.class,
                () -> validator().validate(validOffer().replace(DIGEST, "CD:".repeat(31) + "CD")));
        KeyPair other = keyPair("EC", "secp384r1");
        String mismatched = offer(signedToken(claims()), fingerprints(CANONICAL, other.getPrivate(),
                AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384));
        assertThrows(GeneralSecurityException.class, () -> validator().validate(mismatched));
    }

    @Test
    void rejectsTokenSignedByAnUntrustedIssuerKey() throws Exception {
        String token = sign(claims().toJson(), CLIENT_KEY.getPrivate(), AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        String sdp = offer(token, fingerprints(CANONICAL, CLIENT_KEY.getPrivate(),
                AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384));
        assertThrows(GeneralSecurityException.class, () -> validator().validate(sdp));
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "expiry", "subject", "missing-expiry", "not-before"})
    void enforcesTokenClaims(String invalid) throws Exception {
        JwtClaims claims = claims();
        switch (invalid) {
            case "issuer" -> claims.setIssuer("https://attacker.test/");
            case "audience" -> claims.setAudience("another-service");
            case "expiry" -> claims.setExpirationTime(NumericDate.fromSeconds(Instant.now().getEpochSecond() - 3600));
            case "subject" -> claims.unsetClaim("sub");
            case "missing-expiry" -> claims.unsetClaim("exp");
            case "not-before" -> claims.setNotBefore(NumericDate.fromSeconds(Instant.now().getEpochSecond() + 3600));
            default -> throw new AssertionError(invalid);
        }
        String sdp = offer(claims);
        assertThrows(GeneralSecurityException.class, () -> validator().validate(sdp));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid", "wrong-curve", "private-jwk"})
    void rejectsInvalidClientKeys(String invalid) throws Exception {
        JwtClaims claims = claims();
        switch (invalid) {
            case "missing" -> claims.unsetClaim("cpk");
            case "invalid" -> claims.setClaim("cpk", "not-a-key");
            case "wrong-curve" -> claims.setClaim("cpk", Base64.getEncoder().encodeToString(
                    keyPair("EC", "secp256r1").getPublic().getEncoded()));
            case "private-jwk" -> {
                Map<String, Object> key = new EllipticCurveJsonWebKey((ECPublicKey) CLIENT_KEY.getPublic())
                        .toParams(JsonWebKey.OutputControlLevel.PUBLIC_ONLY);
                key.put("d", "private");
                claims.setClaim("cpk", key);
            }
            default -> throw new AssertionError(invalid);
        }
        String sdp = offer(claims);
        assertThrows(GeneralSecurityException.class, () -> validator().validate(sdp));
    }

    @Test
    void rejectsHmacUsingPublicKeyBytesForEitherSignature() throws Exception {
        String token = sign(claims().toJson(), new HmacKey(ISSUER_KEY.getPublic().getEncoded()),
                AlgorithmIdentifiers.HMAC_SHA256);
        String legitimateFingerprints = fingerprints(CANONICAL, CLIENT_KEY.getPrivate(),
                AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        assertThrows(GeneralSecurityException.class, () -> validator().validate(offer(token, legitimateFingerprints)));
        String hmac = fingerprints(CANONICAL, new HmacKey(CLIENT_KEY.getPublic().getEncoded()),
                AlgorithmIdentifiers.HMAC_SHA256);
        assertThrows(GeneralSecurityException.class, () -> validator().validate(offer(signedToken(claims()), hmac)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsigned-token", "unencoded-payload", "unknown-critical-header"})
    void rejectsUnsupportedSignatureModes(String invalid) throws Exception {
        String token = signedToken(claims());
        JsonWebSignature signature = new JsonWebSignature();
        signature.setKey(CLIENT_KEY.getPrivate());
        signature.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        signature.setPayload(CANONICAL);
        switch (invalid) {
            case "unsigned-token" -> {
                Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
                token = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        + "." + encoder.encodeToString(claims().toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".";
            }
            case "unencoded-payload" -> {
                signature.getHeaders().setObjectHeaderValue("b64", false);
                signature.setCriticalHeaderNames("b64");
            }
            case "unknown-critical-header" -> {
                signature.setHeader("unknown", "value");
                signature.setCriticalHeaderNames("unknown");
            }
            default -> throw new AssertionError(invalid);
        }
        String[] parts = signature.getCompactSerialization().split("\\.", -1);
        String sdp = offer(token, parts[0] + ".." + parts[2]);
        assertThrows(GeneralSecurityException.class, () -> validator().validate(sdp));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-identity", "duplicate-identity", "media-identity", "missing-fingerprint",
            "indented-fingerprint", "invalid-digest", "empty-signature", "attached-payload", "duplicate-json"})
    void rejectsAmbiguousOrMalformedAssertions(String invalid) throws Exception {
        String sdp = validOffer();
        String identityLine = sdp.lines().filter(line -> line.startsWith("a=identity:")).findFirst().orElseThrow();
        switch (invalid) {
            case "missing-identity" -> sdp = sdp.replace(identityLine + "\r\n", "");
            case "duplicate-identity" -> sdp = sdp.replace(identityLine, identityLine + "\r\n" + identityLine);
            case "media-identity" -> sdp = sdp.replace(identityLine + "\r\n", "") + identityLine + "\r\n";
            case "missing-fingerprint" -> sdp = sdp.replace("a=fingerprint:sha-256 " + DIGEST + "\r\n", "");
            case "indented-fingerprint" -> sdp = sdp.replace("a=fingerprint:", " a=fingerprint:");
            case "invalid-digest" -> sdp = sdp.replace(DIGEST, "not-a-digest");
            case "empty-signature" -> sdp = offer(signedToken(claims()), "header..");
            case "attached-payload" -> sdp = offer(signedToken(claims()),
                    sign(CANONICAL, CLIENT_KEY.getPrivate(), AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384));
            case "duplicate-json" -> {
                String json = new String(Base64.getDecoder().decode(identityLine.substring("a=identity:".length())),
                        java.nio.charset.StandardCharsets.UTF_8);
                String duplicate = json.replace("\"idp\":", "\"idp\":{\"domain\":\"ignored.test\",\"protocol\":\"default\"},\"idp\":");
                sdp = sdp.replace(identityLine, "a=identity:" + Base64.getEncoder()
                        .encodeToString(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            default -> throw new AssertionError(invalid);
        }
        String invalidOffer = sdp;
        assertThrows(GeneralSecurityException.class, () -> validator().validate(invalidOffer));
    }

    @Test
    void bindsEveryFingerprintAndPreservesCanonicalArrayOrder() throws Exception {
        String second = "CD:".repeat(31) + "CD";
        String canonical = CANONICAL.replace("]}", ",{\"algorithm\":\"sha-256\",\"digest\":\"" + second + "\"}]}");
        String sdp = offer(signedToken(claims()), fingerprints(canonical, CLIENT_KEY.getPrivate(),
                AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384))
                + "a=fingerprint:sha-256 " + second + "\r\n";
        assertNotNull(validator().validate(sdp));
        assertThrows(GeneralSecurityException.class,
                () -> validator().validate(sdp.replace(second, DIGEST)));
    }
}
