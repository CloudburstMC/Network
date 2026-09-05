package org.cloudburstmc.netty.util.nethernet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.keys.EllipticCurves;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerIdentityTest {
    @Test
    void serializedIdentityPreservesBothSignatures() throws Exception {
        EllipticCurveJsonWebKey key = EcJwkGenerator.generateJwk(EllipticCurves.P384);
        Instant expiry = Instant.now().plusSeconds(3600);
        ServerIdentity identity = new ServerIdentity(key.getPrivateKey(), key.getPublicKey(), expiry, "test.invalid");
        String digest = "AA:".repeat(31) + "AA";
        String sdp = "v=0\r\na=fingerprint:sha-256 " + digest + "\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

        String json = new String(Base64.getDecoder().decode(identity.identityValue(sdp)), StandardCharsets.UTF_8);
        JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("test.invalid", envelope.getAsJsonObject("idp").get("domain").getAsString());
        JsonObject assertion = JsonParser.parseString(envelope.get("assertion").getAsString()).getAsJsonObject();

        JsonWebSignature token = new JsonWebSignature();
        token.setCompactSerialization(assertion.get("token").getAsString());
        token.setKey(key.getPublicKey());
        assertEquals(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384, token.getAlgorithmHeaderValue());
        assertTrue(token.verifySignature());
        JwtClaims claims = JwtClaims.parse(token.getPayload());
        assertEquals("test.invalid", claims.getIssuer());
        assertEquals(expiry.getEpochSecond(), claims.getExpirationTime().getValue());
        assertEquals(Base64.getEncoder().encodeToString(key.getPublicKey().getEncoded()), claims.getStringClaimValue("cpk"));

        JsonWebSignature fingerprints = new JsonWebSignature();
        fingerprints.setCompactSerialization(assertion.get("fingerprints").getAsString());
        fingerprints.setPayload("{\"fingerprint\":[{\"algorithm\":\"sha-256\",\"digest\":\"" + digest + "\"}]}");
        fingerprints.setKey(key.getPublicKey());
        assertTrue(fingerprints.verifySignature());
    }
}
