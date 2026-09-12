package org.cloudburstmc.netty.signalling.provider;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class ProviderHostIdentityTest {
    @TempDir Path directory;

    @Test void firstStartCreatesAPrivatePairedIdentityAndRestartPreservesIt() throws Exception {
        Path state = directory.resolve("state");
        var first = ProviderHostIdentity.ensure(state);
        byte[] key = Files.readAllBytes(first.privateKey()), certificate = Files.readAllBytes(first.certificate());
        Files.writeString(state.resolve("provider-state.json"), "existing-machine-identity");
        var second = ProviderHostIdentity.ensure(state);
        assertEquals(first.fingerprint(), second.fingerprint());
        assertArrayEquals(key, Files.readAllBytes(second.privateKey()));
        assertArrayEquals(certificate, Files.readAllBytes(second.certificate()));
        assertEquals("existing-machine-identity", Files.readString(state.resolve("provider-state.json")));
        if (state.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(state));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(second.privateKey()));
        }
    }

    @Test void missingHalfOfAnExistingPairIsNeverRegenerated() throws Exception {
        Files.writeString(directory.resolve("host-key.pem"), "existing-private-key");
        assertThrows(Exception.class, () -> ProviderHostIdentity.ensure(directory));
        assertEquals("existing-private-key", Files.readString(directory.resolve("host-key.pem")));
        assertFalse(Files.exists(directory.resolve("host-cert.pem")));
    }

    @Test void mismatchedPairIsRejectedWithoutReplacement() throws Exception {
        var first = ProviderHostIdentity.ensure(directory.resolve("first"));
        var other = ProviderHostIdentity.ensure(directory.resolve("other"));
        byte[] key = Files.readAllBytes(first.privateKey());
        Files.copy(other.certificate(), first.certificate(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThrows(Exception.class, () -> ProviderHostIdentity.ensure(directory.resolve("first")));
        assertArrayEquals(key, Files.readAllBytes(first.privateKey()));
        assertArrayEquals(Files.readAllBytes(other.certificate()), Files.readAllBytes(first.certificate()));
    }

    @Test void symbolicIdentityIsRejected() throws Exception {
        Path state = directory.resolve("state");
        Files.createDirectory(state);
        try {
            Files.createSymbolicLink(state.resolve("host-key.pem"), directory.resolve("elsewhere"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows only allows symbolic links with developer mode or elevated rights
            Assumptions.abort("Cannot create symbolic links here: " + e);
        }
        assertThrows(Exception.class, () -> ProviderHostIdentity.ensure(state));
        assertFalse(Files.exists(state.resolve("host-cert.pem")));
    }

    @Test void concurrentInitializationIsRejected() throws Exception {
        Path state = directory.resolve("state");
        Files.createDirectory(state);
        try (var channel = FileChannel.open(state.resolve("host-identity.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(Exception.class, () -> ProviderHostIdentity.ensure(state));
        }
        assertFalse(Files.exists(state.resolve("host-key.pem")));
    }
}
