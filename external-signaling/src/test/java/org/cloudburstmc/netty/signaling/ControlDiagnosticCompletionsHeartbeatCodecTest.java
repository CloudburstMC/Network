package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionCodec;
import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionReceiptCodec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ControlDiagnosticCompletionsHeartbeatCodecTest {
    private static JsonObject fixtures() throws Exception {
        Path p = Path.of("docs/external-signaling/control-v1.diagnostic-completion-receipt.fixtures.json");
        if (!Files.exists(p)) p = Path.of("..").resolve(p);
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }
    private static String slice(String field) throws Exception { return fixtures().getAsJsonArray("heartbeats").get(1).getAsJsonObject().get(field).getAsString(); }
    @TestFactory Stream<DynamicTest> independentOptionalOriginalHeartbeatVectors() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var item : fixtures().getAsJsonArray("heartbeats")) {
            var v = item.getAsJsonObject(); tests.add(DynamicTest.dynamicTest(v.get("name").getAsString(), () -> {
                var request = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(v.get("requestWire").getAsString());
                var response = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(v.get("responseWire").getAsString());
                if (v.get("requestSlice").isJsonNull()) assertNull(request);
                else assertEquals(v.get("requestSlice").getAsString(), ControlDiagnosticCompletionCodec.encodeBatch(request));
                if (v.get("responseSlice").isJsonNull()) assertNull(response);
                else assertEquals(v.get("responseSlice").getAsString(), ControlDiagnosticCompletionReceiptCodec.encodeBatch(response));
            }));
        }
        return tests.stream();
    }
    @Test void originalNestedReceiptIntegerTokensAndUnrelatedDuplicatesCannotBeHiddenByParsing() throws Exception {
        String receipt = slice("responseSlice"), request = slice("requestSlice");
        for (String spelling : List.of("1800000005100.0", "1800000005100e0", "-0", "9007199254740993")) {
            String changed = receipt.replace("1800000005100", spelling);
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts("{\"load\":0.5,\"diagnosticCompletions\":" + changed + "}"));
        }
        for (String payload : List.of(request, receipt)) {
            String duplicate = "{\"diagnosticCompletions\":" + payload + ",\"diagnosticCompl\\u0065tions\":" + payload + "}";
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(duplicate));
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(duplicate));
        }
        for (String wire : List.of("{\"unrelated\":{\"x\":1,\"x\":2}}", "{\"diagnosticCompletions\":" + receipt + ",\"unrelated\":{\"x\":1,\"x\":2}}"))
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(wire));
    }
    @Test void absentOnlyIsOptionalAndRequestResponseDirectionsCannotBeInterchanged() throws Exception {
        assertNull(ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions("{\"diagnosticAdmission\":{\"version\":1,\"installed\":null}}"));
        assertNull(ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts("{\"nested\":{\"diagnosticCompletions\":null},\"note\":\"diagnosticCompletions\"}"));
        for (String bad : List.of("null", "[]", "{}", "0")) {
            String wire = "{\"diagnosticCompletions\":" + bad + "}";
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(wire));
            assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(wire));
        }
        String request = "{\"diagnosticCompletions\":" + slice("requestSlice") + "}", response = "{\"diagnosticCompletions\":" + slice("responseSlice") + "}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(request));
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(response));
    }
    @Test void fullHeartbeatAndNestedOriginalSliceCapsAreIndependent() throws Exception {
        String request = slice("requestSlice"), receipt = slice("responseSlice");
        String padding = " ".repeat(9000);
        assertNotNull(ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts("{" + padding + "\"load\":1.25,\"diagnosticCompletions\":" + receipt + "}"));
        assertNotNull(ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions("{" + padding + "\"diagnosticCompletions\":" + request + "}"));
        String oversizedReceipt = "{\"diagnosticCompletions\":{ " + " ".repeat(8704) + receipt.substring(1) + "}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(oversizedReceipt));
        String oversizedRequest = "{\"diagnosticCompletions\":{ " + " ".repeat(16384) + request.substring(1) + "}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(oversizedRequest));
        String full = "{" + " ".repeat(65536) + "\"diagnosticCompletions\":" + receipt + "}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(full));
        String utf8 = "{\"note\":\"" + "é".repeat(33000) + "\"}";
        assertThrows(IllegalArgumentException.class, () -> ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(utf8));
    }
}
