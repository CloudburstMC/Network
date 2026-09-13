package org.cloudburstmc.netty.signalling.admission;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
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

        PrivateKey key;
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(privateKey))) {
            if (!(parser.readObject() instanceof PrivateKeyInfo info)) {
                throw new IllegalArgumentException("PKCS8 PEM private key required");
            }
            key = new JcaPEMKeyConverter().getPrivateKey(info);
        }

        String signature = switch (cert.getPublicKey().getAlgorithm()) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> throw new IllegalArgumentException("Unsupported DTLS certificate key type");
        };

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

        String fingerprint = DtlsFingerprint.format(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        return new NativeHostIdentity(certificate.toRealPath(), privateKey.toRealPath(), fingerprint);
    }

    @Override
    public String toString() {
        return "NativeHostIdentity[fingerprint=" + fingerprint + "]";
    }
}
