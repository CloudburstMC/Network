package org.cloudburstmc.netty.signalling.admission;

import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

/**
 * Validated background PEM identity; never creates a peer to obtain its fingerprint.
 */
public record NativeHostIdentity(Path certificate, Path privateKey, String fingerprint) {
    public static NativeHostIdentity load(Path certificate, Path privateKey) throws Exception {
        if (Files.size(certificate) > 65536 || Files.size(privateKey) > 65536) {
            throw new IllegalArgumentException("Oversized PEM identity");
        }
        Certificate cert;
        try (var in = Files.newInputStream(certificate)) {
            cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        String pem = Files.readString(privateKey);
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalArgumentException("PKCS8 PEM private key required");
        }
        byte[] der = Base64.getMimeDecoder()
                .decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
        try {
            String algorithm = cert.getPublicKey().getAlgorithm();
            String signature = switch (algorithm) {
                case "EC" -> "SHA256withECDSA";
                case "RSA" -> "SHA256withRSA";
                default -> throw new IllegalArgumentException("Unsupported DTLS certificate key type");
            };
            PrivateKey key = KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance(signature);
            signer.initSign(key);
            signer.update(challenge);
            byte[] signed = signer.sign();
            signer.initVerify(cert.getPublicKey());
            signer.update(challenge);
            if (!signer.verify(signed)) {
                throw new IllegalArgumentException("Certificate/private key mismatch");
            }
        } finally {
            Arrays.fill(der, (byte) 0);
        }
        String fp = HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        return new NativeHostIdentity(certificate.toRealPath(), privateKey.toRealPath(), "sha-256 " + fp);
    }

    @Override
    public String toString() {
        return "NativeHostIdentity[fingerprint=" + fingerprint + "]";
    }
}
