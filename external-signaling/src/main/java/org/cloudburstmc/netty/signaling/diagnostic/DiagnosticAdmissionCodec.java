/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Draft NXD1 primitives; no listener, player admission, workload authorization or traffic execution. */
public final class DiagnosticAdmissionCodec {
    private DiagnosticAdmissionCodec() { }
    public static final int PROFILE = 1, SCTP_PORT = 5000, MAX_MESSAGE_SIZE = 262144;
    public static final int MAX_ATTEMPT_MILLIS = 60_000, MAX_HANDSHAKE_MILLIS = 15_000;
    public static final int MAX_APPLICATION_SEND_BYTES = 1024, MAX_FRAME_BYTES = 256, MAX_FRAMES = 12, MAX_UNRELIABLE_RETRIES = 2;
    /** Declared profile bounds; enforcing these requires the future native send-path adapter. */
    public static final int MAX_UDP_SENDS = 256, MAX_UDP_PAYLOAD_BYTES = 1200;
    static final long SAFE = 9007199254740991L;
    public record Context(String providerOrigin, String hostId, String incarnation, long generation) {
        public Context {
            URI uri = URI.create(providerOrigin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || providerOrigin.length() > 256 || !providerOrigin.equals("https://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort())) || uri.getPort() == 443
                    || !hostId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
            unhex(incarnation, 16); integer(generation, 1, SAFE);
        }
    }
    public record Key(String keyId, String secret, long notBefore, long retireAt) {
        public Key { if (!keyId.matches("[A-Z0-9]{4}") || utf8(secret).length < 32 || utf8(secret).length > 256 || !StandardCharsets.UTF_8.newEncoder().canEncode(secret)) throw invalid(); integer(notBefore, 0, SAFE); integer(retireAt, notBefore + 1, SAFE); }
        @Override public String toString() { return "DiagnosticKey[id=" + keyId + "]"; }
    }
    public record Claims(long expiresAt, String clientFingerprintHex, String clientIcePwd, String attemptIdHex,
                         String offerDigestHex, long candidateRevision, int family, String targetAddressHex, int targetPort, int profile) {
        public Claims {
            integer(expiresAt, 1000, 0xffffffffL * 1000); integer(candidateRevision, 1, SAFE); integer(targetPort, 1, 65535);
            if (expiresAt % 1000 != 0 || profile != PROFILE || (family != 4 && family != 6) || !clientIcePwd.matches("[A-Za-z0-9+/]{22,30}")) throw invalid();
            unhex(clientFingerprintHex, 32); unhex(attemptIdHex, 16); unhex(offerDigestHex, 32); byte[] address = unhex(targetAddressHex, 16);
            if (family == 4 ? !Arrays.equals(Arrays.copyOf(address, 12), new byte[12]) : targetAddressHex.startsWith("00000000000000000000ffff")) throw invalid();
        }
        @Override public String toString() { return "DiagnosticClaims[attempt=" + attemptIdHex + "]"; }
    }
    public record Clock(LongSupplier wallMillis, LongSupplier nanoTime) {
        public Clock { Objects.requireNonNull(wallMillis); Objects.requireNonNull(nanoTime); }
        public static Clock system() { return new Clock(System::currentTimeMillis, System::nanoTime); }
    }
    public record Credentials(String localUfrag, String icePwd) { @Override public String toString() { return "DiagnosticCredentials[redacted]"; } }
    public static int ufragLength(int passwordBytes) { if (passwordBytes < 0 || passwordBytes > 65535) throw invalid(); return 8 + ((156 + passwordBytes) * 4 + 2) / 3; }

    public static Credentials issue(Context context, Key key, Claims claims, String remoteUfrag, byte[] offer,
                                    DiagnosticAssertionCodec.Assertion assertion, long parentExpiresAt, Clock clock) {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        return issueWithNonce(context, key, claims, remoteUfrag, offer, assertion, parentExpiresAt, clock, nonce);
    }
    /** Public deterministic fixtures only. Production callers must use issue(). */
    public static Credentials issueWithNonce(Context context, Key key, Claims claims, String remoteUfrag, byte[] inputOffer,
                                             DiagnosticAssertionCodec.Assertion assertion, long parentExpiresAt, Clock clock, byte[] inputNonce) {
        byte[] offer = inputOffer.clone(), nonce = inputNonce.clone(); ufrag(remoteUfrag);
        long startWall = clock.wallMillis.getAsLong(), startNanos = clock.nanoTime.getAsLong();
        if (nonce.length != 12) throw invalid(); deadline(key, claims, clock.wallMillis.getAsLong(), parentExpiresAt);
        DiagnosticAssertionCodec.validateOffer(offer, claims, remoteUfrag);
        if (!DiagnosticAssertionCodec.verify(context, claims, remoteUfrag, assertion)) throw invalid();
        byte[] ctx = digest(contextBytes(context)), secret = utf8(key.secret), plain = encode(claims, identity(secret, ctx, assertion.publicPoint()));
        try {
            String header = "NXD1" + key.keyId;
            String local = header + base64(concat(nonce, crypt(Cipher.ENCRYPT_MODE, secret, ctx, nonce, aad(header, ctx, remoteUfrag), plain)));
            if (local.length() > 256) throw invalid(); Credentials result = new Credentials(local, icePassword(secret, ctx, local));
            deadline(key, claims, clock.wallMillis.getAsLong(), parentExpiresAt);
            if (clock.nanoTime.getAsLong() - startNanos >= (claims.expiresAt - startWall) * 1_000_000L) throw invalid(); return result;
        } finally { Arrays.fill(plain, (byte) 0); Arrays.fill(secret, (byte) 0); }
    }
    public static VerifiedDiagnosticAdmission open(Context context, Key key, String localUfrag, String remoteUfrag, long parentExpiresAt, Clock clock) {
        byte[] plain = null, secret = null;
        try {
            ufrag(remoteUfrag); long now = clock.wallMillis.getAsLong(), nanos = clock.nanoTime.getAsLong(); String header = "NXD1" + key.keyId;
            if (localUfrag.length() > 256 || !localUfrag.startsWith(header)) return null;
            byte[] envelope = unbase64(localUfrag.substring(8)); if (envelope.length < 178 || envelope.length > 186) return null;
            byte[] ctx = digest(contextBytes(context)); secret = utf8(key.secret);
            plain = crypt(Cipher.DECRYPT_MODE, secret, ctx, Arrays.copyOf(envelope, 12), aad(header, ctx, remoteUfrag), Arrays.copyOfRange(envelope, 12, envelope.length));
            Claims claims = decode(plain); deadline(key, claims, now, parentExpiresAt); deadline(key, claims, clock.wallMillis.getAsLong(), parentExpiresAt);
            VerifiedDiagnosticAdmission result = new VerifiedDiagnosticAdmission(context, claims, new Credentials(localUfrag, icePassword(secret, ctx, localUfrag)), remoteUfrag,
                    secret, ctx, Arrays.copyOfRange(plain, 36, 52), clock, nanos + (claims.expiresAt - now) * 1_000_000L);
            if (!result.usable()) { result.close(); return null; } return result;
        } catch (RuntimeException invalid) { return null; }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); if (secret != null) Arrays.fill(secret, (byte) 0); }
    }
    static byte[] contextBytes(Context c) { return concat(domain("context"), lp(c.providerOrigin), lp(c.hostId), unhex(c.incarnation, 16), ByteBuffer.allocate(8).putLong(c.generation).array()); }
    static byte[] encode(Claims c, byte[] binding) {
        if (binding.length != 16) throw invalid(); ByteBuffer b = ByteBuffer.allocate(128 + c.clientIcePwd.length());
        return b.putInt((int) (c.expiresAt / 1000)).put(unhex(c.clientFingerprintHex, 32)).put(binding).put(unhex(c.attemptIdHex, 16)).put(unhex(c.offerDigestHex, 32))
                .putLong(c.candidateRevision).put((byte) (c.family == 6 ? 129 : 1)).put(unhex(c.targetAddressHex, 16)).putShort((short) c.targetPort).put((byte) c.clientIcePwd.length()).put(utf8(c.clientIcePwd)).array();
    }
    static Claims decode(byte[] bytes) {
        if (bytes.length < 150 || bytes.length != 128 + Byte.toUnsignedInt(bytes[127])) throw invalid(); ByteBuffer b = ByteBuffer.wrap(bytes);
        int packed = Byte.toUnsignedInt(bytes[108]); if (packed != 1 && packed != 129) throw invalid();
        return new Claims(Integer.toUnsignedLong(b.getInt(0)) * 1000, hex(Arrays.copyOfRange(bytes, 4, 36)), new String(bytes, 128, bytes.length - 128, StandardCharsets.US_ASCII),
                hex(Arrays.copyOfRange(bytes, 52, 68)), hex(Arrays.copyOfRange(bytes, 68, 100)), b.getLong(100), packed == 129 ? 6 : 4, hex(Arrays.copyOfRange(bytes, 109, 125)), Short.toUnsignedInt(b.getShort(125)), 1);
    }
    static byte[] identity(byte[] secret, byte[] context, byte[] point) { DiagnosticAssertionCodec.publicKey(point); return Arrays.copyOf(hmac(secret, concat(domain("identity"), context, DiagnosticAssertionCodec.spki(point))), 16); }
    private static String icePassword(byte[] secret, byte[] context, String local) { return base64(Arrays.copyOf(hmac(secret, concat(domain("ice"), context, utf8(local))), 24)); }
    private static byte[] aad(String header, byte[] context, String remote) { return concat(domain("admission"), utf8(header), new byte[1], context, new byte[1], utf8(remote)); }
    private static byte[] crypt(int mode, byte[] secret, byte[] context, byte[] nonce, byte[] aad, byte[] input) {
        byte[] key = hmac(secret, concat(domain("aead"), context));
        try { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(aad); return cipher.doFinal(input); }
        catch (java.security.GeneralSecurityException e) { throw invalid(); } finally { Arrays.fill(key, (byte) 0); }
    }
    static void deadline(Key k, Claims c, long now, long parent) { integer(now, 0, SAFE); integer(parent, 1, SAFE); if (now < k.notBefore || now >= c.expiresAt || c.expiresAt - now > MAX_ATTEMPT_MILLIS || c.expiresAt > k.retireAt || c.expiresAt > parent) throw invalid(); }
    static byte[] domain(String s) { return utf8("nxs-diagnostic-" + s + "-v1\0"); }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] lp(String s) { byte[] b = utf8(s); return concat(ByteBuffer.allocate(2).putShort((short) b.length).array(), b); }
    static String ufrag(String s) { if (!s.matches("[A-Za-z0-9+/]{4,256}")) throw invalid(); return s; }
    static byte[] concat(byte[]... parts) { int n = 0; for (byte[] p : parts) n += p.length; byte[] out = new byte[n]; n = 0; for (byte[] p : parts) { System.arraycopy(p, 0, out, n, p.length); n += p.length; } return out; }
    static byte[] digest(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); } }
    static byte[] hmac(byte[] key, byte[] bytes) { try { Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(bytes); } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); } }
    static String base64(byte[] bytes) { return Base64.getEncoder().withoutPadding().encodeToString(bytes); }
    static byte[] unbase64(String s) { if (s.length() > 248 || !s.matches("[A-Za-z0-9+/]+")) throw invalid(); byte[] b = Base64.getDecoder().decode(s); if (!base64(b).equals(s)) throw invalid(); return b; }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static byte[] unhex(String s, int n) { if (!s.matches("[0-9a-f]{" + (2 * n) + "}")) throw invalid(); return HexFormat.of().parseHex(s); }
    static void integer(long n, long min, long max) { if (n < min || n > max) throw invalid(); }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Diagnostic value invalid or unauthorized"); }

    /** Numeric only; canonical packed bytes are the comparison representation. No DNS or target authorization. */
    public static String address(int family, String text) {
        if (family == 4) { String[] parts = text.split("\\.", -1); if (parts.length != 4) throw invalid(); byte[] out = new byte[16]; for (int i = 0; i < 4; i++) { if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw invalid(); int n = Integer.parseInt(parts[i]); if (n > 255) throw invalid(); out[i + 12] = (byte) n; } return hex(out); }
        if (family != 6 || !text.matches("[0-9A-Fa-f:]+")) throw invalid(); String[] sides = text.split("::", -1); if (sides.length > 2) throw invalid();
        String[] left = sides[0].isEmpty() ? new String[0] : sides[0].split(":", -1), right = sides.length < 2 || sides[1].isEmpty() ? new String[0] : sides[1].split(":", -1);
        if (sides.length == 1 ? left.length != 8 : left.length + right.length >= 8) throw invalid(); ByteBuffer b = ByteBuffer.allocate(16);
        for (String word : left) { if (!word.matches("[0-9A-Fa-f]{1,4}")) throw invalid(); b.putShort((short) Integer.parseInt(word, 16)); }
        b.position(16 - right.length * 2); for (String word : right) { if (!word.matches("[0-9A-Fa-f]{1,4}")) throw invalid(); b.putShort((short) Integer.parseInt(word, 16)); }
        String result = hex(b.array()); if (result.startsWith("00000000000000000000ffff")) throw invalid(); return result;
    }
}
