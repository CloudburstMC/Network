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
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
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
    private static final String CURVE = "secp384r1";
    private static final int FIELD_BYTES = 48;
    private static final byte[] OID_SECP384R1 = {0x2b, (byte) 0x81, 0x04, 0x00, 0x22};
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String domain;
    private final String token;

    public ServerIdentity(PrivateKey privateKey, PublicKey publicKey, Instant expiry,
                          String domain) throws JoseException {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.domain = domain;
        this.token = buildToken(publicKey, expiry);
    }

    /**
     * Loads the keypair from the first key entry of a PKCS12 keystore.
     *
     * @param keystore The PKCS12 keystore file, commonly named .p12 or .pfx
     * @param password The keystore password
     * @return The loaded ServerIdentity
     * @throws GeneralSecurityException If there is a security error
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static ServerIdentity fromPkcs12(File keystore,
                                              String password) throws GeneralSecurityException, IOException, JoseException {
        return fromPkcs12(keystore, password, null);
    }

    /**
     * Loads the keypair from the first key entry of a PKCS12 keystore, naming the identity
     * {@code domain} rather than the certificate's common name.
     * <p>
     * The domain is only display text; clients pin the public key, so it can be changed without
     * replacing the key and re-prompting anyone.
     *
     * @param keystore The PKCS12 keystore file
     * @param password The keystore password
     * @param domain   The identity domain, or null to take the certificate's common name
     * @return The loaded ServerIdentity
     * @throws GeneralSecurityException If there is a security error
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static ServerIdentity fromPkcs12(File keystore, String password, @Nullable String domain)
            throws GeneralSecurityException, IOException, JoseException {
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
        String subject = "";
        if (cert instanceof X509Certificate x509) {
            expiry = x509.getNotAfter().toInstant();
            subject = extractCommonName(x509.getSubjectX500Principal());
        }

        return new ServerIdentity(privateKey, publicKey, expiry, domain == null ? subject : domain);
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
     * Loads the keypair from an unencrypted PEM private key, either SEC1 {@code EC PRIVATE KEY}
     * or PKCS#8 {@code PRIVATE KEY}. The key must carry its public point, which OpenSSL includes
     * by default and the standard library cannot recover from the scalar. A PEM has no expiry,
     * so the token is issued without one.
     *
     * @param pem    The PEM file
     * @param domain The domain name for the server identity, as a PEM carries no subject
     * @return The loaded ServerIdentity
     * @throws GeneralSecurityException If the key is malformed or not on P-384
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static ServerIdentity fromPem(File pem, String domain)
            throws GeneralSecurityException, IOException, JoseException {
        byte[] der = decodePem(Files.readString(pem.toPath(), StandardCharsets.UTF_8));

        Der body = new Der(der).readSequence();
        // Version 0 marks PKCS#8, which wraps the SEC1 structure after the algorithm identifier
        if (body.readInteger().signum() == 0) {
            body.readAny(); // algorithm identifier
            body = new Der(body.readOctetString()).readSequence();
            body.readInteger(); // SEC1 version, always 1
        }

        byte[] scalar = body.readOctetString();
        byte[] point = null;
        while (body.hasNext() && point == null) {
            int tag = body.peekTag();
            byte[] content = body.readAny();
            if (tag == 0xa1) { // [1] EXPLICIT publicKey BIT STRING
                point = new Der(content).readBitString();
            }
        }
        if (point == null) {
            throw new GeneralSecurityException("The EC private key in " + pem + " does not carry its public key. "
                    + "Regenerate it with: openssl ecparam -name secp384r1 -genkey -noout");
        }

        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);

        KeyFactory factory = KeyFactory.getInstance("EC");
        PrivateKey privateKey = factory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), spec));
        PublicKey publicKey = factory.generatePublic(new ECPublicKeySpec(decodePoint(point), spec));
        return new ServerIdentity(privateKey, publicKey, null, domain);
    }

    /**
     * Loads the identity from a PEM private key, creating a new P-384 key there on first use.
     * <p>
     * The file is created readable only by its owner and is never replaced once it exists, because
     * clients pin the public key and a new one prompts every returning player again.
     *
     * @param pem    The PEM file to load or create
     * @param domain The identity domain, as a PEM carries no subject
     * @return The loaded ServerIdentity
     * @throws GeneralSecurityException If the key is malformed or not on P-384
     * @throws IOException              If there is an I/O error
     * @throws JoseException            If there is an error creating the JWT
     */
    public static ServerIdentity fromPemOrCreate(File pem, String domain)
            throws GeneralSecurityException, IOException, JoseException {
        Path path = pem.toPath();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("The identity key must not be a symbolic link: " + pem);
        }

        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class);

        if (Files.exists(path)) {
            if (posix) {
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
            Files.writeString(temporary, writeSec1Pem(pair), StandardCharsets.UTF_8);
            // Never replace a key another process created in the meantime
            Files.move(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return fromPem(pem, domain);
    }

    /**
     * Writes SEC1 rather than the PKCS#8 the JDK produces, because that drops the public point and
     * it cannot be recomputed through the standard library.
     */
    private static String writeSec1Pem(KeyPair pair) throws GeneralSecurityException {
        ECPrivateKey privateKey = (ECPrivateKey) pair.getPrivate();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();

        byte[] point = new byte[1 + 2 * FIELD_BYTES];
        point[0] = 0x04;
        unsigned(publicKey.getW().getAffineX(), point, 1);
        unsigned(publicKey.getW().getAffineY(), point, 1 + FIELD_BYTES);

        byte[] scalar = new byte[FIELD_BYTES];
        unsigned(privateKey.getS(), scalar, 0);

        byte[] body = concat(
                tlv(0x02, new byte[]{1}),
                tlv(0x04, scalar),
                tlv(0xa0, tlv(0x06, OID_SECP384R1)),
                tlv(0xa1, tlv(0x03, concat(new byte[]{0}, point))));

        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(tlv(0x30, body));
        return "-----BEGIN EC PRIVATE KEY-----\n" + base64 + "\n-----END EC PRIVATE KEY-----\n";
    }

    private static void unsigned(BigInteger value, byte[] out, int offset) throws GeneralSecurityException {
        byte[] bytes = value.toByteArray();
        int from = 0;
        while (from < bytes.length - 1 && bytes[from] == 0) {
            from++;
        }
        int length = bytes.length - from;
        if (length > FIELD_BYTES) {
            throw new GeneralSecurityException("Key component is wider than the P-384 field");
        }
        System.arraycopy(bytes, from, out, offset + FIELD_BYTES - length, length);
    }

    private static byte[] tlv(int tag, byte[] content) {
        byte[] length;
        if (content.length < 0x80) {
            length = new byte[]{(byte) content.length};
        } else if (content.length < 0x100) {
            length = new byte[]{(byte) 0x81, (byte) content.length};
        } else {
            length = new byte[]{(byte) 0x82, (byte) (content.length >> 8), (byte) content.length};
        }
        return concat(new byte[]{(byte) tag}, length, content);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] decodePem(String pem) throws GeneralSecurityException {
        StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (String line : pem.split("\\r\\n|\\n|\\r")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----BEGIN")) {
                inside = true;
            } else if (trimmed.startsWith("-----END")) {
                break;
            } else if (inside) {
                body.append(trimmed);
            }
        }
        if (body.isEmpty()) {
            throw new GeneralSecurityException("No PEM block found");
        }
        try {
            return Base64.getDecoder().decode(body.toString());
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("The PEM body is not valid base64", e);
        }
    }

    /**
     * Decodes an uncompressed {@code 0x04 || X || Y} point.
     */
    private static ECPoint decodePoint(byte[] point) throws GeneralSecurityException {
        if (point.length != 1 + 2 * FIELD_BYTES || point[0] != 0x04) {
            throw new GeneralSecurityException("Expected an uncompressed P-384 public key point, got "
                    + point.length + " bytes");
        }
        return new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + FIELD_BYTES)),
                new BigInteger(1, Arrays.copyOfRange(point, 1 + FIELD_BYTES, point.length)));
    }

    /**
     * The slice of DER needed to walk a private key structure: tag, length, value.
     */
    private static final class Der {
        private final byte[] buffer;
        private int offset;
        private final int limit;

        private Der(byte[] buffer) {
            this(buffer, 0, buffer.length);
        }

        private Der(byte[] buffer, int offset, int limit) {
            this.buffer = buffer;
            this.offset = offset;
            this.limit = limit;
        }

        private boolean hasNext() {
            return this.offset < this.limit;
        }

        private int peekTag() throws GeneralSecurityException {
            if (!hasNext()) {
                throw new GeneralSecurityException("Truncated DER");
            }
            return this.buffer[this.offset] & 0xff;
        }

        private Der readSequence() throws GeneralSecurityException {
            expect(0x30);
            int length = readLength();
            Der nested = new Der(this.buffer, this.offset, this.offset + length);
            this.offset += length;
            return nested;
        }

        private BigInteger readInteger() throws GeneralSecurityException {
            expect(0x02);
            return new BigInteger(readValue());
        }

        private byte[] readOctetString() throws GeneralSecurityException {
            expect(0x04);
            return readValue();
        }

        private byte[] readBitString() throws GeneralSecurityException {
            expect(0x03);
            byte[] value = readValue();
            if (value.length == 0 || value[0] != 0) {
                throw new GeneralSecurityException("Expected a whole number of bytes in the bit string");
            }
            return Arrays.copyOfRange(value, 1, value.length);
        }

        /**
         * Reads one element of any tag and returns its contents.
         */
        private byte[] readAny() throws GeneralSecurityException {
            peekTag();
            this.offset++;
            return readValue();
        }

        private void expect(int tag) throws GeneralSecurityException {
            if (peekTag() != tag) {
                throw new GeneralSecurityException(String.format("Expected DER tag 0x%02x, got 0x%02x",
                        tag, peekTag()));
            }
            this.offset++;
        }

        private byte[] readValue() throws GeneralSecurityException {
            int length = readLength();
            byte[] value = Arrays.copyOfRange(this.buffer, this.offset, this.offset + length);
            this.offset += length;
            return value;
        }

        private int readLength() throws GeneralSecurityException {
            if (this.offset >= this.limit) {
                throw new GeneralSecurityException("Truncated DER length");
            }
            int first = this.buffer[this.offset++] & 0xff;
            if (first < 0x80) {
                return checked(first);
            }
            int count = first & 0x7f;
            if (count == 0 || count > 4) {
                throw new GeneralSecurityException("Unsupported DER length of " + count + " bytes");
            }
            int length = 0;
            for (int i = 0; i < count; i++) {
                if (this.offset >= this.limit) {
                    throw new GeneralSecurityException("Truncated DER length");
                }
                length = (length << 8) | (this.buffer[this.offset++] & 0xff);
            }
            return checked(length);
        }

        private int checked(int length) throws GeneralSecurityException {
            if (length < 0 || this.offset + length > this.limit) {
                throw new GeneralSecurityException("DER element runs past the end of its parent");
            }
            return length;
        }
    }

    /**
     * The keypair this identity signs with, for callers that also have to sign as a client.
     *
     * @return The keypair
     */
    public KeyPair keyPair() {
        return new KeyPair(this.publicKey, this.privateKey);
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
