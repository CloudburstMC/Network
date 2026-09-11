package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
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
