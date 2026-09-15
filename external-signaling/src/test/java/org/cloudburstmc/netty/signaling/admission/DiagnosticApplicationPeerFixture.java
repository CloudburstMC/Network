package org.cloudburstmc.netty.signaling.admission;

import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAnswerCodec;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.admission.NativeDiagnosticHostTest.*;

/** Reuses the signed NXD1/answer, ICE, DTLS and two-channel data fixture around an application rebind. */
public final class DiagnosticApplicationPeerFixture {
    @FunctionalInterface public interface Rebind { void run() throws Exception; }
    public static DiagnosticAdmission.Completion admittedAcrossRebind(NativeProviderTransport transport, NativeHostIdentity identity,
            DiagnosticAdmission.Policy original, Rebind rebind) throws Exception {
        return admittedAcrossRebind(transport, identity, original, null, null, rebind);
    }
    public static DiagnosticAdmission.Completion admittedAcrossRebind(NativeProviderTransport transport, NativeHostIdentity identity,
            DiagnosticAdmission.Policy original, DiagnosticAnswerCodec.Signer signer, DiagnosticAnswerCodec.Catalog catalog, Rebind rebind) throws Exception {
        var local = (InetSocketAddress) transport.channel().localAddress(); int family = local.getAddress() instanceof Inet6Address ? 6 : 4;
        long expiry = (System.currentTimeMillis() + 18000) / 1000 * 1000;
        try (Client client = new Client(local.getAddress(), local.getPort(), expiry)) {
            long candidateRevision = original.endpoints().stream().filter(endpoint -> endpoint.target().family() == family
                    && endpoint.target().port() == local.getPort()).findFirst().orElseThrow().target().candidateRevision();
            client.connect(identity, original.binding().context(), original.keys().get(0), family, local.getAddress().getHostAddress(), local.getPort(), false,
                    candidateRevision, signer, catalog);
            await(() -> client.channels[0].isOpen() && client.channels[1].isOpen());
            rebind.run(); client.start(false);
            var completed = new ArrayList<DiagnosticAdmission.Completion>();
            await(() -> { client.tick(); completed.addAll(transport.pollDiagnosticResults(1)); return !completed.isEmpty(); });
            var report = completed.get(0); assertTrue(report.success(), report.toString()); assertTrue(report.cleanupComplete());
            assertEquals(original.binding(), report.installation()); assertEquals(expiry, report.expiresAt());
            assertEquals(client.exchange.completionDigestHex(), report.completionDigestHex());
            assertTrue(report.sentFrames() > 0); assertTrue(report.receivedFrames() > 0); assertTrue(report.udp().sent() > 0);
            assertEquals(0, transport.channel().liveNativePeers()); return report;
        }
    }
    private DiagnosticApplicationPeerFixture() { }
}
