package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ControlStateCodecTest {
    @Test void genericObjectSummaryRejectsNumericCoercionAndOwnsItsInput() {
        for (String number : List.of("1.9", "1e0", "18446744073709551616", "9007199254740992", "-0", "\"1\"")) {
            var object = JsonParser.parseString("{\"desiredRevision\":" + number
                    + ",\"desiredState\":\"serving\",\"appliedBasisSha256\":null}").getAsJsonObject();
            assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.readSummary(object), number);
        }
        var object = JsonParser.parseString("{\"desiredRevision\":9007199254740991,\"desiredState\":\"serving\",\"appliedBasisSha256\":null}").getAsJsonObject();
        var summary = ControlStateCodec.readSummary(object);
        object.addProperty("desiredRevision", 0);
        assertEquals(9007199254740991L, summary.desiredRevision());
        object.addProperty("desiredState", "x".repeat(2049));
        assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.readSummary(object));
    }
    static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.state.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    @Test void independentlyGeneratedVectorsMatchAcrossBothRuntimes() throws Exception {
        var fixture = fixtures();
        for (var item : fixture.getAsJsonArray("policies")) {
            var vector = item.getAsJsonObject(); var policy = ControlStateCodec.decodeTicketPolicy(vector.get("policy").toString());
            assertEquals(vector.get("digest").getAsString(), ControlStateCodec.ticketPolicyDigest(policy));
            assertEquals(vector.get("policy"), JsonParser.parseString(ControlStateCodec.encodeTicketPolicy(policy)));
        }
        for (var item : fixture.getAsJsonArray("bases")) {
            var vector = item.getAsJsonObject(); var basis = ControlStateCodec.decodeAppliedBasis(vector.get("basis").toString());
            assertEquals(vector.get("digest").getAsString(), ControlStateCodec.appliedBasisDigest(basis));
            assertEquals(vector.get("basis"), JsonParser.parseString(ControlStateCodec.encodeAppliedBasis(basis)));
        }
        for (var item : fixture.getAsJsonArray("acknowledgements")) {
            var ack = ControlStateCodec.decodeAcknowledgement(item.toString());
            assertEquals(item, JsonParser.parseString(ControlStateCodec.encodeAcknowledgement(ack)));
            assertTrue(ControlStateCodec.matches(ack.state(), ack));
            assertFalse(ControlStateCodec.matches(new ControlStateCodec.Summary(ack.state().desiredRevision(), ack.state().desiredState(), null), ack));
        }
    }
    @Test void policyOwnsEpochsAndBindsTheirExactAbsoluteCutoffs() {
        var epochs = new ArrayList<>(List.of(new ControlStateCodec.TicketEpoch("A001", 0, 300000L), new ControlStateCodec.TicketEpoch("B002", 1000, null)));
        var policy = new ControlStateCodec.TicketPolicy("B002", epochs); String digest = ControlStateCodec.ticketPolicyDigest(policy);
        epochs.set(0, new ControlStateCodec.TicketEpoch("A001", 0, 300001L));
        assertEquals(digest, ControlStateCodec.ticketPolicyDigest(policy));
        assertNotEquals(digest, ControlStateCodec.ticketPolicyDigest(new ControlStateCodec.TicketPolicy("B002", epochs)));
        assertThrows(UnsupportedOperationException.class, () -> policy.epochs().clear());
        assertThrows(IllegalArgumentException.class, () -> new ControlStateCodec.TicketPolicy("C003", epochs));
        assertThrows(IllegalArgumentException.class, () -> new ControlStateCodec.TicketPolicy("A001", List.of(epochs.get(0), epochs.get(0))));
        assertThrows(IllegalArgumentException.class, () -> new ControlStateCodec.TicketPolicy("B002", List.of(epochs.get(1), epochs.get(0))));
        assertThrows(IllegalArgumentException.class, () -> new ControlStateCodec.TicketEpoch("A001", 5, 5L));
    }
    @Test void closedShapesIntegersAndStateRulesFailClosed() throws Exception {
        var basis = fixtures().getAsJsonArray("bases").get(0).getAsJsonObject().getAsJsonObject("basis");
        for (String field : List.of("generation", "desiredRevision", "state", "admission", "hostProfileRevision", "ticketPolicySha256")) {
            var changed = basis.deepCopy(); changed.add(field, com.google.gson.JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.decodeAppliedBasis(changed.toString()), field);
        }
        var changed = basis.deepCopy(); changed.addProperty("state", "draining");
        assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.decodeAppliedBasis(changed.toString()));
        String wire = basis.toString();
        for (String invalid : List.of(wire.replace("\"generation\":7", "\"generation\":7,\"generation\":7"),
                wire.replace("\"generation\":7", "\"generation\":7.0"), " ".repeat(2049) + wire))
            assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.decodeAppliedBasis(invalid));
        var ack = fixtures().getAsJsonArray("acknowledgements").get(0).getAsJsonObject();
        ack.add("appliedBasisSha256", com.google.gson.JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> ControlStateCodec.decodeAcknowledgement(ack.toString()));
    }
    @Test void everyApplicationIdentityComponentAffectsTheDigest() throws Exception {
        var basis = ControlStateCodec.decodeAppliedBasis(fixtures().getAsJsonArray("bases").get(0).getAsJsonObject().get("basis").toString());
        String digest = ControlStateCodec.appliedBasisDigest(basis);
        for (var changed : List.of(new ControlStateCodec.AppliedBasis(8,5,"serving","enabled",basis.hostProfileRevision(),basis.ticketPolicySha256()),
                new ControlStateCodec.AppliedBasis(7,6,"serving","enabled",basis.hostProfileRevision(),basis.ticketPolicySha256()),
                new ControlStateCodec.AppliedBasis(7,5,"serving","enabled","hpr_another",basis.ticketPolicySha256()),
                new ControlStateCodec.AppliedBasis(7,5,"draining","disabled",null,null)))
            assertNotEquals(digest, ControlStateCodec.appliedBasisDigest(changed));
    }
}
