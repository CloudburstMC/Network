package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Explicit, short-lived fixture handoff; never part of durable instance identity or stdout. */
public final class ExtensionFixtureFile {
    private ExtensionFixtureFile() {}
    public static void write(Path path, JsonObject extensions) throws IOException {
        JsonObject document = new JsonObject(); document.add("extensions", extensions); ProtocolExtensions.validate(document);
        try (FileChannel file = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(extensions.toString());
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
    }
}
