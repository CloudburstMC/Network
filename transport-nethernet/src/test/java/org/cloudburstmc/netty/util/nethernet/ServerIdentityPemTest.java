package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.security.spec.ECGenParameterSpec;
import java.security.KeyPairGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Throwaway keys from {@code openssl ecparam -name secp384r1 -genkey -noout}. Never deploy them. */
class ServerIdentityPemTest {

    private static final String SEC1 =
            "-----BEGIN EC PRIVATE KEY-----\n" +
            "MIGkAgEBBDDmCV/icghwrAdKuvy8s6iJc7J5SdH9Ks43hq7Bw1JO/d0sgdHGbe30\n" +
            "mKHzlu5+GW6gBwYFK4EEACKhZANiAAQK7XU8ZiZox723S4u5U01a1Uioo5TMCuT7\n" +
            "ozPRWmm431vyOC9i+irVFLOTjcvwAY3D3+T7RGG1Y1/F0CPoO8tP3ClHbOeCgINA\n" +
            "qGQgpH3NW/5D6hvZubCqcEKvV2igcoA=\n" +
            "-----END EC PRIVATE KEY-----\n";

    /** The same key, run through {@code openssl pkcs8 -topk8 -nocrypt}. */
    private static final String PKCS8 =
            "-----BEGIN PRIVATE KEY-----\n" +
            "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDDmCV/icghwrAdKuvy8\n" +
            "s6iJc7J5SdH9Ks43hq7Bw1JO/d0sgdHGbe30mKHzlu5+GW6hZANiAAQK7XU8ZiZo\n" +
            "x723S4u5U01a1Uioo5TMCuT7ozPRWmm431vyOC9i+irVFLOTjcvwAY3D3+T7RGG1\n" +
            "Y1/F0CPoO8tP3ClHbOeCgINAqGQgpH3NW/5D6hvZubCqcEKvV2igcoA=\n" +
            "-----END PRIVATE KEY-----\n";

    /** The same key with {@code -no_public}, which the loader cannot use. */
    private static final String NO_PUBLIC =
            "-----BEGIN EC PRIVATE KEY-----\n" +
            "MD4CAQEEMOYJX+JyCHCsB0q6/LyzqIlzsnlJ0f0qzjeGrsHDUk793SyB0cZt7fSY\n" +
            "ofOW7n4ZbqAHBgUrgQQAIg==\n" +
            "-----END EC PRIVATE KEY-----\n";

    /** What {@code openssl ec -pubout} writes for the key above. */
    private static final String PUBLIC_KEY =
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAECu11PGYmaMe9t0uLuVNNWtVIqKOUzArk+6Mz0VppuN9b8jgvYvoq1RSzk43L8AGN"
            + "w9/k+0RhtWNfxdAj6DvLT9wpR2zngoCDQKhkIKR9zVv+Q+ob2bmwqnBCr1dooHKA";

    private static final String ANSWER = """
            v=0
            o=- 1 2 IN IP4 127.0.0.1
            a=fingerprint:sha-256 AA:BB
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            """;

    private File write(Path dir, String name, String pem) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, pem);
        return file.toFile();
    }

    @Test
    void readsTheSec1KeyOpenSslWrites(@TempDir Path dir) throws Exception {
        ServerIdentity identity = ServerIdentity.fromPem(write(dir, "sec1.pem", SEC1), "example.test");
        assertEquals(PUBLIC_KEY, publicKeyOf(identity));
    }

    @Test
    void readsThePkcs8FormOfTheSameKey(@TempDir Path dir) throws Exception {
        ServerIdentity identity = ServerIdentity.fromPem(write(dir, "pkcs8.pem", PKCS8), "example.test");
        assertEquals(PUBLIC_KEY, publicKeyOf(identity));
    }

    @Test
    void signsAnAnswerWithTheLoadedKey(@TempDir Path dir) throws Exception {
        ServerIdentity identity = ServerIdentity.fromPem(write(dir, "sec1.pem", SEC1), "example.test");
        String augmented = identity.augmentAnswer(ANSWER);

        assertTrue(augmented.contains("a=identity:"));
        assertTrue(augmented.indexOf("a=identity:") < augmented.indexOf("m=application"));
    }

    @Test
    void rejectsAKeyWithoutItsPublicPoint(@TempDir Path dir) throws Exception {
        File file = write(dir, "nopub.pem", NO_PUBLIC);
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class,
                () -> ServerIdentity.fromPem(file, "example.test"));
        assertTrue(e.getMessage().contains("does not carry its public key"), e.getMessage());
    }

    @Test
    void namesTheIdentityWithoutReplacingTheKey(@TempDir Path dir) throws Exception {
        // The domain is display text, so overriding it must not change the key clients pinned
        ServerIdentity fromCert = ServerIdentity.fromPem(write(dir, "sec1.pem", SEC1), "from.cert");
        ServerIdentity renamed = ServerIdentity.fromPem(write(dir, "sec1.pem", SEC1), "renamed.test");

        assertEquals(publicKeyOf(fromCert), publicKeyOf(renamed));
    }

    @Test
    void createsAKeyOnFirstUseAndKeepsIt(@TempDir Path dir) throws Exception {
        File file = dir.resolve("keys/identity.pem").toFile();

        ServerIdentity created = ServerIdentity.fromPemOrCreate(file, "example.test");
        assertTrue(file.isFile());
        assertTrue(Files.readString(file.toPath()).startsWith("-----BEGIN EC PRIVATE KEY-----"));

        // A second start must reuse it, replacing it would re-prompt every player
        ServerIdentity reloaded = ServerIdentity.fromPemOrCreate(file, "example.test");
        assertEquals(publicKeyOf(created), publicKeyOf(reloaded));
    }

    @Test
    void createsTheKeyReadableOnlyByItsOwner(@TempDir Path dir) throws Exception {
        File file = dir.resolve("identity.pem").toFile();
        ServerIdentity.fromPemOrCreate(file, "example.test");

        if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())));
        }
    }

    @Test
    void writesAKeyItCanReadBack(@TempDir Path dir) throws Exception {
        File file = dir.resolve("identity.pem").toFile();
        ServerIdentity created = ServerIdentity.fromPemOrCreate(file, "example.test");

        // Round trips through the reader, which requires the embedded public point
        assertEquals(publicKeyOf(created), publicKeyOf(ServerIdentity.fromPem(file, "example.test")));
    }

    /** Wraps raw DER in a PEM block, to drive the parser with bytes openssl would never write. */
    private static Path pemOf(Path dir, byte... der) throws Exception {
        Path pem = dir.resolve("key.pem");
        Files.writeString(pem, "-----BEGIN EC PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END EC PRIVATE KEY-----\n");
        return pem;
    }

    private static GeneralSecurityException refused(Path pem) {
        return assertThrows(GeneralSecurityException.class,
                () -> ServerIdentity.fromPem(pem.toFile(), "example.test"));
    }

    @Test
    void refusesDerThatIsNotAStructureItKnows(@TempDir Path dir) throws Exception {
        // A key file is operator supplied, but a parser written by hand still has to fail cleanly
        assertTrue(refused(pemOf(dir, (byte) 0x02, (byte) 0x01, (byte) 0x00)).getMessage().contains("Expected DER tag"),
                "an integer where a sequence belongs");
        assertTrue(refused(pemOf(dir, (byte) 0x30)).getMessage().contains("Truncated DER length"),
                "a sequence with no length at all");
        assertTrue(refused(pemOf(dir, (byte) 0x30, (byte) 0x05, (byte) 0x02, (byte) 0x01, (byte) 0x01))
                .getMessage().contains("past the end"), "a length reaching past what was sent");
        assertTrue(refused(pemOf(dir, (byte) 0x30, (byte) 0x85, (byte) 0x01, (byte) 0x01, (byte) 0x01, (byte) 0x01,
                (byte) 0x01)).getMessage().contains("Unsupported DER length"), "a length of five bytes");
        assertTrue(refused(pemOf(dir, (byte) 0x30, (byte) 0x80)).getMessage().contains("Unsupported DER length"),
                "the indefinite form DER does not allow");
        assertTrue(refused(pemOf(dir, (byte) 0x30, (byte) 0x82, (byte) 0x01)).getMessage()
                .contains("Truncated DER length"), "a multi byte length cut short");
        assertTrue(refused(pemOf(dir, (byte) 0x30, (byte) 0x00)).getMessage().contains("Truncated DER"),
                "an empty sequence with nothing to read");
    }

    @Test
    void refusesAPublicPointThatIsNotWholeBytes(@TempDir Path dir) throws Exception {
        // A bit string counts its unused bits, and a key point has none
        byte[] der = {0x30, 0x0b, 0x02, 0x01, 0x01, 0x04, 0x01, 0x01, (byte) 0xa1, 0x03, 0x03, 0x01, 0x07};

        assertTrue(refused(pemOf(dir, der)).getMessage().contains("whole number of bytes"));
    }

    @Test
    void refusesAPemBodyThatIsNotBase64(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("key.pem");
        Files.writeString(pem, "-----BEGIN EC PRIVATE KEY-----\nnot base64!!\n-----END EC PRIVATE KEY-----\n");

        GeneralSecurityException refused = assertThrows(GeneralSecurityException.class,
                () -> ServerIdentity.fromPem(pem.toFile(), "example.test"));
        assertTrue(refused.getMessage().contains("not valid base64"));
    }

    @Test
    void refusesAFileWithNoPemBlockInIt(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("key.pem");
        Files.writeString(pem, "# just a comment\nand some text\n");

        GeneralSecurityException refused = assertThrows(GeneralSecurityException.class,
                () -> ServerIdentity.fromPem(pem.toFile(), "example.test"));
        assertTrue(refused.getMessage().contains("No PEM block"));
    }

    @Test
    void refusesAKeyOnAnotherCurve(@TempDir Path dir) throws Exception {
        // A P-256 key carries a point of the wrong length for P-384
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        Path pem = dir.resolve("key.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair()
                        .getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");

        assertThrows(GeneralSecurityException.class, () -> ServerIdentity.fromPem(pem.toFile(), "example.test"));
    }

    @Test
    void refusesAnIdentityKeyThatIsASymbolicLink(@TempDir Path dir) throws Exception {
        // Following one would write the key wherever the link points
        Path real = dir.resolve("real.pem");
        Path link = dir.resolve("key.pem");
        Files.writeString(real, "placeholder");
        Files.createSymbolicLink(link, real);

        IOException refused = assertThrows(IOException.class,
                () -> ServerIdentity.fromPemOrCreate(link.toFile(), "example.test"));
        assertTrue(refused.getMessage().contains("symbolic link"));
    }

    @Test
    void tightensThePermissionsOfAKeyItFindsAlready(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("key.pem");
        ServerIdentity.fromPemOrCreate(pem.toFile(), "example.test");
        Files.setPosixFilePermissions(pem, PosixFilePermissions.fromString("rw-rw-rw-"));

        ServerIdentity.fromPemOrCreate(pem.toFile(), "example.test");

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(pem)),
                "a key left readable by everyone is tightened on the next start");
    }

    @Test
    void rejectsSomethingThatIsNotAPem(@TempDir Path dir) throws Exception {
        File file = write(dir, "junk.pem", "not a pem at all\n");
        assertThrows(GeneralSecurityException.class, () -> ServerIdentity.fromPem(file, "example.test"));
    }

    /**
     * The token's cpk claim is the X.509 encoding of the public key the loader derived.
     */
    private String publicKeyOf(ServerIdentity identity) throws Exception {
        String line = identity.augmentAnswer(ANSWER).lines()
                .filter(l -> l.startsWith("a=identity:")).findFirst().orElseThrow();
        Identity identityValue = Identity.fromBase64(line.substring("a=identity:".length()));
        String payload = identityValue.assertion().token().split("\\.")[1];
        String claims = new String(Base64.getUrlDecoder().decode(payload));
        return claims.replaceAll(".*\"cpk\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
