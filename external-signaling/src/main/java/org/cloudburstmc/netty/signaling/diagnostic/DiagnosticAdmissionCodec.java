/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import org.cloudburstmc.netty.signaling.control.ControlOrigin;
import org.cloudburstmc.netty.signaling.admission.StatelessAdmissionCodec;
import org.cloudburstmc.netty.signaling.admission.VerifiedAdmission;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Diagnostic metadata carried by the shared NXS1 admission envelope with network ID zero. */
public final class DiagnosticAdmissionCodec {
    private DiagnosticAdmissionCodec() {}

    public static final int PROFILE = 1,
            ASSISTED_PROFILE = 2,
            SCTP_PORT = 5000,
            MAX_MESSAGE_SIZE = 262144;
    public static final int MAX_ATTEMPT_MILLIS = 60_000, MAX_HANDSHAKE_MILLIS = 15_000;
    static final long SAFE = 9007199254740991L;

    public record Context(
            String providerOrigin, String hostId, String incarnation, long generation) {
        public Context {
            if (providerOrigin == null
                    || providerOrigin.length() > 256
                    || !providerOrigin.startsWith("https://")
                    || !ControlOrigin.isCanonical(providerOrigin)
                    || !hostId.matches("[A-Za-z0-9_-]{1,128}")) {
                throw invalid();
            }
            unhex(incarnation, 16);
            integer(generation, 1, SAFE);
        }
    }

    public record Key(String keyId, String secret, long notBefore, long retireAt) {
        public Key {
            if (!keyId.matches("[A-Z0-9]{4}")
                    || secret.length() > 256
                    || utf8(secret).length < 32
                    || utf8(secret).length > 256
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(secret)) {
                throw invalid();
            }
            integer(notBefore, 0, SAFE);
            integer(retireAt, notBefore + 1, SAFE);
        }

        @Override
        public String toString() {
            return "DiagnosticKey[id=" + keyId + "]";
        }
    }

    public record Claims(
            long expiresAt,
            String clientFingerprintHex,
            String clientIcePwd,
            String attemptIdHex,
            String offerDigestHex,
            long candidateRevision,
            int family,
            String targetAddressHex,
            int targetPort,
            int profile) {
        public Claims {
            integer(expiresAt, 1000, 0xffffffffL * 1000);
            integer(candidateRevision, 1, SAFE);
            integer(targetPort, profile == ASSISTED_PROFILE ? 0 : 1, 65535);
            if (expiresAt % 1000 != 0
                    || (profile != PROFILE && profile != ASSISTED_PROFILE)
                    || (family != 4 && family != 6)
                    || !clientIcePwd.matches("[A-Za-z0-9+/]{22,30}")) {
                throw invalid();
            }
            if (profile == ASSISTED_PROFILE
                    && (targetPort != 0 || !"00".repeat(16).equals(targetAddressHex))) {
                throw invalid();
            }
            unhex(clientFingerprintHex, 32);
            unhex(attemptIdHex, 16);
            unhex(offerDigestHex, 32);
            byte[] address = unhex(targetAddressHex, 16);
            if (family == 4
                    ? !Arrays.equals(Arrays.copyOf(address, 12), new byte[12])
                    : targetAddressHex.startsWith("00000000000000000000ffff")) {
                throw invalid();
            }
        }

        @Override
        public String toString() {
            return "DiagnosticClaims[attempt=" + attemptIdHex + "]";
        }
    }

    public record Clock(LongSupplier wallMillis, LongSupplier nanoTime) {
        public Clock {
            Objects.requireNonNull(wallMillis);
            Objects.requireNonNull(nanoTime);
        }

        public static Clock system() {
            return new Clock(System::currentTimeMillis, System::nanoTime);
        }
    }

    public record Admission(
            Context context, Claims claims, Credentials credentials, String remoteUfrag) {
        @Override
        public String toString() {
            return "DiagnosticAdmission[attempt=" + claims.attemptIdHex() + "]";
        }
    }

    public record Credentials(String localUfrag, String icePwd) {
        @Override
        public String toString() {
            return "DiagnosticCredentials[redacted]";
        }
    }

    public static int ufragLength(int passwordBytes) {
        if (passwordBytes < 0 || passwordBytes > 65535) {
            throw invalid();
        }
        return 8 + ((154 + passwordBytes) * 4 + 2) / 3;
    }

    private static String audience(Context context) {
        return "nxs-stateless-host-v1/" + context.incarnation();
    }

    public static Credentials issue(
            Context context,
            Key key,
            Claims claims,
            String remoteUfrag,
            byte[] offer,
            long parentExpiresAt,
            Clock clock) {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return issueWithNonce(
                context, key, claims, remoteUfrag, offer, parentExpiresAt, clock, nonce);
    }

    /** Deterministic fixtures only. Production callers use issue(). */
    public static Credentials issueWithNonce(
            Context context,
            Key key,
            Claims claims,
            String remoteUfrag,
            byte[] offer,
            long parentExpiresAt,
            Clock clock,
            byte[] nonce) {
        long now = clock.wallMillis.getAsLong(), nanos = clock.nanoTime.getAsLong();
        deadline(key, claims, now, parentExpiresAt);
        DiagnosticSdp.offer(offer, claims, remoteUfrag);
        var payload =
                new StatelessAdmissionCodec.Claims(
                        claims.expiresAt,
                        claims.clientFingerprintHex,
                        SCTP_PORT,
                        MAX_MESSAGE_SIZE,
                        claims.attemptIdHex,
                        "0",
                        claims.clientIcePwd,
                        encode(claims));
        var credentials =
                StatelessAdmissionCodec.issue(
                        key.keyId, key.secret, audience(context), remoteUfrag, payload, nonce);
        deadline(key, claims, clock.wallMillis.getAsLong(), parentExpiresAt);
        long elapsed = clock.nanoTime.getAsLong() - nanos;
        if (elapsed < 0 || elapsed >= (claims.expiresAt - now) * 1_000_000L) {
            throw invalid();
        }
        return new Credentials(credentials.localUfrag(), credentials.icePwd());
    }

    public static Admission open(
            Context context,
            Key key,
            String localUfrag,
            String remoteUfrag,
            long parentExpiresAt,
            Clock clock) {
        byte[] secret = utf8(key.secret),
                encryption = StatelessAdmissionCodec.encryptionKey(secret, audience(context));
        try {
            if (!localUfrag.startsWith("NXS1" + key.keyId)) {
                return null;
            }
            var payload =
                    StatelessAdmissionCodec.open(
                            encryption, audience(context), localUfrag, remoteUfrag);
            return verified(
                    context,
                    key,
                    payload,
                    new Credentials(
                            localUfrag,
                            StatelessAdmissionCodec.icePassword(
                                    secret, audience(context), localUfrag)),
                    remoteUfrag,
                    parentExpiresAt,
                    clock);
        } catch (RuntimeException invalid) {
            return null;
        } finally {
            Arrays.fill(secret, (byte) 0);
            Arrays.fill(encryption, (byte) 0);
        }
    }

    static Admission verified(
            Context context,
            Key key,
            VerifiedAdmission admission,
            long parentExpiresAt,
            Clock clock) {
        byte[] secret = utf8(key.secret);
        try {
            if (!admission
                    .localPassword()
                    .equals(
                            StatelessAdmissionCodec.icePassword(
                                    secret, audience(context), admission.localUfrag()))) {
                return null;
            }
            var payload =
                    new StatelessAdmissionCodec.Claims(
                            admission.expiresAt(),
                            admission
                                    .remoteFingerprint()
                                    .substring(8)
                                    .replace(":", "")
                                    .toLowerCase(),
                            admission.remoteSctpPort(),
                            admission.remoteMaxMessageSize(),
                            admission.identityBindingHex(),
                            admission.networkId(),
                            admission.remotePassword(),
                            admission.diagnosticData());
            return verified(
                    context,
                    key,
                    payload,
                    new Credentials(admission.localUfrag(), admission.localPassword()),
                    admission.remoteUfrag(),
                    parentExpiresAt,
                    clock);
        } catch (RuntimeException invalid) {
            return null;
        } finally {
            Arrays.fill(secret, (byte) 0);
            admission.identityVerifier().close();
        }
    }

    private static Admission verified(
            Context context,
            Key key,
            StatelessAdmissionCodec.Claims payload,
            Credentials credentials,
            String remote,
            long parent,
            Clock clock) {
        if (!"0".equals(payload.networkId())
                || payload.sctpPort() != SCTP_PORT
                || payload.maxMessageSize() != MAX_MESSAGE_SIZE) {
            throw invalid();
        }
        Claims claims = decode(payload);
        long now = clock.wallMillis.getAsLong();
        deadline(key, claims, now, parent);
        return new Admission(context, claims, credentials, remote);
    }

    static byte[] encode(Claims c) {
        return ByteBuffer.allocate(59)
                .put(unhex(c.offerDigestHex, 32))
                .putLong(c.candidateRevision)
                .put((byte) (c.profile | (c.family == 6 ? 128 : 0)))
                .put(unhex(c.targetAddressHex, 16))
                .putShort((short) c.targetPort)
                .array();
    }

    private static Claims decode(StatelessAdmissionCodec.Claims c) {
        byte[] bytes = c.diagnostic();
        if (bytes.length != 59) {
            throw invalid();
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int packed = Byte.toUnsignedInt(bytes[40]);
        if (packed != 1 && packed != 129 && packed != 2 && packed != 130) {
            throw invalid();
        }
        return new Claims(
                c.expiresAt(),
                c.fingerprintHex(),
                c.password(),
                c.identityBindingHex(),
                hex(Arrays.copyOf(bytes, 32)),
                b.getLong(32),
                (packed & 128) != 0 ? 6 : 4,
                hex(Arrays.copyOfRange(bytes, 41, 57)),
                Short.toUnsignedInt(b.getShort(57)),
                packed & 127);
    }

    static void deadline(Key k, Claims c, long now, long parent) {
        integer(now, 0, SAFE);
        integer(parent, 1, SAFE);
        if (now < k.notBefore
                || now >= c.expiresAt
                || c.expiresAt - now > MAX_ATTEMPT_MILLIS
                || c.expiresAt > k.retireAt
                || c.expiresAt > parent) {
            throw invalid();
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String ufrag(String s) {
        if (!s.matches("[A-Za-z0-9+/]{4,256}")) {
            throw invalid();
        }
        return s;
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base64(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] unbase64(String s) {
        if (s.length() > 248 || !s.matches("[A-Za-z0-9+/]+")) {
            throw invalid();
        }
        byte[] b = Base64.getDecoder().decode(s);
        if (!base64(b).equals(s)) {
            throw invalid();
        }
        return b;
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] unhex(String s, int n) {
        if (!s.matches("[0-9a-f]{" + (2 * n) + "}")) {
            throw invalid();
        }
        return HexFormat.of().parseHex(s);
    }

    static void integer(long n, long min, long max) {
        if (n < min || n > max) {
            throw invalid();
        }
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Diagnostic value invalid or unauthorized");
    }

    /** Numeric only; canonical packed bytes are the comparison representation. No DNS or target authorization. */
    public static String address(int family, String text) {
        if (family == 4) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 4) {
                throw invalid();
            }
            byte[] out = new byte[16];
            for (int i = 0; i < 4; i++) {
                if (!parts[i].matches("0|[1-9][0-9]{0,2}")) {
                    throw invalid();
                }
                int n = Integer.parseInt(parts[i]);
                if (n > 255) {
                    throw invalid();
                }
                out[i + 12] = (byte) n;
            }
            return hex(out);
        }
        if (family != 6 || !text.matches("[0-9A-Fa-f:]+")) {
            throw invalid();
        }
        String[] sides = text.split("::", -1);
        if (sides.length > 2) {
            throw invalid();
        }
        String[] left = sides[0].isEmpty() ? new String[0] : sides[0].split(":", -1),
                right =
                        sides.length < 2 || sides[1].isEmpty()
                                ? new String[0]
                                : sides[1].split(":", -1);
        if (sides.length == 1 ? left.length != 8 : left.length + right.length >= 8) {
            throw invalid();
        }
        ByteBuffer b = ByteBuffer.allocate(16);
        for (String word : left) {
            if (!word.matches("[0-9A-Fa-f]{1,4}")) {
                throw invalid();
            }
            b.putShort((short) Integer.parseInt(word, 16));
        }
        b.position(16 - right.length * 2);
        for (String word : right) {
            if (!word.matches("[0-9A-Fa-f]{1,4}")) {
                throw invalid();
            }
            b.putShort((short) Integer.parseInt(word, 16));
        }
        String result = hex(b.array());
        if (result.startsWith("00000000000000000000ffff")) {
            throw invalid();
        }
        return result;
    }
}
