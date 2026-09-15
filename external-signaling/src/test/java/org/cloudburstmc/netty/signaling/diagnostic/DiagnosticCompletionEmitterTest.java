package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionCodec;
import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.UdpSendStats;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCompletionEmitterTest {
    private static ControlDiagnosticCompletionCodec.Completion fixture(int index) throws Exception {
        Path p = Path.of("docs/external-signaling/control-v1.diagnostic-completion.fixtures.json");
        if (!Files.exists(p)) p = Path.of("..").resolve(p);
        return ControlDiagnosticCompletionCodec.decodeCompletion(JsonParser.parseString(Files.readString(p)).getAsJsonObject()
                .getAsJsonArray("vectors").get(index).getAsJsonObject().get("wire").getAsString());
    }
    private static InetSocketAddress tuple(ControlDiagnosticCompletionCodec.SelectedTuple t) throws Exception {
        if (t == null) return null;
        byte[] packed = HexFormat.of().parseHex(t.addressHex());
        return new InetSocketAddress(InetAddress.getByAddress(t.family() == 4 ? Arrays.copyOfRange(packed, 12, 16) : packed), t.port());
    }
    private static DiagnosticAdmission.Completion nativeCompletion(ControlDiagnosticCompletionCodec.Completion c) throws Exception {
        var b = c.installation(); var context = new DiagnosticAdmissionCodec.Context(b.providerOrigin(), b.hostId(), b.nativeIncarnation(), b.generation());
        var binding = new DiagnosticAdmission.Binding(context, b.authorityIncarnation(), b.nativeOwnerEpoch(), b.hostProfileRevision(),
                b.hostProfileSha256(), b.policyRevision(), b.installationSha256(), b.hostFingerprintHex());
        var t = c.target(); var target = new DiagnosticHostPolicy.Endpoint(t.family(), t.addressHex(), t.port(), t.candidateRevision());
        var u = c.udp() == null ? null : new DiagnosticAdmission.UdpCounters(c.udp().reserved(), c.udp().sent(), c.udp().sentBytes(), c.udp().rejected());
        return new DiagnosticAdmission.Completion(binding, context, c.keyId(), c.attemptIdHex(), c.offerDigestHex(), c.clientFingerprintHex(),
                c.expiresAt(), target, c.success(), c.cleanupComplete(), c.reason(), tuple(c.selectedLocal()), tuple(c.selectedRemote()), u,
                c.frames().sent(), c.frames().sentBytes(), c.frames().received(), c.frames().receivedBytes(), c.completionDigestHex(), c.completedAt());
    }
    private static DiagnosticAdmission.Completion replace(DiagnosticAdmission.Completion c, DiagnosticAdmission.Binding binding,
            DiagnosticAdmissionCodec.Context context, InetSocketAddress local, InetSocketAddress remote, long completedAt) {
        return new DiagnosticAdmission.Completion(binding, context, c.keyId(), c.attemptId(), c.offerDigestHex(), c.clientFingerprintHex(),
                c.expiresAt(), c.target(), c.success(), c.cleanupComplete(), c.reason(), local, remote, c.udp(),
                c.sentFrames(), c.sentBytes(), c.receivedFrames(), c.receivedBytes(), c.completionDigestHex(), completedAt);
    }
    @Test void neutralAdapterMatchesBothFamiliesAndPreservesForwardedHostLocalWithoutAnyLookup() throws Exception {
        for (int index = 0; index < 2; index++) {
            var expected = fixture(index); var actual = DiagnosticCompletionEmitter.from(nativeCompletion(expected));
            assertEquals(expected, actual);
            assertEquals(ControlDiagnosticCompletionCodec.completionDigest(expected), ControlDiagnosticCompletionCodec.completionDigest(actual));
            if (index == 0) { assertNotEquals(actual.target().addressHex(), actual.selectedLocal().addressHex());
                assertNotEquals(actual.target().port(), actual.selectedLocal().port()); }
        }
    }
    @Test void hostGateResultOverloadMapsOriginalCountersWithoutInventingMissingStats() throws Exception {
        for (int index = 0; index < 6; index++) {
            var expected = fixture(index); var c = nativeCompletion(expected);
            // A public counter fixture only. This test does not create native transport or claim packet delivery.
            UdpSendStats stats = null;
            if (c.udp() != null) {
                var constructor = UdpSendStats.class.getDeclaredConstructor(long[].class); constructor.setAccessible(true);
                stats = constructor.newInstance((Object) new long[]{c.udp().reserved(), c.udp().sent(), c.udp().sentBytes(), c.udp().rejected(), 0});
            }
            var result = new NativeDiagnosticHostGate.Result(c.context(), c.keyId(), c.attemptId(), c.offerDigestHex(), c.clientFingerprintHex(),
                    c.expiresAt(), c.target(), c.success(), c.reason(), c.selectedLocal(), c.selectedRemote(), stats,
                    c.sentFrames(), c.sentBytes(), c.receivedFrames(), c.receivedBytes(), c.completionDigestHex(), c.completedAt(), c.installation(), c.cleanupComplete());
            assertEquals(expected, DiagnosticCompletionEmitter.from(result));
        }
    }
    @Test void missingOriginalInstallationCannotBeReboundOrUploadedEvenForLowLevelSuccessfulResult() throws Exception {
        var c = nativeCompletion(fixture(0));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, null, c.context(), c.selectedLocal(), c.selectedRemote(), c.completedAt())));
        var result = new NativeDiagnosticHostGate.Result(c.context(), c.keyId(), c.attemptId(), c.offerDigestHex(), c.clientFingerprintHex(),
                c.expiresAt(), c.target(), false, "transport_failed", null, null, null, 0, 0, 0, 0, null, c.completedAt(), null, true);
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(result));
    }
    @Test void independentContextMustExactlyMatchOriginalAdmissionBinding() throws Exception {
        var c = nativeCompletion(fixture(0)); var original = c.context();
        for (var wrong : new DiagnosticAdmissionCodec.Context[]{
                new DiagnosticAdmissionCodec.Context("https://other.example", original.hostId(), original.incarnation(), original.generation()),
                new DiagnosticAdmissionCodec.Context(original.providerOrigin(), "other_host", original.incarnation(), original.generation()),
                new DiagnosticAdmissionCodec.Context(original.providerOrigin(), original.hostId(), "ff".repeat(16), original.generation()),
                new DiagnosticAdmissionCodec.Context(original.providerOrigin(), original.hostId(), original.incarnation(), original.generation() + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, c.installation(), wrong, c.selectedLocal(), c.selectedRemote(), c.completedAt())));
        }
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, c.installation(), null, c.selectedLocal(), c.selectedRemote(), c.completedAt())));
    }
    @Test void rejectsUnresolvedScopedOrWrongFamilyTuplesWithoutResolvingHostnames() throws Exception {
        var c = nativeCompletion(fixture(0));
        var unresolved = InetSocketAddress.createUnresolved("must-not-resolve.invalid", 19132);
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, c.installation(), c.context(), unresolved, c.selectedRemote(), c.completedAt())));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, c.installation(), c.context(), c.selectedLocal(), unresolved, c.completedAt())));
        var v6 = nativeCompletion(fixture(1));
        var scoped = new InetSocketAddress(Inet6Address.getByAddress(null, HexFormat.of().parseHex("fe800000000000000000000000000001"), 1), 19132);
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(v6, v6.installation(), v6.context(), scoped, v6.selectedRemote(), v6.completedAt())));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, c.installation(), c.context(), v6.selectedLocal(), c.selectedRemote(), c.completedAt())));
    }
    @Test void latePollingPreservesOriginalBindingDeadlineAndCleanupButExpiredSuccessCannotBeInvented() throws Exception {
        var c = nativeCompletion(fixture(0)); var queued = new java.util.ArrayList<Runnable>();
        var pending = CompletableFuture.supplyAsync(() -> DiagnosticCompletionEmitter.from(c), queued::add);
        // A separate newer installation does not replace the original capture retained in c.
        var old = c.installation(); var newer = new DiagnosticAdmission.Binding(old.context(), old.authorityIncarnation(), old.nativeOwnerEpoch() + 1,
                "hpr_new_profile", old.hostProfileSha256(), old.policyRevision() + 1, old.installationSha256(), old.hostFingerprintHex());
        assertNotEquals(old, newer); queued.get(0).run();
        assertEquals(old.nativeOwnerEpoch(), pending.join().installation().nativeOwnerEpoch()); assertEquals(c.expiresAt(), pending.join().expiresAt());
        assertEquals(c.completedAt(), pending.join().completedAt());
        assertThrows(IllegalArgumentException.class, () -> DiagnosticCompletionEmitter.from(replace(c, old, c.context(), c.selectedLocal(), c.selectedRemote(), c.expiresAt())));
        var failure = DiagnosticCompletionEmitter.from(nativeCompletion(fixture(4)));
        assertFalse(failure.success()); assertFalse(failure.cleanupComplete()); assertNull(failure.completionDigestHex());
    }
}
