/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
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
    public static void validateOffer(byte[] input, Claims claims, String remoteUfrag) {
        offer(input, claims, remoteUfrag);
    }
    static DiagnosticSdp offer(byte[] input, Claims claims, String remoteUfrag) {
        DiagnosticSdp sdp = DiagnosticSdp.parse(input, claims, false);
        if (!sdp.ufrag().equals(ufrag(remoteUfrag)) || !sdp.password().equals(claims.clientIcePwd())
                || !sdp.fingerprintHex().equals(claims.clientFingerprintHex()) || !sdp.digestHex().equals(claims.offerDigestHex())) throw invalid();
        return sdp;
    }
    /** Returns the sole signed numeric candidate after full offer validation. */
    public static java.net.InetSocketAddress candidate(byte[] input, Claims claims, String remoteUfrag) {
        return offer(input, claims, remoteUfrag).endpoint(claims.family());
    }
}
