/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Detached diagnostic key proof. Deliberately unrelated to Minecraft CPK identity. */
public final class DiagnosticAssertionCodec {
    private DiagnosticAssertionCodec() { }
    private static final byte[] SPKI = unhex("3076301006072a8648ce3d020106052b81040022036200", 23);
    public record Assertion(byte[] publicPoint, byte[] signature) {
        public Assertion { if (publicPoint.length != 97 || publicPoint[0] != 4 || signature.length != 96) throw invalid(); publicPoint = publicPoint.clone(); signature = signature.clone(); }
        @Override public byte[] publicPoint() { return publicPoint.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
        @Override public String toString() { return "DiagnosticAssertion[redacted]"; }
    }
    static byte[] spki(byte[] point) { return concat(SPKI, point); }
    static PublicKey publicKey(byte[] point) {
        if (point.length != 97 || point[0] != 4) throw invalid();
        try { return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki(point))); }
        catch (GeneralSecurityException e) { throw invalid(); }
    }
    public static byte[] transcript(Context context, Claims claims, String remoteUfrag) {
        return concat(domain("assertion"), digest(contextBytes(context)), encode(claims, new byte[16]), lp(ufrag(remoteUfrag)));
    }
    public static Assertion sign(Context context, Claims claims, String remoteUfrag, KeyPair pair) {
        if (!(pair.getPrivate() instanceof ECPrivateKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 384) throw invalid();
        byte[] encoded = pair.getPublic().getEncoded();
        if (encoded.length != SPKI.length + 97 || !Arrays.equals(Arrays.copyOf(encoded, SPKI.length), SPKI)) throw invalid();
        try { Signature signer = Signature.getInstance("SHA384withECDSAinP1363Format"); signer.initSign(pair.getPrivate()); signer.update(transcript(context, claims, remoteUfrag));
            Assertion result = new Assertion(Arrays.copyOfRange(encoded, SPKI.length, encoded.length), signer.sign());
            if (!verify(context, claims, remoteUfrag, result)) throw invalid(); return result;
        } catch (GeneralSecurityException e) { throw invalid(); }
    }
    public static boolean verify(Context context, Claims claims, String remoteUfrag, Assertion assertion) {
        try { Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format"); verifier.initVerify(publicKey(assertion.publicPoint())); verifier.update(transcript(context, claims, remoteUfrag)); return verifier.verify(assertion.signature()); }
        catch (GeneralSecurityException | RuntimeException e) { return false; }
    }
    public static byte[] encodeAuth(String attemptIdHex, Assertion assertion) {
        return concat(new byte[]{0, 78, 88, 68, 80, 1, 1, 0}, unhex(attemptIdHex, 16), assertion.publicPoint(), assertion.signature());
    }
    public static Assertion decodeAuth(byte[] input, String attemptIdHex) {
        if (input.length != 217) throw invalid();
        byte[] b = input.clone();
        if (b.length != 217 || !Arrays.equals(Arrays.copyOf(b, 8), new byte[]{0, 78, 88, 68, 80, 1, 1, 0}) || !Arrays.equals(Arrays.copyOfRange(b, 8, 24), unhex(attemptIdHex, 16))) throw invalid();
        return new Assertion(Arrays.copyOfRange(b, 24, 121), Arrays.copyOfRange(b, 121, 217));
    }
    /** Checks the exact offer; never normalizes incompatible SCTP values or rewrites SDP. */
    public static void validateOffer(byte[] input, Claims c, String remoteUfrag) {
        if (input.length == 0 || input.length > 16384) throw invalid(); byte[] bytes = input.clone(); String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw invalid(); }
        if (text.indexOf(0) >= 0 || text.replace("\r\n", "").indexOf('\r') >= 0) throw invalid();
        List<String> lines = Arrays.stream(text.split("\r?\n")).filter(s -> !s.isEmpty()).toList();
        if (!one(lines, "m=").matches("application [0-9]{1,5} UDP/DTLS/SCTP webrtc-datachannel") || Integer.parseInt(one(lines, "m=").split(" ")[1]) < 1 || Integer.parseInt(one(lines, "m=").split(" ")[1]) > 65535 || lines.stream().anyMatch(l -> l.startsWith("a=ice-lite")) || !one(lines, "a=group:").equals("BUNDLE 0") || !one(lines, "a=mid:").equals("0") || !one(lines, "a=setup:").equals("actpass") ||
                !one(lines, "a=ice-ufrag:").equals(ufrag(remoteUfrag)) || !one(lines, "a=ice-pwd:").equals(c.clientIcePwd()) || !one(lines, "a=sctp-port:").equals("5000") || !one(lines, "a=max-message-size:").equals("262144") ||
                lines.stream().filter(l -> l.equals("a=end-of-candidates")).count() != 1 || lines.stream().anyMatch(l -> l.startsWith("a=identity:"))) throw invalid();
        String fingerprint = one(lines, "a=fingerprint:");
        if (!fingerprint.matches("sha-256 (?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}") || !fingerprint.substring(8).replace(":", "").equalsIgnoreCase(c.clientFingerprintHex())) throw invalid();
        String[] candidate = one(lines, "a=candidate:").split(" ", -1);
        if ((candidate.length < 8 || candidate.length > 16 || candidate.length % 2 != 0) || !candidate[0].matches("[A-Za-z0-9+/]{1,32}") || !candidate[1].equals("1") || !candidate[2].equalsIgnoreCase("udp") || !candidate[3].matches("[0-9]{1,10}") || Long.parseLong(candidate[3]) > 0xffffffffL || !candidate[5].matches("[0-9]{1,5}") || Integer.parseInt(candidate[5]) < 1 || Integer.parseInt(candidate[5]) > 65535 || !candidate[6].equals("typ") || !candidate[7].equals("host")) throw invalid();
        java.util.Set<String> extensions = new java.util.HashSet<>();
        for (int i = 8; i < candidate.length; i += 2) { String name = candidate[i], value = candidate[i + 1]; if (!extensions.add(name) || (name.equals("ufrag") ? !value.equals(remoteUfrag) : !List.of("generation", "network-id", "network-cost").contains(name) || !value.matches("[0-9]{1,10}") || Long.parseLong(value) > 0xffffffffL)) throw invalid(); }
        address(c.family(), candidate[4]); if (!hex(digest(bytes)).equals(c.offerDigestHex())) throw invalid();
    }
    private static String one(List<String> lines, String prefix) { List<String> values = lines.stream().filter(l -> l.startsWith(prefix)).toList(); if (values.size() != 1) throw invalid(); return values.get(0).substring(prefix.length()); }
}
