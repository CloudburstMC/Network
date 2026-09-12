package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a host signs and what it will believe. Every request to a provider is authenticated with
 * these, and the signed payload is assembled by hand, so what the assembly refuses matters as much
 * as what it produces.
 */
class ProviderCryptoTest {

    private static JsonObject jwk(KeyPair pair) {
        return ProviderCrypto.publicJwk(pair.getPublic());
    }

    @Test
    void signsAndVerifiesItsOwnPayload() throws Exception {
        KeyPair pair = ProviderCrypto.generate();
        String signature = ProviderCrypto.sign(pair.getPrivate(), "the payload");

        assertTrue(ProviderCrypto.verify(jwk(pair), signature, "the payload"));
    }

    @Test
    void verifiesNothingItCannotHold() throws Exception {
        KeyPair pair = ProviderCrypto.generate();
        KeyPair other = ProviderCrypto.generate();
        String signature = ProviderCrypto.sign(pair.getPrivate(), "the payload");

        assertFalse(ProviderCrypto.verify(jwk(pair), signature, "another payload"), "a payload it did not sign");
        assertFalse(ProviderCrypto.verify(jwk(other), signature, "the payload"), "a key that did not sign it");
        assertFalse(ProviderCrypto.verify(jwk(pair), ProviderCrypto.base64(new byte[95]), "the payload"),
                "a signature of the wrong length");
        assertFalse(ProviderCrypto.verify(jwk(pair), "not base64url!", "the payload"));
        assertFalse(ProviderCrypto.verify(new JsonObject(), signature, "the payload"), "a key that is not a key");
    }

    @Test
    void readsOnlyCanonicalBase64url() {
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.decode("has+plus"), "the wrong alphabet");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.decode("padded=="), "padding");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.decode(""), "nothing at all");
        // The last character carries spare bits, and only one spelling of them may be accepted
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.decode("AB"));
        assertEquals(1, ProviderCrypto.decode("AA").length);
    }

    @Test
    void takesNoPrivateKeyIntoAThumbprint() throws Exception {
        JsonObject key = jwk(ProviderCrypto.generate());
        assertEquals(ProviderCrypto.thumbprint(key), ProviderCrypto.thumbprint(key.deepCopy()));

        JsonObject withPrivate = key.deepCopy();
        withPrivate.addProperty("d", "whatever");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(withPrivate),
                "a private component means this is not a key to publish");
    }

    @Test
    void takesNoKeyItDoesNotRecognise() throws Exception {
        JsonObject key = jwk(ProviderCrypto.generate());

        JsonObject rsa = key.deepCopy();
        rsa.addProperty("kty", "RSA");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(rsa));

        JsonObject curve = key.deepCopy();
        curve.addProperty("crv", "P-256");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(curve));

        JsonObject shortX = key.deepCopy();
        shortX.addProperty("x", "tooshort");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(shortX));

        JsonObject shortY = key.deepCopy();
        shortY.addProperty("y", "tooshort");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(shortY));
    }

    @Test
    void requiresHttpsUnlessTheProviderIsThisMachine() {
        assertEquals("https://provider.example", ProviderCrypto.origin(URI.create("https://provider.example")));
        assertEquals("http://localhost:8080", ProviderCrypto.origin(URI.create("http://localhost:8080")));
        assertEquals("http://127.0.0.1", ProviderCrypto.origin(URI.create("http://127.0.0.1")));

        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.origin(URI.create("http://provider.example")),
                "plaintext to anywhere but this machine");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.origin(URI.create("ftp://provider.example")));
    }

    @Test
    void takesNoOriginCarryingMoreThanAnOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCrypto.origin(URI.create("https://user@provider.example")), "credentials");
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCrypto.origin(URI.create("https://provider.example#fragment")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCrypto.origin(URI.create("https://provider.example?query=1")));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCrypto.origin(URI.create("https://provider.example/v1")), "a path");
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.origin(URI.create("/relative")), "no host");
    }

    @Test
    void writesOneOriginForTheSameProviderHoweverItIsSpelled() {
        assertEquals("https://provider.example",
                ProviderCrypto.origin(URI.create("https://PROVIDER.Example")), "case folds");
        assertEquals("https://provider.example",
                ProviderCrypto.origin(URI.create("https://provider.example:443")), "the default port is implied");
        assertEquals("http://localhost", ProviderCrypto.origin(URI.create("http://localhost:80")));
        assertEquals("https://provider.example:8443",
                ProviderCrypto.origin(URI.create("https://provider.example:8443")), "any other port is kept");
    }

    @Test
    void escapesEverythingThatCouldForgeASignedPayload() {
        // Two different requests must never assemble into the same string to sign
        assertEquals("[\"a\\\"b\"]", ProviderCrypto.array("a\"b"));
        assertEquals("[\"a\\\\b\"]", ProviderCrypto.array("a\\b"));
        assertEquals("[\"a\\nb\",\"c\\td\"]", ProviderCrypto.array("a\nb", "c\td"));
        assertEquals("[\"\\b\\f\\r\"]", ProviderCrypto.array("\b\f\r"));
        assertEquals("[\"\\u0000\"]", ProviderCrypto.array("\u0000"), "a control character");
        assertEquals("[\"\\ud800\"]", ProviderCrypto.array("\ud800"), "a lone high surrogate");
        assertEquals("[\"\\udc00\"]", ProviderCrypto.array("\udc00"), "a lone low surrogate");
        assertEquals("[\"\ud83d\ude00\"]", ProviderCrypto.array("\ud83d\ude00"), "a real pair is left alone");
        assertEquals("[1,true,null]", ProviderCrypto.array(1, true, null), "and values that are not strings");
    }

    @Test
    void signsADifferentPayloadForEveryPartOfARequest() {
        String base = ProviderCrypto.request("https://p.example", "POST", "/v1/x", 1, "i", "k", "n", 2, 3, "body");

        assertNotEquals(base,
                ProviderCrypto.request("https://p.example", "GET", "/v1/x", 1, "i", "k", "n", 2, 3, "body"));
        assertNotEquals(base,
                ProviderCrypto.request("https://p.example", "POST", "/v1/y", 1, "i", "k", "n", 2, 3, "body"));
        assertNotEquals(base,
                ProviderCrypto.request("https://p.example", "POST", "/v1/x", 1, "i", "k", "n", 2, 4, "body"),
                "the sequence is what stops a replay");
        assertNotEquals(base,
                ProviderCrypto.request("https://p.example", "POST", "/v1/x", 1, "i", "k", "n", 2, 3, "other"),
                "and the body is covered rather than trusted");
        assertTrue(base.startsWith("[\"" + ProviderCrypto.PROTOCOL + "\",\"" + ProviderCrypto.SIGNATURE + "\""));
    }

    @Test
    void acceptsNoProofBelowTheDifficultyItAsked() {
        assertTrue(ProviderCrypto.meetsDifficulty(new byte[]{0, 0, 0}, 0), "nothing is asked of it");
        assertTrue(ProviderCrypto.meetsDifficulty(new byte[]{0, 0, 0}, 24));
        assertFalse(ProviderCrypto.meetsDifficulty(new byte[]{1, 0, 0}, 8), "the last bit of the first byte");
        assertTrue(ProviderCrypto.meetsDifficulty(new byte[]{1, 0, 0}, 7), "which one fewer bit does not reach");
        assertFalse(ProviderCrypto.meetsDifficulty(new byte[]{0, 0, 0}, -1), "a difficulty it cannot honour");
        assertFalse(ProviderCrypto.meetsDifficulty(new byte[]{0, 0, 0}, 25));
    }

    @Test
    void digestsTagsWithoutLettingTwoSetsCollide() {
        assertNotEquals(ProviderCrypto.tagsDigest(Map.of("a", "b")), ProviderCrypto.tagsDigest(Map.of("b", "a")));
        assertEquals(ProviderCrypto.tagsDigest(Map.of("a", "b", "c", "d")),
                ProviderCrypto.tagsDigest(new java.util.TreeMap<>(Map.of("c", "d", "a", "b"))),
                "order of a map is not part of what it means");
        assertNotEquals(ProviderCrypto.tagsDigest(Map.of("a,b", "c")), ProviderCrypto.tagsDigest(Map.of("a", "b,c")),
                "a separator inside a tag must not read as two tags");
    }

    @Test
    void digestsNoTagsAtAllAsNothing() {
        assertNull(ProviderCrypto.tagsDigest(null));
        assertNull(ProviderCrypto.tagsDigest(Map.of()));
    }

    @Test
    void bindsAProofToTheChallengeItAnswers() {
        JsonObject challenge = new JsonObject();
        challenge.addProperty("audience", "https://p.example");
        challenge.addProperty("challengeId", "c1");
        challenge.addProperty("nonce", "n1");
        challenge.addProperty("thumbprint", "t1");
        challenge.addProperty("contextDigest", "d1");
        challenge.addProperty("expiresAt", 1234L);

        String proof = ProviderCrypto.proof(challenge, "solved", "intent");

        assertTrue(proof.contains("\"c1\"") && proof.contains("\"n1\"") && proof.contains("\"t1\""));
        JsonObject other = challenge.deepCopy();
        other.addProperty("challengeId", "c2");
        assertNotEquals(proof, ProviderCrypto.proof(other, "solved", "intent"),
                "a solution to one challenge must not answer another");
        assertNotEquals(proof, ProviderCrypto.proof(challenge, "solved", "other intent"));
    }

    @Test
    void readsBackTheKeyItPublished() throws Exception {
        KeyPair pair = ProviderCrypto.generate();
        JsonObject published = jwk(pair);

        assertEquals(pair.getPublic(), ProviderCrypto.publicKey(published));
        assertEquals(published, JsonParser.parseString(published.toString()).getAsJsonObject());
        assertEquals(pair.getPrivate(), ProviderCrypto.privateKey(ProviderCrypto.base64(pair.getPrivate()
                .getEncoded())));
    }
}
