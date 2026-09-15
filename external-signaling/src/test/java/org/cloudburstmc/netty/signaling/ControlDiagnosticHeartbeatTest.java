package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticHeartbeatTest {
    private static List<Installation> fixtures() throws Exception {
        var path = Path.of("../docs/external-signaling/control-v1.diagnostic-installation.fixtures.json");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("vectors").asList().stream()
                .map(value -> decodeInstallation(value.getAsJsonObject().get("wire").getAsString())).toList();
    }
    @Test void sharedInstallationsRoundTripWithSeparateFullAcksAndRedactedText() throws Exception {
        for (var d : fixtures()) {
            var ack = new Acknowledgement(d.binding()); var request = new ControlDiagnosticHeartbeatCodec.Request(ack);
            var response = new ControlDiagnosticHeartbeatCodec.Response(d, ack);
            assertEquals(request, ControlDiagnosticHeartbeatCodec.decodeRequest(ControlDiagnosticHeartbeatCodec.encodeRequest(request)));
            assertEquals(response, ControlDiagnosticHeartbeatCodec.decodeResponse(ControlDiagnosticHeartbeatCodec.encodeResponse(response)));
            assertFalse(response.toString().contains(d.keys().get(0).secret()));
            var nulls = new ControlDiagnosticHeartbeatCodec.Response(null, null);
            assertEquals(nulls, ControlDiagnosticHeartbeatCodec.decodeResponse(ControlDiagnosticHeartbeatCodec.encodeResponse(nulls)));
        }
    }
    @Test void independentPythonHeartbeatVectorsMatchExactJvmWireBytes() throws Exception {
        var file = Path.of("../docs/external-signaling/control-v1.diagnostic-installation.fixtures.json");
        var fixtures = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("vectors");
        assertEquals(5, fixtures.size());
        for (var item : fixtures) {
            var value = item.getAsJsonObject(); String request = value.get("heartbeatRequestWire").getAsString(), response = value.get("heartbeatResponseWire").getAsString();
            assertEquals(request, ControlDiagnosticHeartbeatCodec.encodeRequest(ControlDiagnosticHeartbeatCodec.decodeRequest(request)));
            assertEquals(response, ControlDiagnosticHeartbeatCodec.encodeResponse(ControlDiagnosticHeartbeatCodec.decodeResponse(response)));
            assertEquals(value.get("sha256").getAsString(), installationDigest(ControlDiagnosticHeartbeatCodec.decodeResponse(response).expected()));
        }
    }
    @Test void originalRootExtractionPreservesStrictSliceAndEscapedPropertyNames() {
        String slice = "{\"version\":1,\"installed\":null}", wire = "{\"load\":0.5,\"diagno\\u0073ticAdmission\":" + slice + ",\"note\":\"diagnosticAdmission:{}\"}";
        assertEquals(slice, ControlledProviderJson.rootProperty(wire, "diagnosticAdmission", 65536));
        assertNull(ControlDiagnosticHeartbeatCodec.decodeRequest(ControlledProviderJson.rootProperty(wire, "diagnosticAdmission", 65536)).installed());
        assertNull(ControlledProviderJson.rootProperty("{\"nested\":{\"diagnosticAdmission\":{}}}", "diagnosticAdmission", 65536));
        for (String spelling : List.of("1.0", "1e0", "-0")) {
            String input = wire.replace("\"version\":1", "\"version\":" + spelling);
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticHeartbeatCodec.decodeRequest(ControlledProviderJson.rootProperty(input, "diagnosticAdmission", 65536)));
        }
        assertThrows(IllegalArgumentException.class, () -> ControlledProviderJson.rootProperty("{\"diagnosticAdmission\":" + slice + ",\"diagno\\u0073ticAdmission\":" + slice + "}", "diagnosticAdmission", 65536));
        assertThrows(IllegalArgumentException.class, () -> ControlledProviderJson.rootProperty("{\"diagnosticAdmission\":" + slice + ",\"unrelated\":{\"x\":1,\"x\":2}}", "diagnosticAdmission", 65536));
    }
    @Test void wrapperWhitespaceLimitsDoNotRestrictUnrelatedHeartbeatPadding() {
        String padded = "{" + " ".repeat(2112) + "\"version\":1,\"installed\":null}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticHeartbeatCodec.decodeRequest(ControlledProviderJson.rootProperty("{\"diagnosticAdmission\":" + padded + "}", "diagnosticAdmission", 65536)));
        String small = "{\"version\":1,\"installed\":null}";
        assertNull(ControlDiagnosticHeartbeatCodec.decodeRequest(ControlledProviderJson.rootProperty("{" + " ".repeat(3000) + "\"diagnosticAdmission\":" + small + "}", "diagnosticAdmission", 65536)).installed());
        assertThrows(IllegalArgumentException.class, () -> ControlledProviderJson.rootProperty("{" + " ".repeat(65536) + "\"diagnosticAdmission\":" + small + "}", "diagnosticAdmission", 65536));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticHeartbeatCodec.decodeResponse("{" + " ".repeat(26752) + "\"version\":1,\"expected\":null,\"accepted\":null}"));
    }
    @Test void closedWrappersRejectExtraMissingDuplicateAndWrongDirectionFields() {
        for (String wire : List.of("null", "{}", "{\"version\":1}", "{\"version\":1,\"installed\":null,\"extra\":1}",
                "{\"version\":1,\"installed\":null,\"installed\":null}", "{\"version\":1,\"expected\":null,\"accepted\":null}"))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticHeartbeatCodec.decodeRequest(wire));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticHeartbeatCodec.decodeResponse("{\"version\":1,\"installed\":null}"));
    }
    @Test void explicitConfigurationRequiresIssuedOwnerAndCannotSilentlyDowngradePersistedMode(@TempDir Path path) throws Exception {
        var base = ControlledProviderStateTest.config(ControlledNativeOwnerApplicationTest.ORIGIN);
        assertThrows(IllegalArgumentException.class, () -> new ProviderControlConfiguration(base.routes(), base.providerKeys(), base.migrationSeed(),
                ProviderControlConfiguration.NativeOwnership.DISABLED, ProviderControlConfiguration.CandidatePublication.DISABLED, ProviderControlConfiguration.Diagnostics.ENABLED));
        try (var store = new ProviderStateStore(path)) {
            ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
            try (var state = ControlledProviderState.open(store, ControlledDiagnosticApplicationTest.config())) {
                assertEquals("install-v1", state.application().get("diagnosticAdmission").getAsString());
                var data = state.application(); data.remove("diagnosticAdmission"); assertThrows(java.io.IOException.class, () -> state.saveApplication(data));
            }
            assertThrows(java.io.IOException.class, () -> ControlledProviderState.open(store, ControlledNativeOwnerApplicationTest.config()));
        }
    }
}
