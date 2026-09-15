package org.cloudburstmc.netty.signaling.control;

import com.google.gson.*;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ControlCancellationCodecTest {
    private static JsonObject payload(String vector) throws Exception {
        var envelope = ControlSessionCodecTest.vector(vector).getAsJsonObject("envelope");
        return JsonParser.parseString(new String(java.util.Base64.getUrlDecoder().decode(envelope.get("payload").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static String replacePayload(String vector, JsonObject body) throws Exception {
        var envelope = ControlSessionCodecTest.vector(vector).getAsJsonObject("envelope").deepCopy();
        byte[] bytes = new GsonBuilder().serializeNulls().create().toJson(body).getBytes(StandardCharsets.UTF_8);
        envelope.addProperty("payload", ProviderCrypto.base64(bytes)); envelope.addProperty("payloadSha256", ControlFrameCodec.payloadDigest(bytes)); return envelope.toString();
    }
    @Test void cancellationIsOnlyOriginalHeartbeatUnderSelectedNonlegacyWriter() throws Exception {
        for (String field : List.of("operation", "generation", "audience", "instanceId", "keyId", "transport", "reason")) {
            var body = payload("cancel-intent");
            switch (field) {
                case "operation" -> body.getAsJsonObject("intent").addProperty(field, "outcomes");
                case "generation" -> body.getAsJsonObject("intent").addProperty(field, 9);
                case "audience" -> body.getAsJsonObject("intent").addProperty(field, "https://other.example");
                case "instanceId" -> body.getAsJsonObject("intent").addProperty(field, "other_host");
                case "keyId" -> body.getAsJsonObject("expectedWriter").addProperty(field, "other_key");
                case "transport" -> body.add("expectedWriter", new ControlWriterFence("legacy-http", 0, "", "", "machine_test_01", 1).object());
                default -> body.addProperty(field, "arbitrary-reason");
            }
            String wire = replacePayload("cancel-intent", body);
            assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(wire), field);
        }
    }
    @Test void cancellationResponseCannotManufactureNonterminalOrUnrelatedReceipt() throws Exception {
        for (String disposition : List.of("unknown", "expired")) {
            var body = payload("cancel-intent-cancelled"); body.getAsJsonObject("receipt").addProperty("disposition", disposition);
            String wire = replacePayload("cancel-intent-cancelled", body);
            assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(wire));
        }
        var body = payload("cancel-intent-cancelled"); body.getAsJsonObject("receipt").addProperty("sequence", 999);
        var signed = ControlSessionCodec.sign(ControlSessionCodec.decodeResponse(replacePayload("cancel-intent-cancelled", body)), ControlSessionCodecTest.privateKey("providerControl"));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyResponse(ControlSessionCodec.encode(signed), ControlSessionCodecTest.responseContext("cancel-intent"), ControlSessionCodecTest.key("providerControl")));
    }
    @Test void cancelledReceiptCodeAndOperationAreClosed() throws Exception {
        for (String field : List.of("operation", "code")) {
            var receipt = payload("cancel-intent-cancelled").getAsJsonObject("receipt"); receipt.addProperty(field, field.equals("operation") ? "deregister" : "not_committed");
            assertThrows(IllegalArgumentException.class, () -> ControlLifecycleCodec.decodeReceipt(receipt.toString()));
        }
        assertEquals("committed", ControlSessionPayloadCodec.decodeResponse("cancel-intent", ControlSessionCodecTest.verified("cancel-intent-committed").response().payloadBytes()).getAsJsonObject("receipt").get("disposition").getAsString());
    }
}
