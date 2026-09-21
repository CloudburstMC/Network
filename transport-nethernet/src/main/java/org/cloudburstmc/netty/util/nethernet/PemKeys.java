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
package org.cloudburstmc.netty.util.nethernet;

import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.io.pem.PemObject;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

/**
 * The PEM side of {@link OperatorIdentity}, kept apart so that a client which never touches a
 * key file does not need BouncyCastle on its classpath.
 */
final class PemKeys {
    private static final int FIELD_BYTES = 48;

    private PemKeys() {
    }

    /**
     * Reads either shape of EC private key. PKCS#8 wraps the same SEC1 structure, so the public
     * point is in the same place either way, and the file is useless to us without it.
     */
    static KeyPair read(File pem) throws GeneralSecurityException, IOException {
        // Read the file first, so anything the parser then complains about is the key's fault
        String armour = Files.readString(pem.toPath(), StandardCharsets.UTF_8);
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(armour))) {
            parsed = parser.readObject();
        } catch (IOException | RuntimeException malformed) {
            throw new GeneralSecurityException("Cannot read the EC private key in " + pem, malformed);
        }

        PrivateKeyInfo info;
        if (parsed instanceof PEMKeyPair keyPair) {
            info = keyPair.getPrivateKeyInfo();
        } else if (parsed instanceof PrivateKeyInfo only) {
            info = only;
        } else if (parsed == null) {
            throw new GeneralSecurityException("No PEM block found in " + pem);
        } else {
            throw new GeneralSecurityException("Expected an unencrypted EC private key in " + pem
                    + ", got " + parsed.getClass().getSimpleName());
        }

        ASN1BitString point;
        try {
            point = ECPrivateKey.getInstance(info.parsePrivateKey()).getPublicKey();
        } catch (IOException | RuntimeException malformed) {
            throw new GeneralSecurityException("Cannot read the EC private key in " + pem, malformed);
        }
        if (point == null) {
            throw new GeneralSecurityException("The EC private key in " + pem + " does not carry its public key. "
                    + "Regenerate it with: openssl ecparam -name secp384r1 -genkey -noout");
        }

        // Built here rather than held, so a client that never reads a PEM needs no BouncyCastle
        KeyPair pair = new JcaPEMKeyConverter().getKeyPair(new PEMKeyPair(
                new SubjectPublicKeyInfo(info.getPrivateKeyAlgorithm(), point.getBytes()), info));
        try {
            // Rejects anything that is not a point on P-384, which the assertion algorithm requires
            IdentityPublicKey.canonical(pair.getPublic());
        } catch (RuntimeException invalid) {
            throw new GeneralSecurityException("The key in " + pem + " is not on P-384", invalid);
        }
        return pair;
    }

    /**
     * Writes SEC1 rather than the PKCS#8 the JDK produces, because that drops the public point and
     * it cannot be recomputed through the standard library.
     */
    static String writeSec1(KeyPair pair) throws IOException {
        SubjectPublicKeyInfo publicKey = SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded());
        ECPrivateKey scalar = ECPrivateKey.getInstance(
                PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded()).parsePrivateKey());

        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(new PemObject("EC PRIVATE KEY", new ECPrivateKey(FIELD_BYTES * 8,
                    scalar.getKey(), publicKey.getPublicKeyData(), SECObjectIdentifiers.secp384r1).getEncoded()));
        }
        return out.toString();
    }
}
