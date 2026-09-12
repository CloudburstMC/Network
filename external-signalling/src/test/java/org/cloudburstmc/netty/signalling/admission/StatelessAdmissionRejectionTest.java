package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every way an admission has to be refused. A ticket is the only thing standing between a stranger
 * and a peer on this host, so each rejection is pinned rather than left to the happy path.
 */
class StatelessAdmissionRejectionTest extends AdmissionFixture {

    private String secret() {
        return this.f.getAsJsonObject("context").get("secret").getAsString();
    }

    private String audience() {
        return this.f.getAsJsonObject("context").get("audience").getAsString();
    }

    /** The token with its sealed envelope replaced, keeping the prefix and key id. */
    private String reseal(byte[] envelope) {
        return this.token.substring(0, 8) + Base64.getEncoder().withoutPadding().encodeToString(envelope);
    }

    private byte[] envelope() {
        return Base64.getDecoder().decode(this.token.substring(8));
    }

    @Test
    void acceptsTheFixtureItself() {
        // The negatives below are only meaningful because this one passes
        assertNotNull(this.validator().validate(this.request(), this.now));
    }

    @Test
    void refusesNothingAtAll() {
        assertNull(this.validator().validate(null, this.now));
    }

    @Test
    void refusesATokenTooShortToNameAKey() {
        assertNull(this.validator().validate(request("NXS1K", this.remote), this.now));
    }

    @Test
    void refusesATokenWithAnotherPrefix() {
        assertNull(this.validator().validate(request("NXS2" + this.token.substring(4), this.remote), this.now));
    }

    @Test
    void refusesAKeyEpochItDoesNotHold() {
        assertNull(this.validator().validate(request("NXS1K999" + this.token.substring(8), this.remote), this.now));
    }

    @Test
    void refusesAKeyThatIsNotYetInUse() {
        var validator = new StatelessAdmissionValidator(this.audience(), 60_000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", this.secret(),
                this.now + 1, this.now + 20_000)));

        assertNull(validator.validate(this.request(), this.now));
    }

    @Test
    void refusesAKeyThatHasBeenRetired() {
        var validator = new StatelessAdmissionValidator(this.audience(), 60_000);
        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", this.secret(),
                this.now - 20_000, this.now)));

        assertNull(validator.validate(this.request(), this.now));
    }

    @Test
    void refusesANoncanonicalEncodingOfTheSameEnvelope() {
        // The last character has spare bits. Setting them decodes to the same envelope, so only the
        // canonical spelling of it may be spent
        String canonical = this.reseal(new byte[118]);
        String tweaked = canonical.substring(0, canonical.length() - 1) + "B";

        assertNull(this.validator().validate(request(tweaked, this.remote), this.now));
    }

    @Test
    void refusesAnEnvelopeShorterThanAnyTicket() {
        assertNull(this.validator().validate(request(this.reseal(new byte[116]), this.remote), this.now));

        int length = this.envelope().length;
        assertTrue(length >= 117 && length <= 186, "the fixture has to sit inside the bounds it pins");
    }

    @Test
    void refusesAnOversizedTokenBeforeTheValidatorSeesIt() {
        // The ICE username is capped at 256 characters, which is what bounds the envelope at 186
        assertThrows(IllegalArgumentException.class, () -> request(this.reseal(new byte[187]), this.remote));
    }

    @Test
    void refusesPaddingBeforeTheValidatorSeesIt() {
        // Padding is outside the ICE alphabet, so a padded ticket never reaches the cipher
        String padded = this.token.substring(0, 8) + Base64.getEncoder().encodeToString(new byte[118]);

        assertThrows(IllegalArgumentException.class, () -> request(padded, this.remote));
    }

    @Test
    void refusesACiphertextThatHasBeenEdited() {
        byte[] envelope = this.envelope();
        envelope[envelope.length - 20] ^= 0x01;

        assertNull(this.validator().validate(request(this.reseal(envelope), this.remote), this.now));
    }

    @Test
    void refusesATicketPresentedByAnotherClient() {
        // The client ufrag is authenticated data, so one client cannot spend another's ticket
        assertNull(this.validator().validate(request(this.token, "someoneElseUfrag"), this.now));
    }

    @Test
    void refusesATicketMintedForAnotherHost() {
        var other = this.validator("nxs-stateless-host-v1/ffffffffffffffffffffffffffffffff");

        assertNull(other.validate(this.request(), this.now));
    }

    @Test
    void refusesATicketThatHasExpired() {
        long expiry = this.f.getAsJsonObject("claims").get("expiresAt").getAsLong();

        assertNull(this.validator().validate(this.request(), expiry));
        assertNull(this.validator().validate(this.request(), expiry + 1));
    }

    @Test
    void refusesATicketValidForLongerThanTheHostAllows() {
        var strict = new StatelessAdmissionValidator(this.audience(), 1);
        strict.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", this.secret())));

        assertNull(strict.validate(this.request(), this.now));
    }

    @Test
    void refusesMoreKeyEpochsThanItWillHold() {
        var validator = new StatelessAdmissionValidator(this.audience(), 60_000);
        var keys = new java.util.ArrayList<StatelessAdmissionValidator.TicketKey>();
        for (int i = 0; i < 9; i++) {
            keys.add(new StatelessAdmissionValidator.TicketKey(String.format("K%03d", i), this.secret()));
        }

        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(keys));
    }

    @Test
    void refusesKeyMaterialThatDoesNotMeetItsBounds() {
        var validator = new StatelessAdmissionValidator(this.audience(), 60_000);
        var secret = this.secret();

        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("k001", secret))), "key id must be upper case");
        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("K1", secret))), "key id must be four characters");
        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("K001", "too-short"))), "secret is under 32 bytes");
        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("K001", secret),
                        new StatelessAdmissionValidator.TicketKey("K001", secret))), "duplicate epoch");
        assertThrows(IllegalArgumentException.class, () -> validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("K001", secret, 10, 10))), "empty validity window");
    }

    @Test
    void holdsNoKeysUntilSomeAreInstalled() {
        var validator = new StatelessAdmissionValidator(this.audience(), 60_000);
        assertFalse(validator.ready());

        validator.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", this.secret())));
        assertTrue(validator.ready());
        assertEquals(java.util.Set.of("K001"), validator.keyIds());

        validator.clear();
        assertFalse(validator.ready(), "cleared keys must not keep admitting");
        assertNull(validator.validate(this.request(), this.now));
    }

    /** One field wrong at a time, so each guard is the one doing the refusing. */
    private static VerifiedAdmission admission(String tokenId, String localUfrag, String localPassword,
                                               String remoteUfrag, String remotePassword, String fingerprint,
                                               int sctpPort, int maxMessageSize) {
        return new VerifiedAdmission(tokenId, localUfrag, localPassword, remoteUfrag, remotePassword, fingerprint,
                sctpPort, maxMessageSize, 1, "1", BINDING, "K001", unusedVerifier());
    }

    @Test
    void refusesEachAdmissionFieldOnItsOwn() {
        // The fixture these vary from has to be accepted, or nothing below proves anything
        assertNotNull(admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag", PASSWORD, FINGERPRINT, 5000, 262144));

        assertThrows(IllegalArgumentException.class, () -> admission(null, "localUfrag", PASSWORD, "clientUfrag",
                PASSWORD, FINGERPRINT, 5000, 262144), "no token id at all");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID.toUpperCase(), "localUfrag", PASSWORD,
                "clientUfrag", PASSWORD, FINGERPRINT, 5000, 262144), "a token id is lower case hex");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, null, PASSWORD, "clientUfrag",
                PASSWORD, FINGERPRINT, 5000, 262144), "no local ufrag");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, null,
                PASSWORD, FINGERPRINT, 5000, 262144), "no remote ufrag");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag",
                "short", FINGERPRINT, 5000, 262144), "a remote password under 22 characters");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag",
                PASSWORD, null, 5000, 262144), "no fingerprint");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag",
                PASSWORD, FINGERPRINT.toLowerCase(), 5000, 262144), "a fingerprint is upper case hex");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag",
                PASSWORD, FINGERPRINT, 65536, 262144), "an sctp port past the top of the range");
        assertThrows(IllegalArgumentException.class, () -> admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag",
                PASSWORD, FINGERPRINT, 5000, 0), "no message size at all");
    }

    @Test
    void describesThePeerWithoutInventingAnything() {
        String sdp = admission(TOKEN_ID, "localUfrag", PASSWORD, "clientUfrag", PASSWORD, FINGERPRINT, 5000, 262144)
                .remoteDescription();

        assertTrue(sdp.contains("a=ice-ufrag:clientUfrag"), "the peer's own ufrag, not ours");
        assertTrue(sdp.contains("a=fingerprint:" + FINGERPRINT), "and the fingerprint DTLS has to match");
        assertTrue(sdp.contains("a=sctp-port:5000"));
        assertTrue(sdp.contains("a=max-message-size:262144"));
    }

    @Test
    void refusesIceIdentitiesOutsideTheirAlphabet() {
        assertThrows(IllegalArgumentException.class,
                () -> request("has space", "clientUfrag"), "ICE strings are base64 alphabet only");
        assertThrows(IllegalArgumentException.class, () -> request(this.token, "sh"), "ufrag minimum length");
        assertThrows(NullPointerException.class,
                () -> new AdmissionRequest(this.token, this.remote, null), "a source address is required");
        assertThrows(IllegalArgumentException.class, () -> new AdmissionRequest(this.token, this.remote,
                java.net.InetSocketAddress.createUnresolved("example.test", 1)), "the source must be resolved");
    }

    @Test
    void neverPrintsCredentials() {
        String printed = admission(TOKEN_ID, BINDING).toString() + request(this.token, this.remote);

        assertFalse(printed.contains(PASSWORD), "a password must never reach a log");
        assertFalse(printed.contains(this.token), "a ticket must never reach a log");
    }

    // Tickets this host will decrypt, so the checks behind the cipher can be reached at all

    /** Seals claims the way a provider would, for this audience, key epoch and client. */
    private String mint(byte[] claims) throws Exception {
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(
                        TestSignallingProvider.hmac(TestSignallingProvider.utf8(this.secret()),
                                "nxs-stateless-aead-v1\0" + this.audience()), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(TestSignallingProvider.utf8(
                "nxs-stateless-admission-v1\0NXS1K001\0" + this.audience() + "\0" + this.remote));
        byte[] sealed = cipher.doFinal(claims);
        return "NXS1K001" + Base64.getEncoder().withoutPadding()
                .encodeToString(ByteBuffer.allocate(12 + sealed.length).put(nonce).put(sealed).array());
    }

    private byte[] claims(int sctpPort, int maxMessageSize, String password, int declaredLength) {
        ByteBuffer claims = ByteBuffer.allocate(67 + password.length());
        claims.putInt((int) ((this.now + 30_000) / 1000)).put(new byte[32]).putShort((short) sctpPort)
                .putInt(maxMessageSize).put(new byte[16]).putLong(42)
                .put((byte) declaredLength).put(TestSignallingProvider.utf8(password));
        return claims.array();
    }

    private byte[] claims(int sctpPort, int maxMessageSize, String password) {
        return this.claims(sctpPort, maxMessageSize, password, password.length());
    }

    @Test
    void acceptsATicketItMintedItself() throws Exception {
        // Without this the minted negatives below could be failing for the wrong reason
        assertNotNull(this.validator().validate(
                request(this.mint(this.claims(5000, 262144, "aPasswordOfTwentyFiveChars")), this.remote), this.now));
    }

    @Test
    void refusesAPasswordLengthThatDoesNotMatchTheBody() throws Exception {
        String token = this.mint(this.claims(5000, 262144, "aPasswordOfTwentyFiveChars", 24));

        assertNull(this.validator().validate(request(token, this.remote), this.now));
    }

    @Test
    void refusesSctpParametersOutsideTheirBounds() throws Exception {
        String password = "aPasswordOfTwentyFiveChars";

        assertNull(this.validator().validate(
                request(this.mint(this.claims(0, 262144, password)), this.remote), this.now), "sctp port zero");
        assertNull(this.validator().validate(
                request(this.mint(this.claims(5000, 0, password)), this.remote), this.now), "no message size");
        assertNull(this.validator().validate(
                request(this.mint(this.claims(5000, 262145, password)), this.remote), this.now), "oversized message");
    }

    @Test
    void refusesAPasswordOutsideTheIceAlphabet() throws Exception {
        String token = this.mint(this.claims(5000, 262144, "not a valid ice password!!"));

        assertNull(this.validator().validate(request(token, this.remote), this.now));
    }

    @Test
    void refusesAnAdmissionContextItCannotTrust() {
        assertThrows(IllegalArgumentException.class, () -> new StatelessAdmissionValidator(null, 60_000));
        assertThrows(IllegalArgumentException.class, () -> new StatelessAdmissionValidator("", 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> new StatelessAdmissionValidator("a".repeat(513), 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> new StatelessAdmissionValidator("has\0nul", 60_000));
        assertThrows(IllegalArgumentException.class, () -> new StatelessAdmissionValidator(this.audience(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new StatelessAdmissionValidator(this.audience(), 120_001));
    }

    // What a validated admission looks like, for the field guards rather than the binding itself

    private static final String TOKEN_ID = "0123456789abcdef0123456789abcdef";
    private static final String PASSWORD = "aPasswordOfTwentyTwoPlus";
    private static final String FINGERPRINT = "sha-256 " + "AA:".repeat(31) + "BB";
    private static final String BINDING = "abcdef0123456789abcdef0123456789";

    /** A verifier these tests never consult, since the record now demands one. */
    private static IdentityKeyVerifier unusedVerifier() {
        return new IdentityKeyVerifier() {
            @Override
            protected boolean matches(byte[] canonicalKey) {
                return false;
            }

            @Override
            protected void release() {
            }
        };
    }

    private static VerifiedAdmission admission(String tokenId, String binding) {
        return new VerifiedAdmission(tokenId, "localUfrag", PASSWORD, "clientUfrag", PASSWORD, FINGERPRINT,
                5000, 262144, 1, "1", binding, "K001", unusedVerifier());
    }
}
