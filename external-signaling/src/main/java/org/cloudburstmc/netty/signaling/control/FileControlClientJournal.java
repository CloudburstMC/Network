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

    public FileControlClientJournal(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Control journal directory must not be a symbolic link");
        protect(directory, true);
        this.store = new ProviderStateStore(directory);
        this.file = directory.toAbsolutePath().resolve("provider-state.json");
    }

    @Override public synchronized Optional<Snapshot> read() throws IOException {
        open();
        if (!Files.exists(file)) return Optional.empty();
        protect(file, false);
        if (Files.size(file) > MAX_JOURNAL_BYTES) throw new IOException("Control journal exceeds size limit");
        try {
            JsonObject value = ControlJson.parse(Files.readString(file, StandardCharsets.UTF_8), MAX_JOURNAL_BYTES);
            List<String> fields = new ArrayList<>(List.of("version", "subject", "currentKey", "writer", "lastSequence"));
            for (String name : List.of("pending", "pendingBootstrap", "grant")) if (value.has(name)) fields.add(name);
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
            return Optional.of(new Snapshot(new Subject(ControlJson.string(subject, "audience"), ControlJson.string(subject, "instanceId"), ControlJson.number(subject, "generation")),
                    credential(ControlJson.object(value, "currentKey")), ControlWriterFence.read(ControlJson.object(value, "writer")), ControlJson.number(value, "lastSequence"), pending,
                    value.has("pendingBootstrap") ? new Bootstrap(ControlJson.string(value, "pendingBootstrap")) : null, grant));
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid control journal", failure); }
    }

    @Override public synchronized void commit(Snapshot snapshot) throws IOException {
        open();
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
        if (snapshot.grant() != null) {
            Grant grant = snapshot.grant(); JsonObject item = new JsonObject(); item.add("capabilities", ControlProof.capabilitiesObject(grant.capabilities()));
            item.addProperty("activatedAt", grant.activatedAt()); item.addProperty("sessionExpiresAt", grant.sessionExpiresAt());
            item.addProperty("authoritySourceCheckedAt", grant.authoritySourceCheckedAt()); item.addProperty("authorityExpiresAt", grant.authorityExpiresAt()); value.add("grant", item);
        }
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JOURNAL_BYTES) throw new IOException("Control journal exceeds size limit");
        store.write(value);
        protect(file, false);
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
