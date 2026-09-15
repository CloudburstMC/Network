/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

class DiagnosticCodecTest {
    static final Gson GSON = new Gson();
    static final JsonObject FIXTURE;
    static { try (var reader = new InputStreamReader(DiagnosticCodecTest.class.getResourceAsStream("/nxs/diagnostic-v1.fixtures.json"), StandardCharsets.UTF_8)) { FIXTURE = JsonParser.parseReader(reader).getAsJsonObject(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    final Context context = GSON.fromJson(FIXTURE.get("context"), Context.class);
    final Key key = GSON.fromJson(FIXTURE.get("key"), Key.class);
    final long now = FIXTURE.get("now").getAsLong(), parent = FIXTURE.get("parentExpiresAt").getAsLong();
    final Clock clock = new Clock(() -> now, () -> 1_000_000_000L);
    final JsonObject vector = FIXTURE.getAsJsonArray("vectors").get(1).getAsJsonObject();
    final Claims claims = GSON.fromJson(vector.get("claims"), Claims.class);
    String value(JsonObject v, String name) { return v.get(name).getAsString(); }
    String value(String name) { return value(vector, name); }
    DiagnosticAssertionCodec.Assertion assertion(JsonObject v) { return new DiagnosticAssertionCodec.Assertion(unhex(value(v, "publicPointHex"), 97), unhex(value(v, "signatureHex"), 96)); }
    VerifiedDiagnosticAdmission open() { return DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), parent, clock); }

    @Test void sixSharedGoldenVectorsAndFreshJavaSignatures() throws Exception {
        assertEquals(FIXTURE.get("contextDigestHex").getAsString(), hex(digest(contextBytes(context))));
        JsonArray fresh = new JsonArray();
        for (var entry : FIXTURE.getAsJsonArray("vectors")) {
            JsonObject v = entry.getAsJsonObject(); Claims c = GSON.fromJson(v.get("claims"), Claims.class); var proof = assertion(v); String remote = value(v, "remoteUfrag");
            assertEquals(value(v, "assertionTranscriptHex"), hex(DiagnosticAssertionCodec.transcript(context, c, remote)));
            assertTrue(DiagnosticAssertionCodec.verify(context, c, remote, proof));
            assertEquals(value(v, "authHex"), hex(DiagnosticAssertionCodec.encodeAuth(c.attemptIdHex(), proof)));
            Credentials result = issueWithNonce(context, key, c, remote, utf8(value(v, "offer")), proof, parent, clock, unhex(value(v, "nonceHex"), 12));
            assertEquals(value(v, "localUfrag"), result.localUfrag()); assertEquals(value(v, "icePwd"), result.icePwd()); assertEquals(ufragLength(c.clientIcePwd().length()), result.localUfrag().length());
            try (var a = DiagnosticAdmissionCodec.open(context, key, result.localUfrag(), remote, parent, clock)) {
                assertNotNull(a); assertEquals(c, a.claims()); assertEquals(result, a.credentials());
                DiagnosticPrincipal principal = a.authenticate(unhex(value(v, "authHex"), 217)); assertNotNull(principal); assertEquals("connectivity-check", principal.purpose());
                assertNull(a.authenticate(unhex(value(v, "authHex"), 217)));
            }
            var privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(unhex(FIXTURE.get("privateKeyPkcs8Hex").getAsString(), 185)));
            var newProof = DiagnosticAssertionCodec.sign(context, c, remote, new KeyPair(DiagnosticAssertionCodec.publicKey(proof.publicPoint()), privateKey));
            JsonObject signed = new JsonObject(); signed.addProperty("name", value(v, "name")); signed.addProperty("signatureHex", hex(newProof.signature())); fresh.add(signed);
        }
        Files.createDirectories(Path.of("build")); Files.writeString(Path.of("build/diagnostic-java-signatures.json"), fresh.toString());
    }
    @Test void budgetAndAddressCanonicalization() {
        assertArrayEquals(new int[]{246, 248, 256, 258}, new int[]{ufragLength(22), ufragLength(24), ufragLength(30), ufragLength(31)});
        assertEquals(claims.targetAddressHex(), address(4, "203.0.113.8")); assertEquals("20010db8000100000000000000000008", address(6, "2001:0DB8:0001::8"));
        for (String a : new String[]{"::ffff:203.0.113.8", "::ffff:cb00:7108", "example.com", "fe80::1%eth0", "1::2::3"}) assertThrows(IllegalArgumentException.class, () -> address(6, a));
        assertThrows(IllegalArgumentException.class, () -> address(4, "203.00.113.8"));
        assertThrows(IllegalArgumentException.class, () -> new Claims(claims.expiresAt(), claims.clientFingerprintHex(), "a".repeat(31), claims.attemptIdHex(), claims.offerDigestHex(), 9, 4, claims.targetAddressHex(), 19132, 1));
    }
    @Test void everyContextIdentityAndPurposeIsBound() {
        Context[] wrong = { new Context(context.providerOrigin(), context.hostId(), context.incarnation(), 8), new Context(context.providerOrigin(), context.hostId(), "ff".repeat(16), 7), new Context(context.providerOrigin(), "other", context.incarnation(), 7), new Context("https://other.example", context.hostId(), context.incarnation(), 7) };
        for (Context c : wrong) { assertNull(DiagnosticAdmissionCodec.open(c, key, value("localUfrag"), value("remoteUfrag"), parent, clock)); assertFalse(DiagnosticAssertionCodec.verify(c, claims, value("remoteUfrag"), assertion(vector))); }
        for (String prefix : new String[]{"NXS1", "WDA2", "NXD2"}) assertNull(DiagnosticAdmissionCodec.open(context, key, prefix + value("localUfrag").substring(4), value("remoteUfrag"), parent, clock));
    }
    @Test void keyCredentialsParentsAndExpiryFailClosed() {
        assertNull(DiagnosticAdmissionCodec.open(context, new Key(key.keyId(), "z".repeat(32), key.notBefore(), key.retireAt()), value("localUfrag"), value("remoteUfrag"), parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), "wrong", parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag") + "=", value("remoteUfrag"), parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), claims.expiresAt() - 1, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), parent, new Clock(claims::expiresAt, System::nanoTime)));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), assertion(vector), claims.expiresAt() - 1, clock, unhex(value("nonceHex"), 12)));
    }
    @Test void allClaimFieldsAndOfferHashParticipateInProof() {
        for (String field : new String[]{"offerDigestHex", "clientFingerprintHex", "attemptIdHex", "candidateRevision", "targetPort", "family", "expiresAt", "clientIcePwd", "targetAddressHex"}) {
            JsonObject modified = vector.getAsJsonObject("claims").deepCopy();
            switch (field) {
                case "candidateRevision" -> modified.addProperty(field, 10);
                case "targetPort" -> modified.addProperty(field, 19133);
                case "family" -> modified.addProperty(field, 6);
                case "expiresAt" -> modified.addProperty(field, claims.expiresAt() + 1000);
                case "attemptIdHex", "targetAddressHex" -> modified.addProperty(field, field.equals("targetAddressHex") ? "00".repeat(12) + "01020304" : "ff".repeat(16));
                case "clientIcePwd" -> modified.addProperty(field, "z".repeat(24));
                default -> modified.addProperty(field, "ff".repeat(32));
            }
            assertFalse(DiagnosticAssertionCodec.verify(context, GSON.fromJson(modified, Claims.class), value("remoteUfrag"), assertion(vector)), field);
        }
        byte[] signature = assertion(vector).signature(); signature[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), new DiagnosticAssertionCodec.Assertion(assertion(vector).publicPoint(), signature), parent, clock, unhex(value("nonceHex"), 12)));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer") + "a=changed:x\r\n"), assertion(vector), parent, clock, unhex(value("nonceHex"), 12)));
    }
    @Test void authenticationRequiresExactFrameKeyAndSignatureOnce() {
        for (int offset : new int[]{0, 7, 8, 30, 150}) try (var admission = open()) {
            assertNotNull(admission); byte[] frame = unhex(value("authHex"), 217); frame[offset] ^= 1;
            assertNull(admission.authenticate(frame)); assertNull(admission.authenticate(unhex(value("authHex"), 217)));
        }
        try (var admission = open()) { assertNotNull(admission); assertNull(admission.authenticate(new byte[218])); }
        var proof = assertion(vector); byte[] point = proof.publicPoint(); point[10] ^= 1; assertTrue(DiagnosticAssertionCodec.verify(context, claims, value("remoteUfrag"), proof));
    }
    @Test void revocationCloseAndMonotonicExpiryRejectWallRollback() {
        AtomicLong wall = new AtomicLong(now), nanos = new AtomicLong(1_000_000_000);
        try (var admission = DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), parent, new Clock(wall::get, nanos::get))) {
            assertNotNull(admission); wall.addAndGet(-1000); nanos.addAndGet(30_000_000_000L); assertNull(admission.authenticate(unhex(value("authHex"), 217)));
        }
        var admission = open(); assertNotNull(admission); admission.close(); assertNull(admission.authenticate(unhex(value("authHex"), 217)));
    }
    @Test void incompatibleGatheredOffersAreRejectedWithoutRewrite() {
        for (String[] replace : new String[][]{{"sctp-port:5000", "sctp-port:5001"}, {"max-message-size:262144", "max-message-size:0"}, {" udp ", " tcp "}, {"198.51.100.2", "2001:db8::2"}, {"a=mid:0", "a=mid:0\r\na=mid:0"}, {"a=end-of-candidates", "a=incomplete"}, {"a=mid:0", "a=mid:0\r\na=identity:x"}}) {
            byte[] bytes = utf8(value("offer").replace(replace[0], replace[1])); Claims c = new Claims(claims.expiresAt(), claims.clientFingerprintHex(), claims.clientIcePwd(), claims.attemptIdHex(), hex(digest(bytes)), 9, 4, claims.targetAddressHex(), 19132, 1);
            assertThrows(IllegalArgumentException.class, () -> DiagnosticAssertionCodec.validateOffer(bytes, c, value("remoteUfrag")));
        }
    }
    @Test void validOtherKeyProofFailsTheSecretBinding() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("EC"); generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        var proof = DiagnosticAssertionCodec.sign(context, claims, value("remoteUfrag"), generator.generateKeyPair());
        assertTrue(DiagnosticAssertionCodec.verify(context, claims, value("remoteUfrag"), proof));
        try (var admission = open()) { assertNotNull(admission); assertNull(admission.authenticate(DiagnosticAssertionCodec.encodeAuth(claims.attemptIdHex(), proof))); }
    }
    @Test void slowIssuerCannotMintPastItsFixedDeadline() {
        AtomicLong reads = new AtomicLong();
        Clock lateWall = new Clock(() -> reads.incrementAndGet() >= 2 ? claims.expiresAt() : now, () -> 1000);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), assertion(vector), parent, lateWall, unhex(value("nonceHex"), 12)));
        AtomicLong ticks = new AtomicLong();
        Clock lateMono = new Clock(() -> now, () -> ticks.incrementAndGet() >= 2 ? 30_000_001_000L : 1000);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), assertion(vector), parent, lateMono, unhex(value("nonceHex"), 12)));
    }

    @Test void noncanonicalSecretUnicodeIsRejected() { assertThrows(IllegalArgumentException.class, () -> new Key("D001", "a".repeat(32) + (char) 0xd800, key.notBefore(), key.retireAt())); }

    @Test void allSharedOriginsUseTheHttps256Subset() throws Exception {
        Path path = Path.of("../docs/external-signaling/control-v1.origins.fixtures.json"); if (!Files.exists(path)) path = Path.of("docs/external-signaling/control-v1.origins.fixtures.json");
        JsonArray rows = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("vectors"); assertEquals(274, rows.size());
        for (var row : rows) {
            JsonObject v = row.getAsJsonObject(); String origin = v.get("origin").getAsString();
            if (v.get("accepted").getAsBoolean() && origin.startsWith("https://") && origin.length() <= 256) assertDoesNotThrow(() -> new Context(origin, context.hostId(), context.incarnation(), 7), origin);
            else assertThrows(IllegalArgumentException.class, () -> new Context(origin, context.hostId(), context.incarnation(), 7), origin);
        }
        for (String origin : new String[]{"https://provider.example:99999", "https://[2001:0db8:0000:0000:0000:0000:0000:0001]"}) assertThrows(IllegalArgumentException.class, () -> new Context(origin, context.hostId(), context.incarnation(), 7));
    }
    @Test void closedOfferProfileRejectsLiteAndInvalidMediaPorts() {
        for (String offer : new String[]{value("offer") + "a=ice-lite\r\n", value("offer").replace("m=application 9 ", "m=application 0 "), value("offer").replace("m=application 9 ", "m=application 65536 ")}) {
            byte[] bytes = utf8(offer); Claims c = new Claims(claims.expiresAt(), claims.clientFingerprintHex(), claims.clientIcePwd(), claims.attemptIdHex(), hex(digest(bytes)), 9, 4, claims.targetAddressHex(), 19132, 1);
            assertThrows(IllegalArgumentException.class, () -> DiagnosticAssertionCodec.validateOffer(bytes, c, value("remoteUfrag")));
        }
        assertThrows(IllegalArgumentException.class, () -> DiagnosticAssertionCodec.validateOffer(new byte[16385], claims, value("remoteUfrag")));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), new byte[16385], assertion(vector), parent, clock, unhex(value("nonceHex"), 12)));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), assertion(vector), parent, clock, new byte[13]));
    }
    @Test void backwardIssuerClockFailsClosed() {
        AtomicLong ticks = new AtomicLong(); Clock backwards = new Clock(() -> now, () -> ticks.incrementAndGet() == 1 ? 1000 : 999);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), assertion(vector), parent, backwards, unhex(value("nonceHex"), 12)));
    }

}
