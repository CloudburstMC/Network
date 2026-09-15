package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledProviderExternalDiagnosticFixtureTest {
    private static final String SECRET = "fixture-private-epoch-material-12345";
    private static DiagnosticAdmission.Policy policy(int family) throws Exception {
        var context = new DiagnosticAdmissionCodec.Context("https://127.0.0.1:8443", "fixture_host", "0".repeat(32), 1);
        var binding = new DiagnosticAdmission.Binding(context, "authority_fixture", 1, "profile_fixture", "A".repeat(43), 2, "A".repeat(43), "0".repeat(64));
        var endpoint = new DiagnosticHostPolicy.Endpoint(family, DiagnosticAdmissionCodec.address(family, family == 4 ? "127.0.0.1" : "::1"), 19132, 3);
        return new DiagnosticAdmission.Policy(binding, List.of(new DiagnosticAdmissionCodec.Key("D001", SECRET, 0, 100_000)),
                List.of(new DiagnosticAdmission.Endpoint(endpoint, "host", 50_000)), 1000, 100_000);
    }
    private static DiagnosticAdmission.Completion completion(DiagnosticAdmission.Policy policy, String change) throws Exception {
        var target = policy.endpoints().get(0).target();
        var local = new InetSocketAddress(InetAddress.getByName(target.family() == 4 ? "127.0.0.1" : "::1"), 19132);
        var remote = new InetSocketAddress(local.getAddress(), 19133);
        var udp = new DiagnosticAdmission.UdpCounters(10, 10, 1000, change.equals("blocked") ? 1 : 0);
        var binding = policy.binding();
        if (change.equals("installation")) binding = new DiagnosticAdmission.Binding(binding.context(), binding.authorityIncarnation(), 2,
                binding.hostProfileRevision(), binding.hostProfileSha256(), binding.policyRevision(), binding.installationSha256(), binding.hostFingerprintHex());
        if (change.equals("candidate")) target = new DiagnosticHostPolicy.Endpoint(target.family(), target.addressHex(), target.port(), target.candidateRevision() + 1);
        if (change.equals("local")) local = new InetSocketAddress(local.getAddress(), 19134);
        if (change.equals("family")) remote = new InetSocketAddress(InetAddress.getByName(target.family() == 4 ? "::1" : "127.0.0.1"), 19133);
        return new DiagnosticAdmission.Completion(binding, binding.context(), change.equals("key") ? "D002" : "D001", "1".repeat(32), "2".repeat(64), "3".repeat(64),
                change.equals("expiry") ? 50_001 : 40_000, target, !change.equals("failed"), !change.equals("cleanup"), "complete", local, remote, udp,
                4, 100, change.equals("frames") ? 0 : 4, 100, change.equals("transcript") ? null : "4".repeat(64), change.equals("late") ? 40_000 : 30_000);
    }
    @Test void externalModeRequiresExplicitDiagnosticsAndStrictBoolean() {
        var value = new JsonObject();
        assertFalse(ControlledProviderLocalSmokeClient.externalDiagnosticMode(value, true));
        value.addProperty("externalDiagnosticCheck", true);
        assertTrue(ControlledProviderLocalSmokeClient.externalDiagnosticMode(value, true));
        assertThrows(IllegalArgumentException.class, () -> ControlledProviderLocalSmokeClient.externalDiagnosticMode(value, false));
        for (JsonElement invalid : List.of(new JsonPrimitive("true"), new JsonPrimitive(1), JsonNull.INSTANCE)) {
            value.add("externalDiagnosticCheck", invalid);
            assertThrows(IllegalArgumentException.class, () -> ControlledProviderLocalSmokeClient.externalDiagnosticMode(value, true));
        }
    }
    @Test void bothFamiliesEmitOnlyOriginalPublicCompletionMetadata() throws Exception {
        for (int family : List.of(4, 6)) {
            var policy = policy(family); var result = completion(policy, "valid");
            var event = ControlledProviderLocalSmokeClient.diagnosticCompletion(policy, result, result.selectedLocal());
            assertEquals(family, event.getAsJsonObject("selectedLocal").get("family").getAsInt());
            assertEquals(family, event.getAsJsonObject("selectedRemote").get("family").getAsInt());
            assertEquals(policy.endpoints().get(0).target().addressHex(), event.getAsJsonObject("selectedLocal").get("addressHex").getAsString());
            assertEquals(19133, event.getAsJsonObject("selectedRemote").get("port").getAsInt());
            assertEquals("D001", event.get("keyId").getAsString());
            assertEquals(3, event.get("candidateRevision").getAsInt());
            assertEquals("[1,1,1,1]", event.get("directions").toString());
            assertEquals(10, event.getAsJsonObject("udp").get("sent").getAsInt());
            assertFalse(event.toString().contains(SECRET)); assertFalse(event.has("keys")); assertFalse(event.has("answerCatalog"));
        }
    }
    @Test void historicalWrongIncompleteAndUnboundedObservationsCannotBecomeSuccess() throws Exception {
        var policy = policy(4); var original = completion(policy, "valid");
        for (String change : List.of("installation", "candidate", "local", "family", "key", "expiry", "failed", "cleanup", "frames", "transcript", "late", "blocked")) {
            var changed = completion(policy, change);
            assertThrows(RuntimeException.class, () -> ControlledProviderLocalSmokeClient.diagnosticCompletion(policy, changed, original.selectedLocal()), change);
        }
    }
}
