/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAnswerCodec.*;

class DiagnosticAnswerCodecTest {
    static final Gson GSON = new Gson(); static final JsonObject FIXTURE;
    static { try (var reader = new InputStreamReader(DiagnosticAnswerCodecTest.class.getResourceAsStream("/nxs/diagnostic-answer-v1.fixtures.json"), StandardCharsets.UTF_8)) { FIXTURE = JsonParser.parseReader(reader).getAsJsonObject(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    final long now = FIXTURE.get("now").getAsLong();
    final Catalog catalog = GSON.fromJson(FIXTURE.get("catalog"), Catalog.class);
    final Options options = new Options(new Clock(() -> now, () -> 1_000_000_000L), () -> false);
    final JsonObject vector = FIXTURE.getAsJsonArray("vectors").get(0).getAsJsonObject();
    final Expected expected = GSON.fromJson(vector.get("expected"), Expected.class);
    final Signer signer;
    DiagnosticAnswerCodecTest() throws Exception {
        String encoded = FIXTURE.get("privateKeyPkcs8Hex").getAsString();
        signer = new Signer("provider-diagnostic", catalog.keys().get(0).keyId(), KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(unhex(encoded, encoded.length() / 2))));
    }
    String value(JsonObject o, String name) { return o.get(name).getAsString(); }
    String value(String name) { return value(vector, name); }
    VerifiedDiagnosticAnswer verify(String wire) { return DiagnosticAnswerCodec.verify(expected, wire, () -> catalog, options); }
    String signedMutation(String answer) throws Exception {
        JsonObject w = JsonParser.parseString(value("wire")).getAsJsonObject(); w.addProperty("answerSdpBase64", base64(utf8(answer))); w.addProperty("answerDigestHex", hex(digest(utf8(answer))));
        byte[] transcript = concat(domain("answer"), lp(w.get("keyId").getAsString()), ByteBuffer.allocate(8).putLong(w.get("expiresAt").getAsLong()).array(), unhex(w.get("requestDigestHex").getAsString(), 32), unhex(w.get("answerDigestHex").getAsString(), 32), ByteBuffer.allocate(4).putInt(utf8(answer).length).array());
        Signature s = Signature.getInstance("SHA384withECDSAinP1363Format"); s.initSign(signer.privateKey()); s.update(transcript); w.addProperty("signatureBase64", base64(s.sign())); return w.toString();
    }
    @Test void fourSharedDualFamilyVectorsAndFreshJavaAnswers() throws Exception {
        JsonArray fresh = new JsonArray();
        for (var row : FIXTURE.getAsJsonArray("vectors")) {
            JsonObject v = row.getAsJsonObject(); Expected e = GSON.fromJson(v.get("expected"), Expected.class);
            try (var result = DiagnosticAnswerCodec.verify(e, value(v, "wire"), () -> catalog, options)) { assertNotNull(result); assertArrayEquals(utf8(value(v, "answer")), result.takeSdp()); assertThrows(IllegalArgumentException.class, result::takeSdp); }
            JsonObject w = JsonParser.parseString(value(v, "wire")).getAsJsonObject(); Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format"); verifier.initVerify(DiagnosticAssertionCodec.publicKey(unhex(catalog.keys().get(0).publicPointHex(), 97))); verifier.update(unhex(value(v, "transcriptHex"), value(v, "transcriptHex").length() / 2)); assertTrue(verifier.verify(java.util.Base64.getDecoder().decode(w.get("signatureBase64").getAsString())));
            String wire = DiagnosticAnswerCodec.sign(e, utf8(value(v, "answer")), signer, () -> catalog, options);
            try (var result = DiagnosticAnswerCodec.verify(e, wire, () -> catalog, options)) { assertNotNull(result); assertArrayEquals(utf8(value(v, "answer")), result.takeSdp()); }
            JsonObject signed = new JsonObject(); signed.addProperty("name", value(v, "name")); signed.addProperty("wire", wire); fresh.add(signed);
        }
        Files.createDirectories(Path.of("build")); Files.writeString(Path.of("build/diagnostic-java-answers.json"), fresh.toString());
    }
    @Test void assistedAnswerHasFreshSignedDestinationAndCannotBeUsedForDirectAdmission() {
        var c=expected.claims();
        var assisted=new Claims(c.expiresAt(),c.clientFingerprintHex(),c.clientIcePwd(),c.attemptIdHex(),c.offerDigestHex(),c.candidateRevision(),c.family(),"00".repeat(16),0,ASSISTED_PROFILE);
        var e=new Expected(expected.context(),assisted,expected.remoteUfrag(),expected.hostFingerprintHex());
        String answer=value("answer").replace(" 19132 typ", " 19135 typ");
        String wire=DiagnosticAnswerCodec.sign(e,utf8(answer),signer,()->catalog,options);
        try(var verified=DiagnosticAnswerCodec.verify(e,wire,()->catalog,options)) {assertNotNull(verified);assertArrayEquals(utf8(answer),verified.takeSdp());}
        assertNull(DiagnosticAnswerCodec.verify(expected,wire,()->catalog,options));
        String empty=String.join("\r\n",answer.lines().filter(line->!line.startsWith("a=candidate:")).toList())+"\r\n";
        assertThrows(IllegalArgumentException.class,()->DiagnosticAnswerCodec.sign(e,utf8(empty),signer,()->catalog,options));
        assertThrows(IllegalArgumentException.class,()->DiagnosticAnswerCodec.sign(e,utf8(answer+"a=remote-candidates:1 127.0.0.1 19132\r\n"),signer,()->catalog,options));
    }
    @Test void canonicalWireRejectsDuplicatesLossyNumbersUnknownFieldsAndNestedStructures() {
        String original = value("wire");
        for (String wire : new String[]{original.replace("{\"version\":1", "{\"version\":1,\"version\":1"), original.substring(0, original.length() - 1) + ",\"other\":0}", " " + original, original.replace("\"version\":1", "\"version\":1.0"), original.replace("diagnostic-answer", "player-answer"), original.replace("\"kind\"", "\"\\u006bind\""), "{\"unknown\":" + "[".repeat(5000) + "0" + "]".repeat(5000) + "}"}) assertNull(verify(wire));
        JsonObject changed = JsonParser.parseString(original).getAsJsonObject(); changed.addProperty("signatureBase64", changed.get("signatureBase64").getAsString() + "="); assertNull(verify(changed.toString()));
    }
    @Test void requestContextAndTrustedHostFingerprintAreRequired() {
        for (String field : new String[]{"hostFingerprintHex", "attemptIdHex", "offerDigestHex", "targetPort", "family", "expiresAt", "generation", "remoteUfrag"}) {
            JsonObject e = vector.getAsJsonObject("expected").deepCopy();
            switch (field) {
                case "hostFingerprintHex" -> e.addProperty(field, "aa".repeat(32));
                case "generation" -> e.getAsJsonObject("context").addProperty(field, 8);
                case "remoteUfrag" -> e.addProperty(field, "other");
                case "attemptIdHex" -> e.getAsJsonObject("claims").addProperty(field, "ff".repeat(16));
                case "offerDigestHex" -> e.getAsJsonObject("claims").addProperty(field, "ff".repeat(32));
                case "targetPort" -> e.getAsJsonObject("claims").addProperty(field, 19133);
                case "family" -> e.getAsJsonObject("claims").addProperty(field, 6);
                case "expiresAt" -> e.getAsJsonObject("claims").addProperty(field, expected.claims().expiresAt() + 1000);
            }
            assertNull(DiagnosticAnswerCodec.verify(GSON.fromJson(e, Expected.class), value("wire"), () -> catalog, options), field);
        }
    }
    @Test void issuerAndVerifierRequireCatalogOriginFamilyParentAndKeyWindow() {
        VerificationKey k = catalog.keys().get(0);
        for (Catalog c : new Catalog[]{new Catalog("https://other.example", catalog.notBefore(), catalog.expiresAt(), catalog.keys()), new Catalog(catalog.providerOrigin(), catalog.notBefore(), expected.claims().expiresAt() - 1, catalog.keys()), new Catalog(catalog.providerOrigin(), now + 1, catalog.expiresAt(), catalog.keys()), new Catalog(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(), List.of(new VerificationKey(k.family(), "other", k.publicPointHex(), k.validFrom(), k.validUntil()))), new Catalog(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(), List.of(new VerificationKey(k.family(), k.keyId(), k.publicPointHex(), k.validFrom(), expected.claims().expiresAt() - 1)))}) {
            assertNull(DiagnosticAnswerCodec.verify(expected, value("wire"), () -> c, options));
            assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, utf8(value("answer")), signer, () -> c, options));
        }
        assertThrows(IllegalArgumentException.class, () -> new VerificationKey("machine", k.keyId(), k.publicPointHex(), k.validFrom(), k.validUntil()));
        assertThrows(IllegalArgumentException.class, () -> new Catalog(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(), List.of(k, k)));
    }
    @Test void signedIncompatibleAnswersStillFailSemanticVerification() throws Exception {
        for (String[] change : new String[][]{{"FE:DC", "AA:DC"}, {"203.0.113.8", "203.0.113.9"}, {"19132", "19133"}, {" UDP 213", " TCP 213"}, {"typ host", "typ relay"}, {"sctp-port:5000", "sctp-port:5001"}, {"max-message-size:262144", "max-message-size:0"}, {"setup:active", "setup:actpass"}, {"NXD1", "NXS1"}, {"a=mid:0", "a=mid:0\r\na=identity:player"}, {"a=mid:0", "a=mid:0\r\na=ice-lite"}}) {
            String answer = value("answer").replace(change[0], change[1]); assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, utf8(answer), signer, () -> catalog, options)); assertNull(verify(signedMutation(answer)));
        }
    }
    @Test void currentKeyIsRereadAfterCryptoAndBeforeTake() {
        VerificationKey k = catalog.keys().get(0); Catalog altered = new Catalog(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(), List.of(new VerificationKey(k.family(), k.keyId(), k.publicPointHex(), k.validFrom(), k.validUntil() + 1)));
        AtomicLong reads = new AtomicLong(); Supplier<Catalog> changes = () -> reads.incrementAndGet() == 1 ? catalog : altered;
        assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, utf8(value("answer")), signer, changes, options));
        reads.set(0); assertNull(DiagnosticAnswerCodec.verify(expected, value("wire"), changes, options));
        AtomicReference<Catalog> current = new AtomicReference<>(catalog);
        try (var result = DiagnosticAnswerCodec.verify(expected, value("wire"), current::get, options)) { assertNotNull(result); current.set(altered); assertThrows(IllegalArgumentException.class, result::takeSdp); assertThrows(IllegalArgumentException.class, result::takeSdp); }
    }
    @Test void takeChecksCancellationCloseExpiryAndBackwardClocks() {
        AtomicLong wall = new AtomicLong(now), mono = new AtomicLong(1_000_000_000L); AtomicBoolean cancelled = new AtomicBoolean(); Options opts = new Options(new Clock(wall::get, mono::get), cancelled::get);
        var result = DiagnosticAnswerCodec.verify(expected, value("wire"), () -> catalog, opts); assertNotNull(result); wall.addAndGet(-1000); mono.addAndGet(30_000_000_000L); assertThrows(IllegalArgumentException.class, result::takeSdp);
        wall.set(now); mono.set(1_000_000_000L); result = DiagnosticAnswerCodec.verify(expected, value("wire"), () -> catalog, opts); assertNotNull(result); cancelled.set(true); assertThrows(IllegalArgumentException.class, result::takeSdp);
        cancelled.set(false); result = DiagnosticAnswerCodec.verify(expected, value("wire"), () -> catalog, opts); assertNotNull(result); mono.decrementAndGet(); assertThrows(IllegalArgumentException.class, result::takeSdp);
        result = verify(value("wire")); assertNotNull(result); result.close(); assertThrows(IllegalArgumentException.class, result::takeSdp);
    }
    @Test void pendingDeliveryRejectsLateReaderAndCancelledCryptoCompletion() {
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicLong reads = new AtomicLong(); Options opts = new Options(options.clock(), cancelled::get);
        Supplier<Catalog> reader = () -> { if (reads.incrementAndGet() == 2) cancelled.set(true); return catalog; };
        assertNull(DiagnosticAnswerCodec.verify(expected, value("wire"), reader, opts));
        cancelled.set(false); reads.set(0); assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, utf8(value("answer")), signer, reader, opts));
    }
    @Test void oversizedBuffersAndTamperedProofsAreRejected() {
        assertNull(verify("x".repeat(24577))); assertThrows(IllegalArgumentException.class, () -> validateSdp(new byte[16385], expected));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, new byte[16385], signer, () -> catalog, options));
        JsonObject w = JsonParser.parseString(value("wire")).getAsJsonObject(); String s = w.get("signatureBase64").getAsString(); w.addProperty("signatureBase64", (s.charAt(0) == 'A' ? "B" : "A") + s.substring(1)); assertNull(verify(w.toString()));
    }
    @Test void takeReservesOneUseBeforeReentrantReaderAndHonorsClose() {
        AtomicReference<Runnable> action = new AtomicReference<>(() -> {});
        Supplier<Catalog> reader = () -> { action.get().run(); return catalog; };
        var first = DiagnosticAnswerCodec.verify(expected, value("wire"), reader, options); assertNotNull(first);
        action.set(() -> assertThrows(IllegalArgumentException.class, first::takeSdp));
        assertArrayEquals(utf8(value("answer")), first.takeSdp());
        action.set(() -> {});
        var second = DiagnosticAnswerCodec.verify(expected, value("wire"), reader, options); assertNotNull(second);
        action.set(second::close); assertThrows(IllegalArgumentException.class, second::takeSdp);
    }
    @Test void signerMismatchAndIndentedCandidateAreRejected() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("EC"); generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        Signer wrong = new Signer("provider-diagnostic", signer.keyId(), generator.generateKeyPair().getPrivate());
        assertThrows(IllegalArgumentException.class, () -> DiagnosticAnswerCodec.sign(expected, utf8(value("answer")), wrong, () -> catalog, options));
        String answer = value("answer") + " " + value("answer").lines().filter(l -> l.startsWith("a=candidate:")).findFirst().orElseThrow() + "\r\n";
        assertThrows(IllegalArgumentException.class, () -> validateSdp(utf8(answer), expected)); assertNull(verify(signedMutation(answer)));
    }

}
