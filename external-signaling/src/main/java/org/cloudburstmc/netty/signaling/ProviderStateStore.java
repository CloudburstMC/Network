/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling;

import com.google.gson.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * One logical instance owns this directory and key; never share or clone it across live instances.
 */
public final class ProviderStateStore implements AutoCloseable {
    // Windows has none of this: permissions are an ACL and a directory cannot be opened as a file. The
    // state still lands atomically there, it just inherits whatever the parent directory allows.
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final FileAttribute<?>[] OWNER_ONLY_DIRECTORY = POSIX
            ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
            : new FileAttribute<?>[0];
    private static final FileAttribute<?>[] OWNER_ONLY_FILE = POSIX
            ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
            : new FileAttribute<?>[0];

    private final Path directory, stateFile;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;

    public ProviderStateStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath();
        this.stateFile = this.directory.resolve("provider-state.json");
        Files.createDirectories(this.directory, OWNER_ONLY_DIRECTORY);
        if (Files.isSymbolicLink(this.directory) || Files.isSymbolicLink(stateFile)) {
            throw new IOException("State paths must not be symbolic links");
        }
        if (POSIX) {
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        }
        Path lockFile = this.directory.resolve("provider.lock");
        if (Files.isSymbolicLink(lockFile)) {
            throw new IOException("Lock must not be a symbolic link");
        }
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("State directory is already active", e);
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("State directory is already active");
        }
        lock = acquired;
    }

    public synchronized JsonObject read() throws IOException {
        requireOwnership();
        if (!Files.exists(stateFile)) {
            return new JsonObject();
        }
        if (Files.size(stateFile) > 262144) {
            throw new IOException("State exceeds limit");
        }
        if (POSIX) {
            Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-------"));
        }
        return JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject();
    }

    Path directory() {
        return directory;
    }

    public synchronized void write(JsonObject state) throws IOException {
        requireOwnership();
        Path tmp = Files.createTempFile(directory, "provider-state-", ".tmp", OWNER_ONLY_FILE);
        try {
            byte[] bytes = new GsonBuilder().disableHtmlEscaping().create().toJson(state)
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel file = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes);
                while (b.hasRemaining()) {
                    file.write(b);
                }
                file.force(true);
            }
            Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (POSIX) {
                // Persists the rename itself. Windows cannot open a directory as a file to ask for it.
                try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
                    dir.force(true);
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void requireOwnership() throws IOException {
        if (closed || !lock.isValid()) {
            throw new IOException("Provider state ownership is closed");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        // Queued callbacks may drain after client shutdown. Serialize the entire IO operation with
        // lock release so they can never read or replace a successor owner's state file.
        closed = true;
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }
}
