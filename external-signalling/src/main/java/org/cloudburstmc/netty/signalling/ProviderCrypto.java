package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;

/** Exact v0 canonical bytes. P1363 explicitly avoids the JVM's default DER ECDSA encoding. */
public final class ProviderCrypto {
    public static final String PROTOCOL = "nethernet-external-signalling-v1";
    public static final String SIGNATURE = "nxs-es384-v1";
    private ProviderCrypto() {}
    public static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp384r1")); return g.generateKeyPair();
    }
    public static String base64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] decode(String s) {
        if (!s.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid base64url");
        byte[] b = Base64.getUrlDecoder().decode(s);
        if (!base64(b).equals(s)) throw new IllegalArgumentException("Noncanonical base64url"); return b;
    }
    public static byte[] digest(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static JsonObject publicJwk(PublicKey key) {
        ECPublicKey ec = (ECPublicKey) key; JsonObject j = new JsonObject();
        j.addProperty("crv", "P-384"); j.addProperty("kty", "EC"); j.addProperty("x", base64(coordinate(ec.getW().getAffineX()))); j.addProperty("y", base64(coordinate(ec.getW().getAffineY()))); return j;
    }
    private static byte[] coordinate(BigInteger n) { byte[] raw = n.toByteArray(), out = new byte[48]; System.arraycopy(raw, Math.max(0, raw.length - 48), out, Math.max(0, 48 - raw.length), Math.min(48, raw.length)); return out; }
    public static PublicKey publicKey(JsonObject j) throws GeneralSecurityException {
        validateJwk(j); AlgorithmParameters p = AlgorithmParameters.getInstance("EC"); p.init(new ECGenParameterSpec("secp384r1"));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(new BigInteger(1, decode(j.get("x").getAsString())), new BigInteger(1, decode(j.get("y").getAsString()))), p.getParameterSpec(ECParameterSpec.class)));
    }
    public static PrivateKey privateKey(String encoded) throws GeneralSecurityException { return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decode(encoded))); }
    private static void validateJwk(JsonObject j) {
        if (j.has("d") || !"EC".equals(j.get("kty").getAsString()) || !"P-384".equals(j.get("crv").getAsString()) || !j.get("x").getAsString().matches("[A-Za-z0-9_-]{64}") || !j.get("y").getAsString().matches("[A-Za-z0-9_-]{64}")) throw new IllegalArgumentException("Invalid public P-384 JWK");
    }
    public static String thumbprint(JsonObject j) {
        validateJwk(j); return base64(digest("{\"crv\":\"P-384\",\"kty\":\"EC\",\"x\":\"" + j.get("x").getAsString() + "\",\"y\":\"" + j.get("y").getAsString() + "\"}"));
    }
    public static String sign(PrivateKey key, String payload) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA384withECDSAinP1363Format"); s.initSign(key); s.update(payload.getBytes(StandardCharsets.UTF_8)); return base64(s.sign());
    }
    public static boolean verify(JsonObject key, String signature, String payload) {
        try { byte[] raw = decode(signature); if (raw.length != 96) return false;
            Signature s = Signature.getInstance("SHA384withECDSAinP1363Format"); s.initVerify(publicKey(key)); s.update(payload.getBytes(StandardCharsets.UTF_8)); return s.verify(raw);
        } catch (GeneralSecurityException | RuntimeException e) { return false; }
    }
    public static String contextDigest(JsonObject c) { return base64(digest(array(ProviderContract.contextValues(c)))); }
    public static String tagsDigest(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        StringJoiner entries = new StringJoiner(",", "[", "]");
        new TreeMap<>(tags).forEach((key, value) -> entries.add("[" + quote(key) + "," + quote(value) + "]"));
        return base64(digest(entries.toString()));
    }
    public static String proof(JsonObject c, String nonce, String intent) { return array(PROTOCOL, "complete", c.get("audience").getAsString(), c.get("challengeId").getAsString(), c.get("nonce").getAsString(), c.get("thumbprint").getAsString(), c.get("contextDigest").getAsString(), c.get("expiresAt").getAsLong(), nonce, intent); }
    public static boolean meetsDifficulty(byte[] bytes, int bits) { if (bits < 0 || bits > 24) return false; for (int i = 0; i < bits; i++) if ((bytes[i / 8] & (128 >> (i % 8))) != 0) return false; return true; }
    public static String request(String audience, String method, String path, long timestamp, String instance, String key, String intent, long generation, long sequence, String body) { return array(PROTOCOL, SIGNATURE, audience, method, path, timestamp, instance, key, intent, generation, sequence, base64(digest(body))); }
    public static String origin(URI u) {
        if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || u.getQuery() != null || !(u.getPath().isEmpty() || u.getPath().equals("/"))) throw new IllegalArgumentException("Invalid provider origin");
        String host = u.getHost().toLowerCase(Locale.ROOT), scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && Set.of("localhost", "127.0.0.1", "[::1]").contains(host))) throw new IllegalArgumentException("HTTPS provider required");
        int port = u.getPort(); return scheme + "://" + host + (port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80) ? "" : ":" + port);
    }
    public static String array(Object... values) {
        StringJoiner out = new StringJoiner(",", "[", "]"); for (Object v : values) out.add(v instanceof String s ? quote(s) : String.valueOf(v)); return out.toString();
    }
    public static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); switch (c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\b' -> b.append("\\b"); case '\f' -> b.append("\\f"); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
            default -> { if (c < 32 || (Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1))))) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
        }} return b.append('"').toString();
    }
}
