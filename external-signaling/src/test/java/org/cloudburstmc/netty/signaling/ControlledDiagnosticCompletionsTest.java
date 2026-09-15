package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class ControlledDiagnosticCompletionsTest {
    static ControlDiagnosticCompletionCodec.Completion completion(int index) throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.diagnostic-completion.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        var vectors = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("vectors");
        var json = JsonParser.parseString(vectors.get(index % 2).getAsJsonObject().get("wire").getAsString()).getAsJsonObject();
        json.getAsJsonObject("installation").addProperty("providerOrigin", ControlledNativeOwnerApplicationTest.ORIGIN);
        json.getAsJsonObject("installation").addProperty("hostId", "fixture_host");
        json.getAsJsonObject("installation").addProperty("generation", 1);
        json.addProperty("attemptIdHex", String.format("%032x", index + 1));
        return ControlDiagnosticCompletionCodec.decodeCompletion(json.toString());
    }
    static ControlDiagnosticCompletionCodec.Completion mutate(ControlDiagnosticCompletionCodec.Completion c, java.util.function.Consumer<JsonObject> fn) {
        var j = JsonParser.parseString(ControlDiagnosticCompletionCodec.encodeCompletion(c)).getAsJsonObject(); fn.accept(j);
        return ControlDiagnosticCompletionCodec.decodeCompletion(j.toString());
    }
    static DiagnosticAdmission.Completion nativeCompletion(ControlDiagnosticCompletionCodec.Completion c) throws Exception {
        var b = c.installation(); var context = new DiagnosticAdmissionCodec.Context(b.providerOrigin(), b.hostId(), b.nativeIncarnation(), b.generation());
        var binding = new DiagnosticAdmission.Binding(context, b.authorityIncarnation(), b.nativeOwnerEpoch(), b.hostProfileRevision(),
                b.hostProfileSha256(), b.policyRevision(), b.installationSha256(), b.hostFingerprintHex());
        var t = c.target(); var target = new DiagnosticHostPolicy.Endpoint(t.family(), t.addressHex(), t.port(), t.candidateRevision());
        var u = c.udp() == null ? null : new DiagnosticAdmission.UdpCounters(c.udp().reserved(), c.udp().sent(), c.udp().sentBytes(), c.udp().rejected());
        return new DiagnosticAdmission.Completion(binding, context, c.keyId(), c.attemptIdHex(), c.offerDigestHex(), c.clientFingerprintHex(),
                c.expiresAt(), target, c.success(), c.cleanupComplete(), c.reason(), tuple(c.selectedLocal()), tuple(c.selectedRemote()), u,
                c.frames().sent(), c.frames().sentBytes(), c.frames().received(), c.frames().receivedBytes(), c.completionDigestHex(), c.completedAt());
    }
    static InetSocketAddress tuple(ControlDiagnosticCompletionCodec.SelectedTuple t) throws Exception {
        if (t == null) return null; byte[] bytes = HexFormat.of().parseHex(t.addressHex());
        return new InetSocketAddress(InetAddress.getByAddress(t.family() == 4 ? Arrays.copyOfRange(bytes, 12, 16) : bytes), t.port());
    }
    static final class Harness implements AutoCloseable {
        final ProviderStateStore store; ControlledProviderState storage; ControlledDiagnosticCompletions queue;
        final List<DiagnosticAdmission.Completion> nativeResults = new ArrayList<>(); final List<String> notices = new ArrayList<>();
        final AtomicBoolean reject = new AtomicBoolean(); long dropped; int polls;
        final ProviderTransport transport = (ProviderTransport) java.lang.reflect.Proxy.newProxyInstance(ProviderTransport.class.getClassLoader(), new Class[]{ProviderTransport.class}, (proxy, method, args) -> {
            if (method.getName().equals("supportsDiagnosticAdmission")) return true;
            if (method.getName().equals("diagnosticDroppedResultCount")) return OptionalLong.of(dropped);
            if (method.getName().equals("pollDiagnosticResults")) {
                polls++; int n = Math.min((int) args[0], nativeResults.size()); var result = List.copyOf(nativeResults.subList(0, n)); nativeResults.subList(0, n).clear(); return result;
            }
            throw new AssertionError("Unexpected player/native operation: " + method.getName());
        });
        Harness(Path path) throws Exception {
            store = new ProviderStateStore(path); ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN); reopen();
        }
        void reopen() throws Exception {
            if (storage != null) storage.close();
            storage = ControlledProviderState.open(store, ControlledDiagnosticApplicationTest.config(), value -> {
                if (reject.get()) throw new IOException("injected durable completion save failure"); store.write(value);
            }); queue = new ControlledDiagnosticCompletions(storage, transport, notices::add);
        }
        int pending() { return storage.diagnosticCompletionState().getAsJsonArray("pending").size(); }
        long count(String key) { return storage.diagnosticCompletionState().get(key).getAsLong(); }
        void append(ControlDiagnosticCompletionCodec.Completion... c) throws IOException { storage.appendDiagnosticCompletions(List.of(c), 0, 0); }
        byte[] body() {
            var body = new JsonObject(); body.add("diagnosticCompletions", JsonParser.parseString(ControlDiagnosticCompletionCodec.encodeBatch(storage.diagnosticCompletionBatch())));
            return body.toString().getBytes(StandardCharsets.UTF_8);
        }
        ControlLifecycleCodec.Intent intent(byte[] body, long seq) { var s = storage.initial.subject(); return ControlLifecycleCodec.intent(s.audience(), "heartbeat", s.instanceId(), s.generation(), seq, "diagnostic_delivery_fixture_" + seq, body); }
        ControlLifecycleCodec.Receipt receipt(ControlLifecycleCodec.Intent i) { return new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(i), i.operation(), i.instanceId(), i.generation(), i.sequence(), i.idempotencyKey(), "committed", 2000000000000L, 1L, null); }
        void ack(byte[] body, long seq, ControlDiagnosticCompletionReceiptCodec.Batch receipts) throws IOException {
            var intent = intent(body, seq); queue.acknowledge(intent, body, receipt(intent), response(receipts));
        }
        @Override public void close() throws Exception { storage.close(); store.close(); }
    }
    static ControlDiagnosticCompletionReceiptCodec.Receipt receipt(ControlDiagnosticCompletionCodec.Completion c, String disposition, long receivedAt) {
        return new ControlDiagnosticCompletionReceiptCodec.Receipt(c.installation(), c.attemptIdHex(), ControlDiagnosticCompletionCodec.completionDigest(c), receivedAt, disposition);
    }
    static ControlDiagnosticCompletionReceiptCodec.Batch receipts(ControlDiagnosticCompletionCodec.Completion... c) {
        return new ControlDiagnosticCompletionReceiptCodec.Batch(Arrays.stream(c).map(v -> receipt(v, "recorded", v.completedAt())).toList());
    }
    static byte[] response(ControlDiagnosticCompletionReceiptCodec.Batch b) {
        return b == null ? null : ("{\"diagnosticCompletions\":" + ControlDiagnosticCompletionReceiptCodec.encodeBatch(b) + "}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void queuedApplicationReceiptCannotOverwriteSuccessorAfterRootLockRelease(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            var c = completion(0); h.append(c);
            var tasks = new ArrayDeque<Runnable>();
            var app = new ControlledProviderApplication(h.storage, h.transport, tasks::add,
                    () -> 1000, () -> null, () -> null, null);
            byte[] body = h.body(); var intent = h.intent(body, 18);
            var acknowledgement = app.acknowledgeDiagnosticCompletions(intent, body, h.receipt(intent), response(receipts(c))).toCompletableFuture();
            assertEquals(1, tasks.size()); assertFalse(acknowledgement.isDone());
            // ProviderClient.stop releases these locks before its executor drains queued callbacks.
            app.close(); h.storage.close(); h.store.close();
            try (var successorStore = new ProviderStateStore(path);
                 var successor = ControlledProviderState.open(successorStore, ControlledDiagnosticApplicationTest.config())) {
                var next = successor.application(); next.addProperty("appliedRevision", 2); successor.saveApplication(next);
                var expected = successorStore.read();
                tasks.remove().run();
                var failure = assertThrows(CompletionException.class, acknowledgement::join);
                assertInstanceOf(IOException.class, failure.getCause());
                assertEquals(expected, successorStore.read());
                assertEquals(1, successor.diagnosticCompletionBatch().completions().size());
                assertEquals(0, successor.diagnosticCompletionState().get("recorded").getAsLong());
                // The new owner can settle the original historical report normally.
                successor.acknowledgeDiagnosticCompletions(intent, body, h.receipt(intent), receipts(c));
                assertNull(successor.diagnosticCompletionBatch());
                assertEquals(1, successor.diagnosticCompletionState().get("recorded").getAsLong());
                assertEquals(2, successorStore.read().getAsJsonObject("controlApplication").get("appliedRevision").getAsInt());
            }
        }
    }
    @Test void nativeSaveFailureHoldsExactFourWithoutAnotherPollAndPreservesOtherRootUpdates(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            for (int i = 0; i < 6; i++) h.nativeResults.add(nativeCompletion(completion(i)));
            h.reject.set(true); assertFalse(h.queue.maintain(1000)); assertEquals(1, h.polls); assertEquals(2, h.nativeResults.size()); assertEquals(0, h.pending());
            h.queue.maintain(2000); assertEquals(1, h.polls); assertEquals(0, h.pending());
            h.reject.set(false); var app = h.storage.application(); app.addProperty("appliedRevision", 2); h.storage.saveApplication(app);
            h.storage.appendEvents(List.of(ControlledProviderStateTest.event(1)));
            assertTrue(h.queue.maintain(3000)); assertEquals(4, h.pending()); assertEquals(1, h.polls);
            assertEquals(2, h.storage.application().get("appliedRevision").getAsInt()); assertEquals(1, h.storage.outcomeBatch().getAsJsonArray("events").size());
            h.reopen(); assertEquals(4, h.pending()); assertEquals(completion(0), h.storage.diagnosticCompletionBatch().completions().get(0));
            h.queue.maintain(4000); assertEquals(6, h.pending()); assertEquals(2, h.polls);
        }
    }
    @Test void queueCapacityAndOriginalPrefixRemainFixedWhileNewResultsArrive(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            for (int i = 0; i < 36; i++) h.nativeResults.add(nativeCompletion(completion(i)));
            h.queue.maintain(1000); byte[] original = h.body();
            for (int i = 2; i <= 10; i++) h.queue.maintain(i * 1000L);
            assertEquals(32, h.pending()); assertEquals(8, h.polls); assertEquals(4, h.nativeResults.size()); assertArrayEquals(original, h.body());
            h.ack(original, 18, receipts(completion(0), completion(1), completion(2), completion(3)));
            assertEquals(28, h.pending()); assertEquals(4, h.count("recorded")); h.queue.maintain(11000); assertEquals(32, h.pending());
        }
    }
    @Test void fullReceiptsAndDequeueAreAtomicAcrossSaveFailureRestartAndJournalClearRetry(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.append(completion(0), completion(1)); byte[] original = h.body(); var intent = h.intent(original, 18); var receipt = h.receipt(intent);
            h.reject.set(true); assertThrows(IOException.class, () -> h.queue.acknowledge(intent, original, receipt, response(receipts(completion(0), completion(1)))));
            assertEquals(2, h.pending()); h.reject.set(false);
            h.queue.acknowledge(intent, original, receipt, null); // Exact safe receipt slice survives this process's failed write.
            assertEquals(0, h.pending()); assertEquals(2, h.count("recorded")); h.append(completion(2)); h.reopen();
            h.queue.acknowledge(intent, original, receipt, null); assertEquals(1, h.pending()); assertEquals(2, h.count("recorded"));
            assertEquals(completion(2), h.storage.diagnosticCompletionBatch().completions().get(0));
        }
    }
    @Test void bodylessCommittedRecoveryRetainsPrefixThenFreshExactReportRetrySettles(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.append(completion(0)); byte[] original = h.body(); h.reopen(); h.ack(original, 18, null);
            assertEquals(1, h.pending()); assertArrayEquals(original, h.body()); assertEquals(1, h.count("unsettledBatches"));
            h.reopen(); h.ack(original, 18, null); assertEquals(1, h.count("unsettledBatches"));
            h.ack(original, 19, receipts(completion(0))); assertEquals(0, h.pending()); assertEquals(1, h.count("recorded"));
        }
    }
    @Test void receiptBatchIsAllOrNothingAcrossWrongBindingDigestCountAndOriginalGrace(@TempDir Path path) throws Exception {
        var c0 = completion(0); var c1 = completion(1);
        var wrongBinding = mutate(c1, j -> j.getAsJsonObject("installation").addProperty("policyRevision", c1.installation().policyRevision() + 1));
        var wrongDigest = mutate(c1, j -> j.getAsJsonObject("frames").addProperty("sent", c1.frames().sent() + 1));
        var cases = List.of(receipts(c0), receipts(c0, wrongBinding), receipts(c0, wrongDigest),
                new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(receipt(c0, "recorded", c0.completedAt()), receipt(c1, "recorded", c1.completedAt()-1))),
                new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(receipt(c0, "recorded", c0.completedAt()), receipt(c1, "recorded", c1.expiresAt()+300001))),
                new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(receipt(c0, "recorded", c0.completedAt()), receipt(c1, "unmatched", 2000000000001L))));
        int n = 0; for (var r : cases) try (var h = new Harness(path.resolve("case" + n++))) {
            h.append(c0, c1); var body = h.body(); h.ack(body, 18, r); assertEquals(2, h.pending()); assertEquals(0, h.count("recorded")); assertEquals(0, h.count("unknown"));
        }
    }
    @Test void rejectedReportsSettleUnknownIncludingFutureHostTimeAndOriginalOwner(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            var c0 = completion(0); var c1 = mutate(completion(1), j -> j.getAsJsonObject("installation").addProperty("nativeOwnerEpoch", 2));
            h.append(c0, c1); byte[] original = h.body();
            h.ack(original, 18, new ControlDiagnosticCompletionReceiptCodec.Batch(List.of(receipt(c1, "conflict", 1), receipt(c0, "unmatched", 1))));
            assertEquals(0, h.pending()); assertEquals(2, h.count("unknown")); assertEquals(0, h.count("recorded"));
        }
    }
    @Test void optionalMalformedOrMissingReceiptsKeepQueueAndAreNotFatal(@TempDir Path path) throws Exception {
        for (String body : List.of("{}", "{\"diagnosticCompletions\":null}", "{\"diagnosticCompletions\":[]}", "{\"diagnosticCompletions\":{\"version\":2}}")) {
            try (var h = new Harness(path.resolve(Integer.toString(body.hashCode())))) {
                h.append(completion(0)); byte[] original = h.body(); var intent = h.intent(original, 18);
                assertDoesNotThrow(() -> h.queue.acknowledge(intent, original, h.receipt(intent), body.getBytes(StandardCharsets.UTF_8)));
                assertEquals(1, h.pending()); assertEquals(1, h.count("unsettledBatches"));
            }
        }
    }
    @Test void actualNativeCounterDeltaPersistsOnceAndRestartOnlyMarksUnknownHistory(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.dropped = 7; h.queue.maintain(1000); assertEquals(0, h.count("nativeDropped"));
            h.dropped = 10; h.reject.set(true); h.queue.maintain(2000); h.dropped = 12; h.queue.maintain(3000); assertEquals(0, h.count("nativeDropped"));
            h.reject.set(false); h.queue.maintain(4000); assertEquals(3, h.count("nativeDropped")); h.queue.maintain(5000); assertEquals(5, h.count("nativeDropped"));
            h.reopen(); h.dropped = 30; h.queue.maintain(6000); assertEquals(5, h.count("nativeDropped"));
            assertTrue(h.storage.diagnosticCompletionState().get("priorProcessLossUnknown").getAsBoolean());
        }
    }
    @Test void duplicateSameAttemptDoesNotGrowQueueButDifferentResultIsUnknown(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            var c = completion(0); h.append(c); h.append(c); assertEquals(1, h.pending());
            h.append(mutate(c, j -> j.getAsJsonObject("frames").addProperty("sent", c.frames().sent()+1)));
            assertEquals(1, h.pending()); assertEquals(1, h.count("invalidNative")); assertEquals(c, h.storage.diagnosticCompletionBatch().completions().get(0));
        }
    }
    @Test void reportingRequiresOptInAndHistoricalReportsAloneDoNotClaimNativeReadiness(@TempDir Path path) throws Exception {
        try (var store = new ProviderStateStore(path)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledNativeOwnerApplicationTest.ORIGIN))) {
                var queue = new ControlledDiagnosticCompletions(storage, null, code -> fail("unexpected poll notice"));
                assertFalse(queue.maintain(1000)); assertThrows(IOException.class, () -> storage.appendDiagnosticCompletions(List.of(completion(0)), 0, 0));
                var body = ("{\"diagnosticCompletions\":" + ControlDiagnosticCompletionCodec.encodeBatch(new ControlDiagnosticCompletionCodec.Batch(List.of(completion(0)))) + "}").getBytes(StandardCharsets.UTF_8);
                var i = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 18, "completion_policy_fixture", body);
                assertFalse(queue.requiresAcknowledgement(i, body)); assertFalse(ControlledProviderApplication.requiresNativeCancellation(i, body));
            }
        }
    }
    @Test void actualApplicationIncludesDurableBatchAndMalformedReceiptLeavesPlayersAndNativeInstallationIntact(@TempDir Path path) throws Exception {
        try (var h = new ControlledDiagnosticApplicationTest.Harness(path)) {
            h.ready(); var c = completion(0); h.storage.appendDiagnosticCompletions(List.of(c), 0, 0);
            int playerInstalls = h.nativeHost.delegate.installs, commits = h.nativeHost.delegate.commits, diagnostics = h.nativeHost.installs;
            var handle = h.nativeHost.diagnostic;
            var seen = new AtomicInteger();
            h.inspect = body -> {
                var batch = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(body.toString());
                assertNotNull(batch); assertEquals(List.of(c), batch.completions()); seen.incrementAndGet();
            };
            h.alter = body -> body.add("diagnosticCompletions", JsonNull.INSTANCE);
            h.sync(); assertTrue(seen.get() > 0); assertTrue(h.nativeHost.delegate.enabled); assertSame(handle, h.nativeHost.diagnostic);
            assertEquals(playerInstalls, h.nativeHost.delegate.installs); assertEquals(commits, h.nativeHost.delegate.commits); assertEquals(diagnostics, h.nativeHost.installs);
            var body = ("{\"diagnosticCompletions\":" + ControlDiagnosticCompletionCodec.encodeBatch(h.storage.diagnosticCompletionBatch()) + "}").getBytes(StandardCharsets.UTF_8);
            var i = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 18, "diagnostic_application_settle", body);
            var r = new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(i), "heartbeat", "fixture_host", 1, 18, i.idempotencyKey(), "committed", 2000000000000L, 1L, null);
            h.app.acknowledgeDiagnosticCompletions(i, body, r, "{\"diagnosticCompletions\":null}".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().join();
            assertNotNull(h.storage.diagnosticCompletionBatch()); assertTrue(h.nativeHost.delegate.enabled);
            // A current native owner is irrelevant to delivery of original historical report bytes.
            h.nativeHost.retired = true;
            h.app.acknowledgeDiagnosticCompletions(i, body, r, response(receipts(c))).toCompletableFuture().join();
            assertNull(h.storage.diagnosticCompletionBatch()); assertEquals(playerInstalls, h.nativeHost.delegate.installs);
        }
    }
    @Test void malformedNativeCaptureCountsUnknownWithoutLosingOtherFamilyAndForeignHostIsRejected(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            var original = nativeCompletion(completion(0));
            h.nativeResults.add(new DiagnosticAdmission.Completion(null, original.context(), original.keyId(), original.attemptId(), original.offerDigestHex(), original.clientFingerprintHex(),
                    original.expiresAt(), original.target(), original.success(), original.cleanupComplete(), original.reason(), original.selectedLocal(), original.selectedRemote(), original.udp(),
                    original.sentFrames(), original.sentBytes(), original.receivedFrames(), original.receivedBytes(), original.completionDigestHex(), original.completedAt()));
            h.nativeResults.add(nativeCompletion(completion(1))); assertTrue(h.queue.maintain(1000));
            assertEquals(1, h.count("invalidNative")); assertEquals(List.of(completion(1)), h.storage.diagnosticCompletionBatch().completions());
            var foreign = mutate(completion(2), j -> j.getAsJsonObject("installation").addProperty("hostId", "foreign_host"));
            assertThrows(IllegalArgumentException.class, () -> h.append(foreign)); assertEquals(1, h.pending());
        }
    }
    @Test void originalBodyAndParentReceiptCannotBeReassociatedWithAnotherSequence(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.append(completion(0)); byte[] body = h.body(); var intent = h.intent(body, 18);
            assertThrows(IllegalArgumentException.class, () -> h.queue.acknowledge(intent, body, h.receipt(h.intent(body, 19)), response(receipts(completion(0)))));
            assertEquals(1, h.pending()); h.ack(body, 18, receipts(completion(0)));
            h.append(completion(1)); assertThrows(IOException.class, () -> h.ack(h.body(), 18, receipts(completion(1)))); assertEquals(1, h.pending());
        }
    }

    @Test void heartbeatByteBudgetSelectsOnlyFittingImmutablePrefixAndCanDeferWithoutDeleting(@TempDir Path path) throws Exception {
        try (var h = new Harness(path)) {
            h.append(completion(0), completion(1), completion(2), completion(3));
            var single = new JsonObject(); single.add("diagnosticCompletions", JsonParser.parseString(ControlDiagnosticCompletionCodec.encodeBatch(new ControlDiagnosticCompletionCodec.Batch(List.of(completion(0))))));
            int oneSize = single.toString().getBytes(StandardCharsets.UTF_8).length;
            var body = new JsonObject(); body.addProperty("padding", "p".repeat(ControlLifecycleCodec.MAX_WS_BODY_BYTES - oneSize - 30));
            h.queue.appendToHeartbeat(body); var batch = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(body.toString());
            assertEquals(List.of(completion(0)), batch.completions()); assertEquals(4, h.pending());
            assertTrue(body.toString().getBytes(StandardCharsets.UTF_8).length <= ControlLifecycleCodec.MAX_WS_BODY_BYTES);
            var full = new JsonObject(); full.addProperty("padding", "p".repeat(ControlLifecycleCodec.MAX_WS_BODY_BYTES - 30));
            h.queue.appendToHeartbeat(full); assertFalse(full.has("diagnosticCompletions")); assertEquals(4, h.pending());
        }
    }

    @Test void maximalClosedCompletionFieldsFitAggregateBatchBound() throws Exception {
        var reports = new ArrayList<ControlDiagnosticCompletionCodec.Completion>();
        String origin = "https://" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(50) + ":65535";
        assertEquals(256, origin.length());
        for (int i = 0; i < 4; i++) reports.add(mutate(completion(i), j -> {
            var b = j.getAsJsonObject("installation"); b.addProperty("providerOrigin", origin); b.addProperty("hostId", "h".repeat(128));
            b.addProperty("authorityIncarnation", "a".repeat(128)); b.addProperty("hostProfileRevision", "p".repeat(128));
            for (String key : List.of("generation", "nativeOwnerEpoch", "policyRevision")) b.addProperty(key, 9007199254740991L);
            j.addProperty("success", false); j.addProperty("cleanupComplete", false); j.addProperty("reason", "invalid_diagnostic_protocol");
            j.add("completionDigestHex", JsonNull.INSTANCE); j.addProperty("expiresAt", 4294967295000L); j.addProperty("completedAt", 4294967595000L);
            j.getAsJsonObject("target").addProperty("candidateRevision", 9007199254740991L); j.getAsJsonObject("target").addProperty("port", 65535);
            for (String tuple : List.of("selectedLocal", "selectedRemote")) j.getAsJsonObject(tuple).addProperty("port", 65535);
            for (String key : List.of("sent", "sentBytes", "received", "receivedBytes")) j.getAsJsonObject("frames").addProperty(key, Integer.MAX_VALUE);
            for (String key : List.of("reserved", "sent", "sentBytes", "rejected")) j.getAsJsonObject("udp").addProperty(key, 9007199254740991L);
        }));
        String wire = ControlDiagnosticCompletionCodec.encodeBatch(new ControlDiagnosticCompletionCodec.Batch(reports));
        assertTrue(wire.getBytes(StandardCharsets.UTF_8).length < 10000, "Closed-field maxima leave wrapper headroom");
        assertEquals(reports, ControlDiagnosticCompletionCodec.decodeBatch(wire).completions());
    }

}
