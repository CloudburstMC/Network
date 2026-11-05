/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling.admission;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

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
