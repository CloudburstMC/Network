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

import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.lang.JoseException;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.List;


/**
 * The key an operator signs NetherNet identity assertions with, and the name it carries.
 * <p>
 * The name goes out as the assertion's identity provider and as the token issuer. Clients pin the
 * key, and nothing displays the name today, so it is free to change.
 * <p>
 * A server presents it in every answer. A client presents it in its offer, derived for the player
 * it connects on behalf of with {@link #forPlayer}, which is what a proxy does toward a downstream
 * server.
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/7330880ab78ef001cad0b9cdfedb3aa3eaa6d4af/NetherNetOnboardingGuide.md#52-producing-the-server-assertion-in-the-answer">NetherNet onboarding guide, section 5.2</a>
 */
public class OperatorIdentity {
    private static final String ALG = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384; // ES384 / P-384
    private static final String CURVE = "secp384r1";
    /** A token issued for one player's connection only needs to outlive that connection attempt. */
    private static final Duration PLAYER_TOKEN_LIFETIME = Duration.ofHours(1);
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String domain;
    private final String token;

    public OperatorIdentity(PrivateKey privateKey, PublicKey publicKey, Instant expiry,
                            String domain) throws JoseException {
        this(privateKey, publicKey, expiry, domain, null, null);
    }

    private OperatorIdentity(PrivateKey privateKey, PublicKey publicKey, Instant expiry, String domain,
                             String xuid, String name) throws JoseException {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.domain = domain;
        this.token = buildToken(publicKey, expiry, xuid, name);
    }

    private OperatorIdentity(KeyPair keyPair, String domain, String token) {
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.domain = domain;
        this.token = token;
    }

    /**
     * An identity whose token an auth service issued for the key, as a retail client's multiplayer
     * token is: what a client presents to a server that validates against Minecraft's auth service
     * rather than trusting the peer's own signature. The key has to be the one the token's
     * {@code cpk} claim names, or the fingerprint signature will not verify.
     *
     * @param keyPair The key the token was issued for
     * @param token   The compact JWT the auth service issued, carrying {@code cpk}, {@code xid} and
     *                {@code xname}
     * @param domain  The name the identity carries, see above
     * @return An identity presenting that token
     */
    public static OperatorIdentity fromToken(KeyPair keyPair, String token, String domain) {
        return new OperatorIdentity(keyPair, domain, token);
    }

    /**
     * Generate a brand-new server identity that is not stored
     *
     * @param domain The name the identity carries, see above
     * @return A new OperatorIdentity instance
     * @throws JoseException If there is an error creating the JWT
     */
    public static OperatorIdentity generate(String domain) throws JoseException {
        EllipticCurveJsonWebKey jwk = EcJwkGenerator.generateJwk(EllipticCurves.P384);
        return new OperatorIdentity(jwk.getPrivateKey(), jwk.getPublicKey(), null, domain);
    }


    /**
     * Loads the keypair from an unencrypted PEM private key, either SEC1 {@code EC PRIVATE KEY}
     * or PKCS#8 {@code PRIVATE KEY}. The key must carry its public point, which OpenSSL includes
     * by default and the standard library cannot recover from the scalar. A PEM has no expiry,
     * so the token is issued without one.
     *
     * @param pem    The PEM file
     * @param domain The name the identity carries, see above, as a PEM carries no subject
     * @return The loaded OperatorIdentity
     * @throws GeneralSecurityException If the key is malformed or not on P-384
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static OperatorIdentity fromPem(File pem, String domain)
            throws GeneralSecurityException, IOException, JoseException {
        KeyPair pair = PemKeys.read(pem);
        return new OperatorIdentity(pair.getPrivate(), pair.getPublic(), null, domain);
    }

    /**
     * Loads the identity from a PEM private key, creating a new P-384 key there on first use.
     * <p>
     * The file is created readable only by its owner and is never replaced once it exists, because
     * clients pin the public key and a new one prompts every returning player again.
     *
     * @param pem    The PEM file to load or create
     * @param domain The name the identity carries, see above, as a PEM carries no subject
     * @return The loaded OperatorIdentity
     * @throws GeneralSecurityException If the key is malformed or not on P-384
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static OperatorIdentity fromPemOrCreate(File pem, String domain)
            throws GeneralSecurityException, IOException, JoseException {
        Path path = pem.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("The identity key must not be a symbolic link: " + pem);
        }

        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class);

        if (Files.exists(path)) {
            // Tighten only when it is actually too wide, because the key may sit on a
            // read-only mount, such as a Kubernetes secret volume, where the chmod fails.
            if (posix && !OWNER_ONLY.containsAll(Files.getPosixFilePermissions(path))) {
                Files.setPosixFilePermissions(path, OWNER_ONLY);
            }
            return fromPem(pem, domain);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE));
        KeyPair pair = generator.generateKeyPair();

        FileAttribute<?>[] attributes = posix
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY)}
                : new FileAttribute<?>[0];
        Path temporary = Files.createTempFile(directory, ".identity-", ".pem", attributes);
        try {
            Files.writeString(temporary, PemKeys.writeSec1(pair), StandardCharsets.UTF_8);
            // Never replace a key another process created in the meantime
            Files.move(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return fromPem(pem, domain);
    }

    /**
     * The key players pin this operator by, for fingerprints and fleet checks.
     *
     * @return The public key
     */
    public PublicKey publicKey() {
        return this.publicKey;
    }

    /**
     * This identity acting for a player, for the offer a client sends on their behalf. The token
     * names the player and expires, so derive one per connection rather than keeping it.
     *
     * @param xuid The player's XUID, which the server reads as {@code xid}
     * @param name The player's name, read as {@code xname}
     * @return An identity with the same key and domain whose token carries the player
     * @throws JoseException If there is an error signing the token
     */
    public OperatorIdentity forPlayer(String xuid, String name) throws JoseException {
        return new OperatorIdentity(privateKey, publicKey, Instant.now().plus(PLAYER_TOKEN_LIFETIME), domain,
                xuid, name);
    }

    /**
     * Build a JWT token with the given public key and expiry.
     *
     * @param publicKey The public key to include in the token
     * @param expiry    The expiration time of the token, or null for none
     * @param xuid      The player this identity acts for, or null when it acts as a host
     * @param name      The player's name, ignored without a xuid
     * @return The signed JWT token
     * @throws JoseException If there is an error signing the token
     */
    private String buildToken(PublicKey publicKey, Instant expiry, String xuid, String name) throws JoseException {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", IdentityUtils.encodePublicKey(publicKey)); // Custom claim required by the NetherNet spec
        if (xuid != null) {
            claims.setClaim("xid", xuid);
            claims.setClaim("xname", name == null ? "" : name);
        }
        claims.setIssuedAtToNow();

        // The name also goes out as the issuer
        if (domain != null && !domain.isBlank()) {
            claims.setIssuer(domain);
        }

        // Mirror the certificate expiry if set
        if (expiry != null) {
            claims.setExpirationTime(NumericDate.fromMilliseconds(expiry.toEpochMilli()));
        }

        return sign(claims.toJson());
    }

    /**
     * Sign the payload with the private key and return the compact JWS serialization.
     *
     * @param payload The payload to sign
     * @return The compact JWS serialization
     * @throws JoseException If there is an error signing the payload
     */
    private String sign(String payload) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(payload);
        jws.setKey(privateKey);
        jws.setAlgorithmHeaderValue(ALG);
        return jws.getCompactSerialization();
    }

    /**
     * The identity attribute value for a description: the token and a detached signature over the
     * description's fingerprints, which is what ties the assertion to the DTLS certificate.
     */
    private String identityValue(String sdp) throws JoseException {
        String[] fingerprintParts = sign(IdentityUtils.getCanonicalFingerprintJson(sdp)).split("\\.");
        String fingerprints = fingerprintParts[0] + ".." + fingerprintParts[2];

        Identity.Assertion assertion = new Identity.Assertion(token, fingerprints);
        Identity.Idp idp = new Identity.Idp(domain, "default");
        return new Identity(idp, assertion).toBase64();
    }

    /**
     * Inserts this identity's assertion into a description, offer or answer. The description has
     * to be finished, because a peer checks the signature against exactly the fingerprints it
     * receives, and the attribute goes ahead of the first media line as the spec requires.
     *
     * @param sdp The description without an identity line
     * @return The description with the identity inserted
     * @throws JoseException If there is an error signing
     */
    public String withAssertion(String sdp) throws JoseException {
        String line = "a=identity:" + identityValue(sdp);
        String eol = sdp.contains("\r\n") ? "\r\n" : "\n";

        String[] lines = sdp.split("\r\n|\n", -1);
        List<String> out = new ArrayList<>(lines.length + 1);

        boolean inserted = false;
        for (String current : lines) {
            if (!inserted && current.startsWith("m=")) {
                out.add(line);
                inserted = true;
            }
            out.add(current);
        }

        if (!inserted) {
            out.add(line);
        }

        return String.join(eol, out);
    }
}
