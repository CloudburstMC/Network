package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretValueTest {

    @Test
    void takesAValueThatNamesNoFileAsItself() throws Exception {
        assertEquals("hunter2", SecretValue.resolve("hunter2", Path.of(".")));
        assertEquals("", SecretValue.resolve("", Path.of(".")));
        assertEquals(null, SecretValue.resolve(null, Path.of(".")));
    }

    @Test
    void readsAFileReferenceRelativeToTheHostDirectory(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("keystore.pass"), "hunter2\n");

        assertEquals("hunter2", SecretValue.resolve("file:keystore.pass", directory));
        assertEquals("hunter2", SecretValue.resolve(directory.resolve("keystore.pass").toString(), directory));
    }

    @Test
    void dropsOnlyTheTrailingNewlineAFileEndsWith(@TempDir Path directory) throws Exception {
        // echo adds one, and it is never part of the secret; a space inside one might be
        Files.writeString(directory.resolve("windows"), "two words\r\n");
        Files.writeString(directory.resolve("spaced"), " padded ");

        assertEquals("two words", SecretValue.resolve("file:windows", directory));
        assertEquals(" padded ", SecretValue.resolve("file:spaced", directory));
    }

    @Test
    void takesEveryShapeOfAbsenceAsItself() throws Exception {
        assertNull(SecretValue.resolve(null, null));
        assertEquals("", SecretValue.resolve("", null));
        assertEquals("   ", SecretValue.resolve("   ", null));
    }

    @Test
    void readsEachWayOfNamingAFile(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("key.txt");
        Files.writeString(secret, "opensesame");

        assertEquals("opensesame", SecretValue.resolve("file:" + secret, directory));
        assertEquals("opensesame", SecretValue.resolve(secret.toString(), directory), "an absolute path");
        assertEquals("opensesame", SecretValue.resolve("./key.txt", directory), "relative to the directory");
        assertEquals("opensesame", SecretValue.resolve("../" + directory.getFileName() + "/key.txt", directory),
                "and one that climbs out and back");
        assertEquals("opensesame", SecretValue.resolve("  file:" + secret + "  ", directory), "with space around it");
    }

    @Test
    void readsARelativeFileWithNoDirectoryToResolveAgainst(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("key.txt");
        Files.writeString(secret, "opensesame");

        assertEquals("opensesame", SecretValue.resolve("file:" + secret, null));
    }

    @Test
    void takesAPasswordThatIsNotAPathAsItself() throws Exception {
        // A password may hold anything, so only what looks like a file is treated as one
        assertEquals("hunter2", SecretValue.resolve("hunter2", null));
        assertEquals("p@ss:word", SecretValue.resolve("p@ss:word", null));
        assertEquals("not/a/reference", SecretValue.resolve("not/a/reference", null),
                "a bare relative path is a value, not a file");
        assertEquals("a\u0000b", SecretValue.resolve("a\u0000b", null), "and neither is something that is no path at all");
    }

    @Test
    void dropsABareCarriageReturnToo(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("key.txt");
        Files.writeString(secret, "opensesame\r");

        assertEquals("opensesame", SecretValue.resolve("file:" + secret, directory));
    }

    @Test
    void namesTheFileItCouldNotFindWithNoDirectoryToLookIn() {
        IOException failure = assertThrows(IOException.class, () -> SecretValue.resolve("./nowhere.txt", null));

        assertTrue(failure.getMessage().contains("nowhere.txt"));
        assertFalse(failure.getMessage().contains("opensesame"));
    }

    @Test
    void refusesAFileItCannotRead(@TempDir Path directory) {
        IOException missing = assertThrows(IOException.class,
                () -> SecretValue.resolve("file:absent.pass", directory));

        assertFalse(missing.getMessage().contains("hunter2"));
    }

    @Test
    void refusesAFileTooLargeToBeASecret(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("huge"), "x".repeat(16385));

        assertThrows(IOException.class, () -> SecretValue.resolve("file:huge", directory));
    }
}
