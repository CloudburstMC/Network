package org.cloudburstmc.netty.signaling.diagnostic;

import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionCodec;
import org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.HexFormat;
import java.util.Objects;

/** Pure conversion of original native observations. No polling, profile lookup, upload or direction synthesis. */
public final class DiagnosticCompletionEmitter {
    private DiagnosticCompletionEmitter() { }

    public static ControlDiagnosticCompletionCodec.Completion from(NativeDiagnosticHostGate.Result result) {
        Objects.requireNonNull(result);
        var stats = result.udp();
        var counters = stats == null ? null : new DiagnosticAdmission.UdpCounters(stats.reservedDatagrams(), stats.sentDatagrams(),
                stats.sentBytes(), stats.rejectedDatagrams());
        return from(new DiagnosticAdmission.Completion(result.installation(), result.context(), result.keyId(), result.attemptId(),
                result.offerDigestHex(), result.clientFingerprintHex(), result.expiresAt(), result.target(), result.success(),
                result.cleanupComplete(), result.reason(), result.selectedLocal(), result.selectedRemote(), counters,
                result.sentFrames(), result.sentBytes(), result.receivedFrames(), result.receivedBytes(), result.completionDigestHex(), result.completedAt()));
    }
    public static ControlDiagnosticCompletionCodec.Completion from(DiagnosticAdmission.Completion result) {
        Objects.requireNonNull(result);
        var installation = result.installation();
        if (installation == null || !installation.context().equals(result.context()))
            throw new IllegalArgumentException("Original diagnostic installation/context required");
        var context = installation.context();
        var binding = new ControlDiagnosticInstallationCodec.Binding(context.providerOrigin(), context.hostId(), installation.authorityIncarnation(),
                context.generation(), installation.nativeOwnerEpoch(), context.incarnation(), installation.hostProfileRevision(),
                installation.hostProfileSha256(), installation.hostFingerprintHex(), installation.policyRevision(), installation.installationSha256());
        var t = Objects.requireNonNull(result.target());
        var target = new ControlDiagnosticCompletionCodec.Target(t.family(), t.addressHex(), t.port(), t.candidateRevision());
        var f = new ControlDiagnosticCompletionCodec.Frames(result.sentFrames(), result.sentBytes(), result.receivedFrames(), result.receivedBytes());
        var u = result.udp() == null ? null : new ControlDiagnosticCompletionCodec.Udp(result.udp().reserved(), result.udp().sent(),
                result.udp().sentBytes(), result.udp().rejected());
        return new ControlDiagnosticCompletionCodec.Completion(binding, result.keyId(), result.attemptId(), result.offerDigestHex(),
                result.clientFingerprintHex(), result.expiresAt(), target, result.success(), result.reason(), result.completedAt(),
                result.cleanupComplete(), result.completionDigestHex(), tuple(result.selectedLocal()), tuple(result.selectedRemote()), f, u);
    }
    private static ControlDiagnosticCompletionCodec.SelectedTuple tuple(InetSocketAddress input) {
        if (input == null) return null;
        if (input.isUnresolved()) throw new IllegalArgumentException("Unresolved diagnostic selected tuple");
        var address = input.getAddress(); int family;
        if (address instanceof Inet4Address) family = 4;
        else if (address instanceof Inet6Address v6 && v6.getScopeId() == 0 && v6.getScopedInterface() == null) family = 6;
        else throw new IllegalArgumentException("Unsupported or scoped diagnostic selected tuple");
        byte[] raw = address.getAddress(), packed = new byte[16];
        System.arraycopy(raw, 0, packed, packed.length - raw.length, raw.length);
        return new ControlDiagnosticCompletionCodec.SelectedTuple(family, HexFormat.of().formatHex(packed), input.getPort());
    }
}
