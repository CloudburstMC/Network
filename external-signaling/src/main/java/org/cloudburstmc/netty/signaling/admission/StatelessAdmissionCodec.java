/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.admission;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Shared NXS1 envelope. Network ID zero reserves the authenticated diagnostic extension. */
public final class StatelessAdmissionCodec {
    public static final String DIAGNOSTIC_NETWORK_ID = "0";
    public static final int DIAGNOSTIC_BYTES = 59;
    private static final Base64.Encoder BASE64 = Base64.getEncoder().withoutPadding();
    private StatelessAdmissionCodec() { }

    public record Claims(long expiresAt, String fingerprintHex, int sctpPort, int maxMessageSize,
                         String identityBindingHex, String networkId, String password, byte[] diagnostic) {
        public Claims {
            diagnostic = diagnostic.clone();
            if (expiresAt < 1000 || expiresAt % 1000 != 0 || expiresAt / 1000 > 0xffffffffL
                    || !fingerprintHex.matches("[0-9a-f]{64}") || !identityBindingHex.matches("[0-9a-f]{32}")
                    || sctpPort < 1 || sctpPort > 65535 || maxMessageSize < 1 || maxMessageSize > 262144
                    || !password.matches("[A-Za-z0-9+/]{22,91}") || !networkId.matches("0|[1-9][0-9]{0,19}")) {
                throw invalid();
            }
            Long.parseUnsignedLong(networkId);
            if (DIAGNOSTIC_NETWORK_ID.equals(networkId) ? diagnostic.length != DIAGNOSTIC_BYTES : diagnostic.length != 0) {
                throw invalid();
            }
            // A 12-byte nonce and 16-byte tag must fit the 256-character ICE ufrag limit.
            if (95 + password.length() + diagnostic.length > 186) throw invalid();
        }
        @Override public byte[] diagnostic() { return diagnostic.clone(); }
        @Override public String toString() { return "AdmissionClaims[redacted]"; }
    }
    public record Credentials(String localUfrag, String icePwd) {
        @Override public String toString() { return "AdmissionCredentials[redacted]"; }
    }
    public static byte[] encryptionKey(byte[] secret, String audience) {
        return hmac(secret, utf8("nxs-stateless-aead-v1\0" + audience));
    }
    public static String icePassword(byte[] secret, String audience, String token) {
        return BASE64.encodeToString(Arrays.copyOf(hmac(secret, utf8("nxs-stateless-ice-v1\0" + audience + "\0" + token)), 24));
    }
    public static Credentials issue(String keyId, String secret, String audience, String remoteUfrag, Claims claims, byte[] nonce) {
        if (!keyId.matches("[A-Z0-9]{4}") || utf8(secret).length < 32 || utf8(secret).length > 256 || nonce.length != 12) throw invalid();
        byte[] material = utf8(secret), key = encryptionKey(material, audience), plain = encode(claims);
        try {
            String header = "NXS1" + keyId;
            byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, nonce, aad(header, audience, remoteUfrag), plain);
            String token = header + BASE64.encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array());
            return new Credentials(token, icePassword(material, audience, token));
        } finally {
            Arrays.fill(material, (byte) 0);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
    }
    public static Claims open(byte[] encryptionKey, String audience, String token, String remoteUfrag) {
        if (token.length() > 256 || !token.matches("NXS1[A-Z0-9]{4}[A-Za-z0-9+/]+")) throw invalid();
        String encoded = token.substring(8);
        byte[] envelope = Base64.getDecoder().decode(encoded);
        if (envelope.length < 117 || envelope.length > 186 || !BASE64.encodeToString(envelope).equals(encoded)) throw invalid();
        byte[] plain = crypt(Cipher.DECRYPT_MODE, encryptionKey, Arrays.copyOf(envelope, 12),
                aad(token.substring(0, 8), audience, remoteUfrag), Arrays.copyOfRange(envelope, 12, envelope.length));
        try {
            if (plain.length < 89) throw invalid();
            ByteBuffer b = ByteBuffer.wrap(plain);
            int length = Byte.toUnsignedInt(plain[66]);
            if (67 + length > plain.length) throw invalid();
            return new Claims(Integer.toUnsignedLong(b.getInt()) * 1000, hex(plain, 4, 36),
                    Short.toUnsignedInt(b.getShort(36)), b.getInt(38), hex(plain, 42, 58),
                    Long.toUnsignedString(b.getLong(58)), new String(plain, 67, length, StandardCharsets.US_ASCII),
                    Arrays.copyOfRange(plain, 67 + length, plain.length));
        } finally { Arrays.fill(plain,(byte) 0); }
    }
    private static byte[] encode(Claims c) {
        return ByteBuffer.allocate(67 + c.password.length() + c.diagnostic.length).putInt((int) (c.expiresAt / 1000))
                .put(HexFormat.of().parseHex(c.fingerprintHex)).putShort((short) c.sctpPort).putInt(c.maxMessageSize)
                .put(HexFormat.of().parseHex(c.identityBindingHex)).putLong(Long.parseUnsignedLong(c.networkId))
                .put((byte) c.password.length()).put(utf8(c.password)).put(c.diagnostic).array();
    }
    private static byte[] aad(String header, String audience, String remote) {
        if (audience == null || audience.isEmpty() || audience.length() > 512 || audience.indexOf(0) >= 0 || !remote.matches("[A-Za-z0-9+/]{4,256}")) throw invalid();
        return utf8("nxs-stateless-admission-v1\0"+header+"\0"+audience+"\0"+remote);
    }
    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException invalid) {
            throw invalid();
        }
    }
    private static byte[] hmac(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String hex(byte[] bytes, int from, int to) { return HexFormat.of().formatHex(bytes, from, to); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid NXS admission"); }
}
