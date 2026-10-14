package org.cloudburstmc.netty.signaling;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit, short-lived fixture handoff of an operator link, which may carry a one-time credential;
 * never part of durable instance identity or stdout. Empty when the provider offers none.
 */
public final class OperatorActionFile {
    private OperatorActionFile() {
    }

    public static void write(Path path, Optional<ProviderClient.OperatorAction> action) throws IOException {
        try (FileChannel file = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(action.map(value -> value.url().toString()).orElse(""));
            while (bytes.hasRemaining()) {
                file.write(bytes);
            }
            file.force(true);
        }
    }
}
