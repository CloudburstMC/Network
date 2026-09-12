package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
