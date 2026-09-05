package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** One logical instance owns this directory and key; never share or clone it across live instances. */
public final class ProviderStateStore implements AutoCloseable {
    private final Path directory, stateFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public ProviderStateStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath(); this.stateFile = this.directory.resolve("provider-state.json");
        Files.createDirectories(this.directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(this.directory) || Files.isSymbolicLink(stateFile)) throw new IOException("State paths must not be symbolic links");
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        Path lockFile = this.directory.resolve("provider.lock");
        if (Files.isSymbolicLink(lockFile)) throw new IOException("Lock must not be a symbolic link");
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { lockChannel.close(); throw new IOException("State directory is already active", e); }
        if (acquired == null) { lockChannel.close(); throw new IOException("State directory is already active"); }
        lock = acquired;
    }
    public JsonObject read() throws IOException {
        if (!Files.exists(stateFile)) return new JsonObject();
        if (Files.size(stateFile) > 262144) throw new IOException("State exceeds limit");
        Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-------"));
        return JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
    }
    public void write(JsonObject state) throws IOException {
        Path tmp = Files.createTempFile(directory, "provider-state-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] bytes = new GsonBuilder().disableHtmlEscaping().create().toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) { ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) file.write(b); file.force(true); }
            Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
        } finally { Files.deleteIfExists(tmp); }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
}
