package org.cloudburstmc.netty.util.nethernet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A secret a host configured, which may be the value itself or a file holding it.
 * <p>
 * A configuration file is read by anyone who can read the directory, gets copied between machines
 * and ends up in support requests. A file reference keeps the secret out of it and leaves the
 * protection to the filesystem.
 */
public final class SecretValue {

    /** Above this a file is something other than a secret, and reading it is a waste. */
    private static final long MAX_BYTES = 16384;

    private SecretValue() {
    }

    /**
     * Resolves a configured value, reading it from a file when it names one.
     * <p>
     * A value is a file reference when it begins with {@code file:} or looks like a path. The file's
     * trailing line terminator is dropped, because a file written by {@code echo} or an editor has
     * one and it is never part of the secret.
     *
     * @param value     The configured value, which may be empty
     * @param directory The directory a relative path is resolved against
     * @return The secret itself, or the value unchanged when it names no file
     * @throws IOException If the value names a file that cannot be read
     */
    public static String resolve(String value, Path directory) throws IOException {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (!isFileReference(trimmed)) {
            return value;
        }

        Path source = path(directory, trimmed.startsWith("file:") ? trimmed.substring("file:".length()) : trimmed);
        try {
            if (!Files.isRegularFile(source)) {
                throw new IOException("not a readable file");
            }
            if (Files.size(source) > MAX_BYTES) {
                throw new IOException("larger than " + MAX_BYTES + " bytes");
            }
            return stripLineTerminator(Files.readString(source));
        } catch (IOException | RuntimeException e) {
            // Never the value itself, which is the path here rather than the secret
            throw new IOException("Could not read the secret at " + source + ": " + e.getMessage(), e);
        }
    }

    private static boolean isFileReference(String value) {
        return value.startsWith("file:") || value.startsWith("/") || value.startsWith("./")
                || value.startsWith("../") || absolutePath(value);
    }

    /**
     * @return Whether the value is an absolute path, such as {@code C:\secret} on Windows, so it is
     * never mistaken for the secret itself
     */
    private static boolean absolutePath(String value) {
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    private static Path path(Path directory, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() || directory == null ? path : directory.resolve(path)).normalize();
    }

    private static String stripLineTerminator(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n") || value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
