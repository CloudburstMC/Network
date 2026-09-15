package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderStateStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Dedicated control-state directory with ProviderStateStore's exclusive lock, owner-only POSIX
 * permissions, atomic replace/file fsync and POSIX directory fsync. Never point this at an active
 * ProviderClient identity directory: that client retains its own cached whole-state snapshot.
 * ACL filesystems receive an owner-only inheritable directory ACL before private material is written.
 * Atomic replacement applies on Windows, but directory fsync is unavailable there.
 */
public final class FileControlClientJournal implements ControlClientJournal {
    public static final int MAX_JOURNAL_BYTES = 196608;
    private final ProviderStateStore store;
    private final Path file;
    private boolean closed;
    private boolean floorLoaded;
    private AuthorityFloor retainedFloor;

    public FileControlClientJournal(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Control journal directory must not be a symbolic link");
        protect(directory, true);
        this.store = new ProviderStateStore(directory);
        this.file = directory.toAbsolutePath().resolve("provider-state.json");
    }

    @Override public synchronized Optional<Snapshot> read() throws IOException {
        open();
        if (!Files.exists(file)) {
            if (retainedFloor != null) throw new IOException("Control authority floor disappeared");
            floorLoaded = true; return Optional.empty();
        }
        protect(file, false);
        if (Files.size(file) > MAX_JOURNAL_BYTES) throw new IOException("Control journal exceeds size limit");
        try {
            JsonObject value = ControlJson.parse(Files.readString(file, StandardCharsets.UTF_8), MAX_JOURNAL_BYTES);
            List<String> fields = new ArrayList<>(List.of("version", "subject", "currentKey", "writer", "lastSequence"));
            for (String name : List.of("pending", "pendingBootstrap", "grant", "authorityFloor")) if (value.has(name)) fields.add(name);
            ControlJson.fields(value, fields.toArray(String[]::new)); ControlJson.version(value);
            JsonObject subject = ControlJson.object(value, "subject"); ControlJson.fields(subject, "audience", "instanceId", "generation");
            Pending pending = null;
            if (value.has("pending")) {
                JsonObject item = ControlJson.object(value, "pending");
                List<String> required = new ArrayList<>(List.of("intent", "originalBody"));
                if (item.has("candidate")) required.add("candidate"); if (item.has("receipt")) required.add("receipt");
                ControlJson.fields(item, required.toArray(String[]::new));
                pending = new Pending(ControlLifecycleCodec.readIntent(ControlJson.object(item, "intent")), ControlJson.string(item, "originalBody"),
                        item.has("candidate") ? credential(ControlJson.object(item, "candidate")) : null,
                        item.has("receipt") ? ControlLifecycleCodec.decodeReceipt(ControlJson.string(item, "receipt")) : null);
            }
            Grant grant = null;
            if (value.has("grant")) {
                JsonObject item = ControlJson.object(value, "grant");
                ControlJson.fields(item, "capabilities", "activatedAt", "sessionExpiresAt", "authoritySourceCheckedAt", "authorityExpiresAt");
                grant = new Grant(ControlJson.strings(item, "capabilities"), ControlJson.number(item, "activatedAt"), ControlJson.number(item, "sessionExpiresAt"),
                        ControlJson.number(item, "authoritySourceCheckedAt"), ControlJson.number(item, "authorityExpiresAt"));
            }
            AuthorityFloor floor = value.has("authorityFloor") ? new AuthorityFloor(ControlJson.string(value, "authorityFloor")) : null;
            checkFloor(floor);
            var result = new Snapshot(new Subject(ControlJson.string(subject, "audience"), ControlJson.string(subject, "instanceId"), ControlJson.number(subject, "generation")),
                    credential(ControlJson.object(value, "currentKey")), ControlWriterFence.read(ControlJson.object(value, "writer")), ControlJson.number(value, "lastSequence"), pending,
                    value.has("pendingBootstrap") ? new Bootstrap(ControlJson.string(value, "pendingBootstrap")) : null, grant, floor);
            retainedFloor = floor; floorLoaded = true; return Optional.of(result);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid control journal", failure); }
    }

    @Override public synchronized void commit(Snapshot snapshot) throws IOException {
        open();
        if (!floorLoaded) read();
        try { checkFloor(snapshot.authorityFloor()); }
        catch (IllegalArgumentException failure) { throw new IOException("Control authority floor regression", failure); }
        JsonObject value = new JsonObject(); value.addProperty("version", 1);
        JsonObject subject = new JsonObject(); subject.addProperty("audience", snapshot.subject().audience()); subject.addProperty("instanceId", snapshot.subject().instanceId());
        subject.addProperty("generation", snapshot.subject().generation()); value.add("subject", subject);
        value.add("currentKey", credential(snapshot.currentKey())); value.add("writer", snapshot.writer().object()); value.addProperty("lastSequence", snapshot.lastSequence());
        if (snapshot.pending() != null) {
            Pending pending = snapshot.pending(); JsonObject item = new JsonObject(); item.add("intent", ControlLifecycleCodec.intentObject(pending.intent()));
            item.addProperty("originalBody", pending.originalBody()); if (pending.candidate() != null) item.add("candidate", credential(pending.candidate()));
            if (pending.receipt() != null) item.addProperty("receipt", ControlLifecycleCodec.encodeReceipt(pending.receipt())); value.add("pending", item);
        }
        if (snapshot.pendingBootstrap() != null) value.addProperty("pendingBootstrap", snapshot.pendingBootstrap().originalRequest());
        if (snapshot.authorityFloor() != null) value.addProperty("authorityFloor", snapshot.authorityFloor().originalResponse());
        if (snapshot.grant() != null) {
            Grant grant = snapshot.grant(); JsonObject item = new JsonObject(); item.add("capabilities", ControlProof.capabilitiesObject(grant.capabilities()));
            item.addProperty("activatedAt", grant.activatedAt()); item.addProperty("sessionExpiresAt", grant.sessionExpiresAt());
            item.addProperty("authoritySourceCheckedAt", grant.authoritySourceCheckedAt()); item.addProperty("authorityExpiresAt", grant.authorityExpiresAt()); value.add("grant", item);
        }
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_BYTES) throw new IOException("Control journal exceeds size limit");
        store.write(value);
        protect(file, false);
        retainedFloor = snapshot.authorityFloor(); floorLoaded = true;
    }

    private void checkFloor(AuthorityFloor next) {
        if (retainedFloor != null && next == null) throw ControlJson.invalid("removed journal authority floor");
        if (next != null) next.requireAtLeast(retainedFloor);
    }

    @Override public synchronized void close() throws IOException { if (!closed) { closed = true; store.close(); } }
    private void open() throws IOException { if (closed) throw new IOException("Control journal is closed"); }
    private static void protect(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Control journal requires POSIX permissions or owner-only ACL support");
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
    private static Credential credential(JsonObject value) {
        ControlJson.fields(value, "keyId", "publicKeyJwk", "privateKeyPkcs8");
        return new Credential(ControlJson.string(value, "keyId"), ControlJson.string(value, "publicKeyJwk"), ControlJson.string(value, "privateKeyPkcs8"));
    }
    private static JsonObject credential(Credential key) {
        JsonObject value = new JsonObject(); value.addProperty("keyId", key.keyId()); value.addProperty("publicKeyJwk", key.publicKeyJwk());
        value.addProperty("privateKeyPkcs8", key.privateKeyPkcs8()); return value;
    }
}
