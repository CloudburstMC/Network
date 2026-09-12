package org.cloudburstmc.netty.signalling.admission;


import org.cloudburstmc.netty.util.nethernet.IdentityKeyVerifier;
import java.util.function.LongSupplier;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * NXS1 token validation using locally installed keys and the incoming ICE username.
 */
public final class StatelessAdmissionValidator implements AdmissionValidator {
    public record TicketKey(String keyId, String secret, long notBefore, long retireAfter) {
        public TicketKey(String keyId, String secret) {
            this(keyId, secret, 0, Long.MAX_VALUE);
        }

        @Override
        public String toString() {
            return "TicketKey[keyId=" + keyId + "]";
        }
    }

    private static final class Material {
        final byte[] encryption, secret;
        final long notBefore, retireAfter;
        private int references;
        private boolean installed = true, revoked;

        Material(byte[] encryption, byte[] secret, long notBefore, long retireAfter) {
            this.encryption = encryption;
            this.secret = secret;
            this.notBefore = notBefore;
            this.retireAfter = retireAfter;
        }

        synchronized void retain() { references++; }
        synchronized void release() { references--; eraseIfUnused(); }
        synchronized void retire() { installed = false; eraseIfUnused(); }
        synchronized boolean usable() { return !revoked; }
        synchronized void revoke() {
            revoked = true;
            Arrays.fill(encryption, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
        private void eraseIfUnused() { if (!installed && references == 0) revoke(); }
        synchronized boolean unused() { return !installed && references == 0; }
        synchronized boolean matches(String audience, byte[] canonicalKey, byte[] expected) {
            if (revoked) return false;
            byte[] actual = hmac("HmacSHA256", secret, utf8("nxs-identity-binding-v1\0" + audience + "\0"
                    + Base64.getEncoder().encodeToString(canonicalKey)));
            try { return MessageDigest.isEqual(expected, Arrays.copyOf(actual, 16)); }
            finally { Arrays.fill(actual, (byte) 0); }
        }
    }

    private final class Binding extends IdentityKeyVerifier {
        private final Material material;
        private final byte[] expected;
        private final long deadlineNanos;

        Binding(Material material, byte[] expected, long ttlMillis) {
            this.material = material;
            this.expected = expected;
            this.deadlineNanos = nanoTime.getAsLong() + ttlMillis * 1_000_000L;
            material.retain();
        }

        protected boolean usable() {
            return nanoTime.getAsLong() - deadlineNanos < 0 && material.usable();
        }
        protected boolean matches(byte[] key) { return material.matches(audience, key, expected); }
        protected void release() {
            Arrays.fill(expected, (byte) 0);
            material.release();
        }
    }

    private static final Base64.Encoder BASE64 = Base64.getEncoder().withoutPadding();
    private final String audience;
    private final long maxTtlMs;
    private final LongSupplier nanoTime;
    private final Set<Material> epochs = new HashSet<>();
    private volatile Map<String, Material> keys = Map.of();

    public StatelessAdmissionValidator(String audience, long maxTtlMs) {
        this(audience, maxTtlMs, System::nanoTime);
    }

    StatelessAdmissionValidator(String audience, long maxTtlMs, LongSupplier nanoTime) {
        if (audience == null || audience.isEmpty() || audience.length() > 512 || audience.indexOf(0) >= 0
                || maxTtlMs <= 0 || maxTtlMs > 120_000) {
            throw new IllegalArgumentException("Admission context");
        }
        this.audience = audience;
        this.maxTtlMs = maxTtlMs;
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    /**
     * Validates everything before atomically replacing a bounded snapshot.
     */
    public synchronized void installKeys(List<TicketKey> snapshot) {
        if (snapshot.size() > 8) {
            throw new IllegalArgumentException("At most eight admission epochs");
        }
        Set<String> ids = new HashSet<>();
        for (TicketKey key : snapshot) {
            if (key.keyId() == null || !key.keyId().matches("[A-Z0-9]{4}") || key.secret() == null
                    || key.secret().length() < 32 || key.secret().length() > 256 || !ids.add(key.keyId())
                    || key.notBefore() < 0 || key.retireAfter() <= key.notBefore()) {
                throw new IllegalArgumentException("Invalid admission key snapshot");
            }
        }
        Map<String, Material> next = new HashMap<>();
        try {
            for (TicketKey key : snapshot) {
                byte[] secret = utf8(key.secret());
                Material previous = keys.get(key.keyId());
                if (previous != null && previous.notBefore == key.notBefore() && previous.retireAfter == key.retireAfter()
                        && MessageDigest.isEqual(previous.secret, secret)) {
                    Arrays.fill(secret, (byte) 0);
                    next.put(key.keyId(), previous);
                } else {
                    next.put(key.keyId(), new Material(hmac("HmacSHA256", secret,
                            utf8("nxs-stateless-aead-v1\0" + audience)), secret, key.notBefore(), key.retireAfter()));
                }
            }
        } catch (RuntimeException failed) {
            next.values().stream().filter(m -> !keys.containsValue(m)).forEach(Material::retire);
            throw failed;
        }
        keys.values().stream().filter(m -> !next.containsValue(m)).forEach(Material::retire);
        keys = Map.copyOf(next);
        epochs.addAll(next.values());
        epochs.removeIf(Material::unused);
    }

    /** Routine rotation stops new admissions; admitted logins retain their original epoch until completion/expiry. */
    public synchronized void retireKeys(long nowMillis) {
        Map<String, Material> retained = new HashMap<>();
        for (var entry : keys.entrySet()) {
            if (entry.getValue().retireAfter <= nowMillis) entry.getValue().retire();
            else retained.put(entry.getKey(), entry.getValue());
        }
        keys = Map.copyOf(retained);
        epochs.removeIf(Material::unused);
    }

    public boolean ready() {
        return !keys.isEmpty();
    }

    public Set<String> keyIds() {
        return keys.keySet();
    }

    /** Revoke all installed and retained epochs, including pending logins. */
    public synchronized void clear() {
        epochs.forEach(Material::revoke);
        epochs.clear();
        keys = Map.of();
    }

    @Override
    public synchronized VerifiedAdmission validate(AdmissionRequest request, long nowMillis) {
        if (request == null) {
            return null;
        }
        byte[] plaintext = null;
        try {
            String token = request.localUfrag();
            if (token.length() < 8 || !token.startsWith("NXS1")) {
                return null;
            }
            String keyId = token.substring(4, 8);
            Material key = keys.get(keyId);
            if (key == null || nowMillis < key.notBefore || nowMillis >= key.retireAfter) {
                return null;
            }
            String encoded = token.substring(8);
            byte[] envelope = Base64.getDecoder().decode(encoded);
            if (envelope.length < 117 || envelope.length > 186 || !BASE64.encodeToString(envelope).equals(encoded)) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.encryption, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(envelope, 12)));
            cipher.updateAAD(utf8("nxs-stateless-admission-v1\0" + token.substring(0, 8) + "\0" + audience + "\0"
                    + request.remoteUfrag()));
            plaintext = cipher.doFinal(Arrays.copyOfRange(envelope, 12, envelope.length));
            if (plaintext.length < 89) {
                return null;
            }
            ByteBuffer body = ByteBuffer.wrap(plaintext);
            long expiresAt = Integer.toUnsignedLong(body.getInt()) * 1000;
            if (expiresAt <= nowMillis || expiresAt - nowMillis > maxTtlMs) {
                return null;
            }
            byte[] fingerprint = new byte[32];
            body.get(fingerprint);
            int sctp = Short.toUnsignedInt(body.getShort()), max = body.getInt();
            byte[] identity = new byte[16];
            body.get(identity);
            String networkId = Long.toUnsignedString(body.getLong());
            int length = Byte.toUnsignedInt(body.get());
            if (length < 22 || length > 91 || body.remaining() != length) {
                return null;
            }
            String remotePassword = new String(plaintext, 67, length, StandardCharsets.US_ASCII);
            if (sctp < 1 || max < 1 || max > 262144 || !remotePassword.matches("[A-Za-z0-9+/]{22,91}")) {
                return null;
            }
            String localPassword = BASE64.encodeToString(Arrays.copyOf(
                    hmac("HmacSHA256", key.secret, utf8("nxs-stateless-ice-v1\0" + audience + "\0" + token)), 24));
            return new VerifiedAdmission(tokenId(token), token, localPassword, request.remoteUfrag(), remotePassword,
                    "sha-256 " + HexFormat.ofDelimiter(":").withUpperCase().formatHex(fingerprint), sctp, max,
                    expiresAt,
                    networkId, HexFormat.of().formatHex(identity), keyId, new Binding(key, identity, expiresAt - nowMillis));
        } catch (Exception invalid) {
            return null;
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    public static String tokenId(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(token)), 0, 16);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(String algorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key, algorithm));
            return mac.doFinal(data);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
