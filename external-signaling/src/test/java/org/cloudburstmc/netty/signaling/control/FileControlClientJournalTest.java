package org.cloudburstmc.netty.signaling.control;

import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileControlClientJournalTest {
    @TempDir Path directory;
    static ControlClientJournal.Snapshot initial() throws Exception {
        var current = ControlClientJournal.Credential.from("machine_test_01", ProviderCrypto.generate());
        return new ControlClientJournal.Snapshot(new ControlClientJournal.Subject("https://provider.example", "instance_test_01", 3), current,
                new ControlWriterFence("legacy-http", 0, "", "", current.keyId(), 1), 0, null, null, null);
    }
    @Test void selectedCandidateAndOriginalRotationSurviveCloseAndReopenAtomically() throws Exception {
        var initial = initial();
        var candidate = ControlClientJournal.Credential.from("chosen_candidate_id_0001", ProviderCrypto.generate());
        var context = new ControlRotationCodec.Context(initial.subject().audience(), initial.subject().instanceId(), initial.subject().generation(),
                initial.currentKey().keyId(), "persisted_rotation_intent_0001");
        byte[] body = ControlRotationCodec.encode(ControlRotationCodec.create(candidate.keyId(), candidate.keyPair(), context)).getBytes(StandardCharsets.UTF_8);
        var intent = ControlLifecycleCodec.intent(context.audience(), "rotate", context.instanceId(), context.generation(), 1, context.idempotencyKey(), body);
        var pending = new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), candidate, null);
        var expected = new ControlClientJournal.Snapshot(initial.subject(), initial.currentKey(), initial.writer(), 1, pending, null, null);
        try (var journal = new FileControlClientJournal(directory)) {
            assertTrue(journal.read().isEmpty()); journal.commit(initial); journal.commit(expected);
            assertEquals(expected, journal.read().orElseThrow());
            assertThrows(IOException.class, () -> new FileControlClientJournal(directory));
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(directory.resolve("provider-state.json")));
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(directory));
            }
        }
        try (var journal = new FileControlClientJournal(directory)) {
            var restored = journal.read().orElseThrow(); assertEquals(expected, restored);
            assertArrayEquals(body, restored.pending().bodyBytes());
            assertEquals(ControlLifecycleCodec.intentDigest(intent), ControlLifecycleCodec.intentDigest(restored.pending().intent()));
            assertEquals(candidate.privateKeyPkcs8(), restored.pending().candidate().privateKeyPkcs8());
            assertFalse(restored.toString().contains(candidate.privateKeyPkcs8()));
            assertFalse(restored.toString().contains(pending.originalBody()));
        }
    }
    @Test void rejectsCorruptionAndCannotWriteAfterReleasingOwnership() throws Exception {
        var journal = new FileControlClientJournal(directory); var state = initial(); journal.commit(state); journal.close();
        assertThrows(IOException.class, () -> journal.commit(state));
        Path file = directory.resolve("provider-state.json"); String valid = Files.readString(file);
        Files.writeString(file, valid.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1"));
        try (var reopened = new FileControlClientJournal(directory)) { assertThrows(IOException.class, reopened::read); }
        Files.writeString(file, " ".repeat(FileControlClientJournal.MAX_JOURNAL_BYTES + 1));
        try (var reopened = new FileControlClientJournal(directory)) { assertThrows(IOException.class, reopened::read); }
    }
    @Test void refusesMismatchedIdentitySequenceAndCandidateBeforePersistence() throws Exception {
        var state = initial(); byte[] body = "{ }\n".getBytes(StandardCharsets.UTF_8);
        var intent = ControlLifecycleCodec.intent(state.subject().audience(), "heartbeat", state.subject().instanceId(), 3, 1, "pending_heartbeat_0001", body);
        var pending = new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), null, null);
        assertThrows(IllegalArgumentException.class, () -> new ControlClientJournal.Snapshot(state.subject(), state.currentKey(), state.writer(), 0, pending, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ControlClientJournal.Pending(intent, ProviderCrypto.base64("{}".getBytes(StandardCharsets.UTF_8)), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ControlClientJournal.Pending(intent, ProviderCrypto.base64(body), state.currentKey(), null));
    }

    @Test void authorityFloorSurvivesReopenAndCannotBeRemovedOrRegressedByAnotherSnapshotWrite() throws Exception {
        var h = new ControlClientCoordinatorTest.Harness(); h.sourceRevision = 4; h.ready();
        var expected = h.client.snapshot();
        try (var journal = new FileControlClientJournal(directory)) { journal.commit(expected); }
        h.client.synchronize(); var exchange = h.authorityRequests.remove(); h.sourceRevision = 0;
        var older = new ControlClientJournal.AuthorityFloor(h.authorityWire(exchange));
        var regressed = new ControlClientJournal.Snapshot(expected.subject(), expected.currentKey(), expected.writer(), expected.lastSequence(),
                expected.pending(), expected.pendingBootstrap(), expected.grant(), older);
        var removed = new ControlClientJournal.Snapshot(expected.subject(), expected.currentKey(), expected.writer(), expected.lastSequence(),
                expected.pending(), expected.pendingBootstrap(), expected.grant());
        try (var journal = new FileControlClientJournal(directory)) {
            // commit without a prior explicit read must still load and preserve the on-disk floor.
            assertThrows(IOException.class, () -> journal.commit(removed));
            assertThrows(IOException.class, () -> journal.commit(regressed));
            assertEquals(expected, journal.read().orElseThrow());
            assertEquals(5, journal.read().orElseThrow().authorityFloor().value().source().sourceRevision());
        }
        h.client.close();
    }
}
