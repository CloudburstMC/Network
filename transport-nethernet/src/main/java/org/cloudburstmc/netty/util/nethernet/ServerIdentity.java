package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwk.EcJwkGenerator;
import org.jose4j.jwk.EllipticCurveJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.lang.JoseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/**
 * Produces the server-side identity assertion for each SDP answer
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/7330880ab78ef001cad0b9cdfedb3aa3eaa6d4af/NetherNetOnboardingGuide.md#52-producing-the-server-assertion-in-the-answer">NetherNet onboarding guide, section 5.2</a>
 */
public class ServerIdentity {
    private static final String ALG = AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384; // ES384 / P-384

    private final PrivateKey privateKey;
    private final String domain;
    private final String token;

    public ServerIdentity(PrivateKey privateKey, PublicKey publicKey, Instant expiry,
                          String domain) throws JoseException {
        this.privateKey = privateKey;
        this.domain = domain;
        this.token = buildToken(publicKey, expiry);
    }

    /**
     * Loads the keypair from the first key entry of a PKCS12 keystore.
     *
     * @param keystore The PKCS12 keystore file
     * @param password The keystore password
     * @return The loaded ServerIdentity
     * @throws GeneralSecurityException If there is a security error
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static ServerIdentity fromKeystore(File keystore,
                                              String password) throws GeneralSecurityException, IOException, JoseException {
        char[] pwd = password.toCharArray();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(keystore)) {
            ks.load(fis, pwd);
        }

        // Find the first key in the keystore and extract the certificate
        String alias = findKeyAlias(ks);
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, pwd);
        Certificate cert = ks.getCertificate(alias);
        PublicKey publicKey = cert.getPublicKey();

        // Extract the expiry and common name from the cert if they exist
        Instant expiry = null;
        String domain = "";
        if (cert instanceof X509Certificate x509) {
            expiry = x509.getNotAfter().toInstant();
            domain = extractCommonName(x509.getSubjectX500Principal());
        }

        return new ServerIdentity(privateKey, publicKey, expiry, domain);
    }

    /**
     * Finds the first key entry alias in a keystore.
     *
     * @param keyStore The keystore to search
     * @return The alias of the first key entry
     * @throws KeyStoreException If no key entry is found
     */
    private static String findKeyAlias(KeyStore keyStore) throws KeyStoreException {
        for (String candidate : Collections.list(keyStore.aliases())) {
            if (keyStore.isKeyEntry(candidate)) {
                return candidate;
            }
        }
        throw new KeyStoreException("No private key entry found in identity keystore");
    }

    /**
     * Search the principal and extract the common name
     *
     * @param principal The X500Principal to extract the common name from
     * @return The common name, or an empty string if not found
     */
    private static String extractCommonName(X500Principal principal) {
        try {
            LdapName name = new LdapName(principal.getName());
            for (Rdn rdn : name.getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException ignored) {
        }
        return "";
    }

    /**
     * Generate a brand-new server identity that is not stored
     *
     * @param domain The domain name for the server identity
     * @return A new ServerIdentity instance
     * @throws JoseException If there is an error creating the JWT
     */
    public static ServerIdentity generate(String domain) throws JoseException {
        EllipticCurveJsonWebKey jwk = EcJwkGenerator.generateJwk(EllipticCurves.P384);
        return new ServerIdentity(jwk.getPrivateKey(), jwk.getPublicKey(), null, domain);
    }

    /**
     * Build a JWT token with the given public key and expiry.
     *
     * @param publicKey The public key to include in the token
     * @param expiry    The expiration time of the token
     * @return The signed JWT token
     * @throws JoseException If there is an error signing the token
     */
    private String buildToken(PublicKey publicKey, Instant expiry) throws JoseException {
        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder()
                .encodeToString(publicKey.getEncoded())); // Custom claim required by the NetherNet spec
        claims.setIssuedAtToNow();

        // If we have a domain set it as the isser as it could be shown to the user
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
     * Generate the identity value as base64 for this answer SDP
     *
     * @param answerSdp The SDP to generate the identity value for
     * @return The base64 identity value
     * @throws JoseException If there is an error signing the identity value
     */
    public String identityValue(String answerSdp) throws JoseException {
        // Generate and sign the fingerprint
        String[] fingerprintParts = sign(IdentityUtils.getCanonicalFingerprintJson(answerSdp)).split("\\.");
        String fingerprints = fingerprintParts[0] + ".." + fingerprintParts[2];

        Identity.Assertion assertion = new Identity.Assertion(token, fingerprints);
        Identity.Idp idp = new Identity.Idp(domain, "default");
        return new Identity(idp, assertion).toBase64();
    }

    /**
     * Insert the identity into the answer SDP
     * The specific placement is a strange requirement for the spec but we will follow it
     *
     * @param answerSdp The SDP to insert the identity into
     * @return The SDP with the identity inserted
     * @throws JoseException If there is an error signing the identity value
     */
    public String augmentAnswer(String answerSdp) throws JoseException {
        String line = "a=identity:" + identityValue(answerSdp);
        String eol = answerSdp.contains("\r\n") ? "\r\n" : "\n";

        String[] lines = answerSdp.split("\r\n|\n", -1);
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
