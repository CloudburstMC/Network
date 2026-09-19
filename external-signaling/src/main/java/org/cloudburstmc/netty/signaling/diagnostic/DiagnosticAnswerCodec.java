/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Provider-signed answer primitives. No workload authorization, source acquisition or transport execution. */
public final class DiagnosticAnswerCodec {
    private DiagnosticAnswerCodec() { }
    public static final int MAX_SDP_BYTES = 16384, MAX_WIRE_BYTES = 24576;
    public record Expected(Context context, Claims claims, String remoteUfrag, String hostFingerprintHex) {
        public Expected { Objects.requireNonNull(context); Objects.requireNonNull(claims); ufrag(remoteUfrag); unhex(hostFingerprintHex, 32); }
    }
    public record VerificationKey(String family, String keyId, String publicPointHex, long validFrom, long validUntil) {
        public VerificationKey { DiagnosticAnswerCodec.keyId(keyId); integer(validFrom, 0, SAFE); integer(validUntil, validFrom + 1, SAFE); if (!"provider-diagnostic".equals(family) || unhex(publicPointHex, 97)[0] != 4) throw invalid(); }
    }
    /** Trusted cached parent metadata. Construction does not establish source freshness. */
    public record Catalog(String providerOrigin, long notBefore, long expiresAt, List<VerificationKey> keys) {
        public Catalog { if (keys.size() < 1 || keys.size() > 8) throw invalid(); keys = List.copyOf(keys); integer(notBefore, 0, SAFE); integer(expiresAt, notBefore + 1, SAFE); Set<String> ids = new HashSet<>(); for (var key : keys) if (!ids.add(key.keyId)) throw invalid(); }
    }
    public record Signer(String family, String keyId, PrivateKey privateKey) {
        public Signer { DiagnosticAnswerCodec.keyId(keyId); if (!"provider-diagnostic".equals(family) || !(privateKey instanceof ECPrivateKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384) throw invalid(); }
        @Override public String toString() { return "DiagnosticAnswerSigner[id=" + keyId + "]"; }
    }
    public record Options(Clock clock, BooleanSupplier cancelled) {
        public Options { Objects.requireNonNull(clock); Objects.requireNonNull(cancelled); }
        public static Options system() { return new Options(Clock.system(), () -> false); }
    }
    private record Wire(int version, String kind, String keyId, long expiresAt, String requestDigestHex,
                        String answerSdpBase64, String answerDigestHex, String signatureBase64) { }
    static final class Fence {
        final long expiresAt, deadlineNanos; final Options options; long previousNanos;
        Fence(long expiresAt, Options options) {
            this.expiresAt = expiresAt; this.options = options; long now = options.clock.wallMillis().getAsLong(), nanos = options.clock.nanoTime().getAsLong(); integer(now, 0, SAFE);
            if (options.cancelled.getAsBoolean() || expiresAt <= now || expiresAt - now > 60000) throw invalid(); previousNanos = nanos; deadlineNanos = nanos + (expiresAt - now) * 1_000_000L;
        }
        long current() {
            long now = options.clock.wallMillis().getAsLong(), nanos = options.clock.nanoTime().getAsLong(); integer(now, 0, SAFE);
            if (options.cancelled.getAsBoolean() || nanos - previousNanos < 0 || nanos - deadlineNanos >= 0 || now >= expiresAt) throw invalid(); previousNanos = nanos; return now;
        }
    }
    private static void keyId(String id) { if (id == null || id.length() > 128 || !id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw invalid(); }
    static VerificationKey key(Supplier<Catalog> reader, Expected expected, String keyId, long now) {
        Catalog catalog = reader.get();
        if (!expected.context.providerOrigin().equals(catalog.providerOrigin) || now < catalog.notBefore || now >= catalog.expiresAt || expected.claims.expiresAt() > catalog.expiresAt) throw invalid();
        VerificationKey found = catalog.keys.stream().filter(k -> k.keyId.equals(keyId)).findFirst().orElseThrow(DiagnosticAdmissionCodec::invalid);
        if (now < found.validFrom || now >= found.validUntil || expected.claims.expiresAt() > found.validUntil) throw invalid(); return found;
    }
    static void currentKey(Supplier<Catalog> reader, Expected expected, VerificationKey selected, Fence fence) {
        if (!selected.equals(key(reader, expected, selected.keyId, fence.current()))) throw invalid(); fence.current();
    }
    private static String canonical(Wire w) {
        JsonObject object = new JsonObject(); object.addProperty("version", w.version); object.addProperty("kind", w.kind); object.addProperty("keyId", w.keyId); object.addProperty("expiresAt", w.expiresAt);
        object.addProperty("requestDigestHex", w.requestDigestHex); object.addProperty("answerSdpBase64", w.answerSdpBase64); object.addProperty("answerDigestHex", w.answerDigestHex); object.addProperty("signatureBase64", w.signatureBase64); return object.toString();
    }
    private static byte[] decodeBase64(String value, int maximumBytes) {
        if (value == null || value.length() == 0 || value.length() > (maximumBytes * 4 + 2) / 3 || !value.matches("[A-Za-z0-9+/]+")) throw invalid();
        byte[] bytes = Base64.getDecoder().decode(value); if (bytes.length > maximumBytes || !base64(bytes).equals(value)) throw invalid(); return bytes;
    }
    private static Wire decode(String input) {
        if (input == null || input.length() == 0 || input.length() > MAX_WIRE_BYTES || !input.chars().allMatch(c -> c >= 32 && c <= 126)) throw invalid();
        if (!input.startsWith("{") || !input.endsWith("}") || input.indexOf('{', 1) >= 0 || input.indexOf('}') != input.length() - 1 || input.indexOf('[') >= 0 || input.indexOf(']') >= 0) throw invalid();
        JsonObject o = JsonParser.parseString(input).getAsJsonObject();
        Wire w = new Wire(o.get("version").getAsInt(), o.get("kind").getAsString(), o.get("keyId").getAsString(), o.get("expiresAt").getAsLong(), o.get("requestDigestHex").getAsString(), o.get("answerSdpBase64").getAsString(), o.get("answerDigestHex").getAsString(), o.get("signatureBase64").getAsString());
        if (w.version != 1 || !w.kind.equals("diagnostic-answer")) throw invalid(); keyId(w.keyId); integer(w.expiresAt, 1000, 0xffffffffL * 1000); if (w.expiresAt % 1000 != 0) throw invalid();
        unhex(w.requestDigestHex, 32); unhex(w.answerDigestHex, 32); decodeBase64(w.answerSdpBase64, MAX_SDP_BYTES); if (decodeBase64(w.signatureBase64, 96).length != 96 || !canonical(w).equals(input)) throw invalid(); return w;
    }
    private static byte[] transcript(Wire w, int size) {
        return concat(domain("answer"), lp(w.keyId), ByteBuffer.allocate(8).putLong(w.expiresAt).array(), unhex(w.requestDigestHex, 32), unhex(w.answerDigestHex, 32), ByteBuffer.allocate(4).putInt(size).array());
    }
    public static String sign(Expected expected, byte[] input, Signer signer, Supplier<Catalog> catalog, Options options) {
        if (input.length == 0 || input.length > MAX_SDP_BYTES) throw invalid(); byte[] bytes = input.clone(); Fence fence = new Fence(expected.claims.expiresAt(), options);
        VerificationKey selected = key(catalog, expected, signer.keyId, fence.current()); validateSdp(bytes, expected);
        String requestHash = hex(digest(DiagnosticAssertionCodec.transcript(expected.context, expected.claims, expected.remoteUfrag)));
        Wire unsigned = new Wire(1, "diagnostic-answer", selected.keyId, expected.claims.expiresAt(), requestHash, base64(bytes), hex(digest(bytes)), "");
        try {
            byte[] signingBytes = transcript(unsigned, bytes.length); Signature algorithm = Signature.getInstance("SHA384withECDSAinP1363Format"); algorithm.initSign(signer.privateKey); algorithm.update(signingBytes); byte[] signature = algorithm.sign();
            algorithm.initVerify(DiagnosticAssertionCodec.publicKey(unhex(selected.publicPointHex, 97))); algorithm.update(signingBytes); if (signature.length != 96 || !algorithm.verify(signature)) throw invalid();
            String result = canonical(new Wire(unsigned.version, unsigned.kind, unsigned.keyId, unsigned.expiresAt, unsigned.requestDigestHex, unsigned.answerSdpBase64, unsigned.answerDigestHex, base64(signature)));
            if (result.length() > MAX_WIRE_BYTES) throw invalid(); currentKey(catalog, expected, selected, fence); return result;
        } catch (java.security.GeneralSecurityException e) { throw invalid(); }
    }
    public static VerifiedDiagnosticAnswer verify(Expected expected, String input, Supplier<Catalog> catalog, Options options) {
        try {
            Wire wire = decode(input); Fence fence = new Fence(expected.claims.expiresAt(), options); if (wire.expiresAt != expected.claims.expiresAt()) return null;
            VerificationKey selected = key(catalog, expected, wire.keyId, fence.current()); byte[] bytes = decodeBase64(wire.answerSdpBase64, MAX_SDP_BYTES);
            if (!hex(digest(DiagnosticAssertionCodec.transcript(expected.context, expected.claims, expected.remoteUfrag))).equals(wire.requestDigestHex) || !hex(digest(bytes)).equals(wire.answerDigestHex)) return null;
            Signature algorithm = Signature.getInstance("SHA384withECDSAinP1363Format"); algorithm.initVerify(DiagnosticAssertionCodec.publicKey(unhex(selected.publicPointHex, 97))); algorithm.update(transcript(wire, bytes.length)); if (!algorithm.verify(decodeBase64(wire.signatureBase64, 96))) return null;
            validateSdp(bytes, expected); currentKey(catalog, expected, selected, fence); return new VerifiedDiagnosticAnswer(expected, bytes, selected, catalog, fence);
        } catch (java.security.GeneralSecurityException | RuntimeException invalid) { return null; }
    }
    /** Enforces the declared profile and exact numeric destination; it does not observe a selected pair. */
    public static void validateSdp(byte[] input, Expected expected) {
        DiagnosticSdp sdp = DiagnosticSdp.parse(input, expected.claims, true);
        String local = sdp.ufrag();
        if (!sdp.fingerprintHex().equalsIgnoreCase(expected.hostFingerprintHex)
                || !local.matches("NXD1[A-Z0-9]{4}[A-Za-z0-9+/]+")
                || local.length() != ufragLength(expected.claims.clientIcePwd().length())
                || unbase64(local.substring(8)).length != 156 + expected.claims.clientIcePwd().length()
                || !sdp.password().matches("[A-Za-z0-9+/]{32}")) throw invalid();
        if (expected.claims.profile() == PROFILE && (sdp.port() != expected.claims.targetPort()
                || !sdp.addressHex().equals(expected.claims.targetAddressHex()))) throw invalid();
    }
}
