package org.cloudburstmc.netty.signaling.control;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ControlAuthorityCodecTest {
    static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.authority.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static JsonObject vector(String name) throws Exception {
        for (var item : fixtures().getAsJsonArray("vectors")) if (item.getAsJsonObject().get("name").getAsString().equals(name)) return item.getAsJsonObject();
        throw new IllegalArgumentException(name);
    }
    static long now() throws Exception { return fixtures().get("now").getAsLong(); }
    static long sessionExpiry() throws Exception { return fixtures().get("sessionExpiresAt").getAsLong(); }
    static PrivateKey privateKey(String family) throws Exception {
        return ProviderCrypto.privateKey(fixtures().getAsJsonObject("keys").getAsJsonObject(family).get("privateKeyPkcs8").getAsString());
    }
    static ControlFrameCodec.VerificationKey key(String family) throws Exception {
        var f = fixtures(); var k = f.getAsJsonObject("keys").getAsJsonObject(family);
        return new ControlFrameCodec.VerificationKey(family.equals("providerControl") ? ControlFrameCodec.KeyFamily.PROVIDER_CONTROL : ControlFrameCodec.KeyFamily.MACHINE,
                k.get("keyId").getAsString(), ProviderCrypto.publicKey(k.getAsJsonObject("publicKeyJwk")), f.get("keyValidFrom").getAsLong(), f.get("keyValidUntil").getAsLong());
    }
    static ControlAuthorityCodec.Request request(String name) throws Exception { return ControlAuthorityCodec.decodeRequest(vector(name).get("envelope").toString()); }
    static ControlAuthorityCodec.Response response(String name) throws Exception { return ControlAuthorityCodec.decodeResponse(vector(name).get("envelope").toString()); }
    static ControlAuthorityCodec.RequestContext context(ControlAuthorityCodec.Request r) throws Exception {
        return new ControlAuthorityCodec.RequestContext(r.audience(), r.instanceId(), r.generation(), r.method(), r.encodedPathAndQuery(), r.writer(), r.capabilities(), now(), sessionExpiry(), 1000);
    }
    static ControlAuthorityCodec.ResponseContext context(ControlAuthorityCodec.Request r, ControlAuthorityCodec.Floor floor) throws Exception {
        return new ControlAuthorityCodec.ResponseContext(r, now(), sessionExpiry(), 1000, floor);
    }
    static String changed(String wire, Map<String, ?> values) {
        var o = JsonParser.parseString(wire).getAsJsonObject(); var gson = new Gson();
        values.forEach((name, value) -> o.add(name, value instanceof com.google.gson.JsonElement element ? element.deepCopy() : gson.toJsonTree(value))); return o.toString();
    }
    static String signedResponse(ControlAuthorityCodec.Response v, Map<String, ?> changes) throws Exception {
        return ControlAuthorityCodec.encode(ControlAuthorityCodec.sign(ControlAuthorityCodec.decodeResponse(changed(ControlAuthorityCodec.encode(v), changes)), privateKey("providerControl")));
    }
    @Test
    void independentVectorsAndReverseJavaSignatures() throws Exception {
        JsonArray output = new JsonArray();
        for (var item : fixtures().getAsJsonArray("vectors")) {
            var v = item.getAsJsonObject(); var wire = v.get("envelope").toString(); var family = v.get("keyFamily").getAsString();
            String signingText, signature;
            if (v.getAsJsonObject("envelope").get("kind").getAsString().equals("authority-request")) {
                var r = ControlAuthorityCodec.decodeRequest(wire);
                signingText = new String(ControlAuthorityCodec.signingBytes(r), StandardCharsets.UTF_8);
                assertEquals(v.get("requestDigest").getAsString(), ControlAuthorityCodec.requestDigest(r));
                assertEquals(r, ControlAuthorityCodec.verifyRequest(wire, context(r), key(family)));
                var produced = ControlAuthorityCodec.sign(r, privateKey(family)); signature = produced.authentication().signature();
                assertEquals(produced, ControlAuthorityCodec.decodeRequest(ControlAuthorityCodec.encode(produced)));
            } else {
                var r = ControlAuthorityCodec.decodeResponse(wire);
                signingText = new String(ControlAuthorityCodec.signingBytes(r), StandardCharsets.UTF_8);
                var result = ControlAuthorityCodec.verifyResponse(wire, context(request(v.get("requestName").getAsString()), null), key(family));
                assertEquals(r, result.response()); result.requireFreshDelivery(now(), null);
                var produced = ControlAuthorityCodec.sign(r, privateKey(family)); signature = produced.authentication().signature();
                assertEquals(produced, ControlAuthorityCodec.decodeResponse(ControlAuthorityCodec.encode(produced)));
            }
            assertEquals(v.get("signingText").getAsString(), signingText);
            JsonObject result = new JsonObject(); result.addProperty("name", v.get("name").getAsString());
            result.addProperty("signingText", signingText); result.addProperty("signature", signature); output.add(result);
        }
        Files.createDirectories(Path.of("build")); Files.writeString(Path.of("build/control-authority-java.json"), output.toString());
    }
    @Test
    void exactMachineIdentityRouteAndCapabilityBinding() throws Exception {
        var r = request("ws-request"); var wire = ControlAuthorityCodec.encode(r); var k = key("machine");
        for (var changes : List.of(Map.of("method", "GET"), Map.of("encodedPathAndQuery", "/different"), Map.of("generation", 4),
                Map.of("audience", "https://other.example"), Map.of("instanceId", "different-host"), Map.of("capabilities", List.of("addressed", "request-response")))) {
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyRequest(changed(wire, changes), context(r), k));
        }
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyRequest(wire, context(r), key("providerControl")));
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyRequest(wire, context(r), key("machineCandidate")));
        var earlier = new ControlFrameCodec.VerificationKey(k.family(), k.keyId(), k.key(), k.validFrom(), now() + 60000);
        var context = new ControlAuthorityCodec.RequestContext(r.audience(), r.instanceId(), r.generation(), r.method(), r.encodedPathAndQuery(), r.writer(), r.capabilities(), now(), now() + 60000, 1000);
        assertEquals(r, ControlAuthorityCodec.verifyRequest(wire, context, earlier));
        var tooEarly = new ControlFrameCodec.VerificationKey(k.family(), k.keyId(), k.key(), k.validFrom(), now() + 29000);
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyRequest(wire, context, tooEarly));
    }
    @Test
    void requestDigestAndBothLifetimesAreBound() throws Exception {
        var r = request("ws-request"); var v = response("ws-response"); var k = key("providerControl");
        for (var patch : List.of(Map.of("requestId", "other_request_0001"), Map.of("authorityNotAfter", now() + 50000),
                Map.of("encodedPathAndQuery", "/authority?version=2"), Map.of("generation", 4))) {
            var changed = ControlAuthorityCodec.sign(ControlAuthorityCodec.decodeRequest(changed(ControlAuthorityCodec.encode(r), patch)), privateKey("machine"));
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(changed, null), k));
        }
        var retiring = new ControlFrameCodec.VerificationKey(k.family(), k.keyId(), k.key(), k.validFrom(), now() + 179999);
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(r, null), retiring));
        var proof = ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(r, null), k);
        proof.requireFreshDelivery(now() + 29999, null);
        assertThrows(IllegalArgumentException.class, () -> proof.requireFreshDelivery(now() + 30000, null));
        proof.requireUnexpired(now() + 179999);
        assertThrows(IllegalArgumentException.class, () -> proof.requireUnexpired(now() + 180000));
    }
    @Test
    void sourceFloorRejectsRollbackAndSameRevisionSubjectChanges() throws Exception {
        var r = request("ws-request"); var v = response("ws-response"); var k = key("providerControl");
        var current = ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(r, null), k); var floor = current.floor();
        for (var patch : List.of(Map.of("sourceId", "control:p18"), Map.of("sourceRevision", 41), Map.of("sourceWatermark", 1000),
                Map.of("sourceCheckedAt", now() - 1001, "sourceExpiresAt", now() + 298999), Map.of("permissions", List.of()), Map.of("subjectExpiresAt", v.subjectExpiresAt() + 1))) {
            var wire = signedResponse(v, patch);
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(wire, context(r, floor), k));
        }
        var changedRequest = ControlAuthorityCodec.sign(ControlAuthorityCodec.decodeRequest(changed(ControlAuthorityCodec.encode(r), Map.of("generation", 4))), privateKey("machine"));
        var changedResponse = signedResponse(v, Map.of("generation", 4, "requestDigest", ControlAuthorityCodec.requestDigest(changedRequest)));
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(changedResponse, context(changedRequest, floor), k));
        var newer = signedResponse(v, Map.of("generation", 4, "requestDigest", ControlAuthorityCodec.requestDigest(changedRequest), "sourceRevision", 43, "sourceWatermark", 1002));
        assertEquals(4, ControlAuthorityCodec.verifyResponse(newer, context(changedRequest, floor), k).response().generation());
        var future = new ControlAuthorityCodec.Floor(floor.audience(), floor.instanceId(), floor.generation(),
                new ControlAuthorityCodec.Source(floor.source().sourceId(), 43, 1002, floor.source().sourceCheckedAt(), floor.source().sourceExpiresAt()),
                floor.writer(), floor.capabilities(), floor.subjectExpiresAt(), floor.permissions(), floor.state());
        assertThrows(IllegalArgumentException.class, () -> current.requireFreshDelivery(now(), future));
    }
    @Test
    void shorterEffectiveGrantDoesNotPinRawSourceDeadlineAndFreshnessDoesNotRestorePermissions() throws Exception {
        var r = request("ws-request"); var v = response("ws-response"); var k = key("providerControl");
        var shortGrant = ControlAuthorityCodec.verifyResponse(signedResponse(v, Map.of("authorityExpiresAt", now() + 31000)), context(r, null), k);
        var full = ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(r, shortGrant.floor()), k);
        assertEquals(v.authorityExpiresAt(), full.response().authorityExpiresAt());
        var shortened = ControlAuthorityCodec.verifyResponse(signedResponse(v, Map.of("sourceExpiresAt", now() + 150000, "authorityExpiresAt", now() + 150000)), context(r, full.floor()), k);
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(ControlAuthorityCodec.encode(v), context(r, shortened.floor()), k));
        var renewed = signedResponse(v, Map.of("sourceCheckedAt", now(), "sourceExpiresAt", now() + 300000));
        assertEquals(now() + 300000, ControlAuthorityCodec.verifyResponse(renewed, context(r, shortened.floor()), k).response().source().sourceExpiresAt());
        var assisted = response("assisted-response"); var ar = request("assisted-request");
        var reduced = signedResponse(assisted, Map.of("sourceRevision", 43, "sourceWatermark", 1002, "permissions", List.of("control.status")));
        var reducedProof = ControlAuthorityCodec.verifyResponse(reduced, context(ar, null), k);
        var restored = signedResponse(assisted, Map.of("sourceRevision", 43, "sourceWatermark", 1002, "sourceCheckedAt", now(), "sourceExpiresAt", now() + 300000));
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(restored, context(ar, reducedProof.floor()), k));
    }
    @Test
    void stateIsSignedAndFlooredWhileNullBasisStillAllowsControlAuthority() throws Exception {
        var r = request("ws-request"); var v = response("ws-response"); var k = key("providerControl");
        var wire = ControlAuthorityCodec.encode(v);
        var current = ControlAuthorityCodec.verifyResponse(wire, context(r, null), k);
        assertNull(current.response().state().appliedBasisSha256());
        assertEquals(v.state(), current.floor().state());
        for (String state : List.of(
                "{\"desiredRevision\":13,\"desiredState\":\"serving\",\"appliedBasisSha256\":null}",
                "{\"desiredRevision\":12,\"desiredState\":\"closed\",\"appliedBasisSha256\":null}",
                "{\"desiredRevision\":12,\"desiredState\":\"serving\",\"appliedBasisSha256\":\"" + "A".repeat(43) + "\"}")) {
            var summary = JsonParser.parseString(state);
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(changed(wire, Map.of("state", summary)), context(r, null), k));
            var sameRevision = signedResponse(v, Map.of("state", summary, "sourceCheckedAt", now(), "sourceExpiresAt", now() + 300000));
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(sameRevision, context(r, current.floor()), k));
            var advanced = ControlAuthorityCodec.verifyResponse(signedResponse(v, Map.of("state", summary, "sourceRevision", 43, "sourceWatermark", 1002)), context(r, current.floor()), k);
            assertEquals(ControlStateCodec.readSummary(summary.getAsJsonObject()), advanced.response().state());
            assertEquals(v.authorityExpiresAt(), advanced.response().authorityExpiresAt());
        }
        var backwards = JsonParser.parseString("{\"desiredRevision\":11,\"desiredState\":\"serving\",\"appliedBasisSha256\":null}");
        var regression = signedResponse(v, Map.of("state", backwards, "sourceRevision", 43, "sourceWatermark", 1002));
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.verifyResponse(regression, context(r, current.floor()), k));
    }
    @Test
    void nestedStateShapeAndCanonicalIntegersAreRequired() throws Exception {
        var wire = ControlAuthorityCodec.encode(response("ws-response"));
        for (String revision : List.of("12.0", "12e0", "1.5", "-1", "9223372036854775808", "9007199254740992")) {
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(wire.replace("\"desiredRevision\":12", "\"desiredRevision\":" + revision)));
        }
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(wire.replace("\"desiredRevision\":12", "\"desiredRevision\":12,\"desiredRevision\":12")));
        for (String state : List.of("null", "{}", "{\"desiredRevision\":12,\"desiredState\":\"serving\",\"appliedBasisSha256\":null,\"extra\":true}",
                "{\"desiredRevision\":12,\"desiredState\":\"unknown\",\"appliedBasisSha256\":null}",
                "{\"desiredRevision\":12,\"desiredState\":\"serving\",\"appliedBasisSha256\":\"" + "B".repeat(43) + "\"}")) {
            assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(changed(wire, Map.of("state", JsonParser.parseString(state)))));
        }
        var missing = JsonParser.parseString(wire).getAsJsonObject(); missing.remove("state");
        assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(missing.toString()));
    }
    @Test
    void strictBoundsAndImmutableCollections() throws Exception {
        String wire = ControlAuthorityCodec.encode(response("assisted-response"));
        for (var patch : List.of(Map.of("capabilities", List.of("addressed", "request-response")), Map.of("sourceExpiresAt", now() + 299001),
                Map.of("subjectExpiresAt", now() + 179999), Map.of("permissions", List.of("control.status", "control.assisted")),
                Map.of("version", 4294967297L), Map.of("extra", true))) assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(changed(wire, patch)));
        for (String malformed : List.of(wire.replace("\"version\":1", "\"version\":1,\"version\":1"), wire.replace("\"version\":1", "\"version\":1.0"),
                "\uFEFF" + wire, " ".repeat(8193))) assertThrows(IllegalArgumentException.class, () -> ControlAuthorityCodec.decodeResponse(malformed));
        var r = request("ws-request"); var caps = new ArrayList<>(r.capabilities());
        var copied = new ControlAuthorityCodec.Request(r.version(), r.kind(), r.requestId(), r.audience(), r.instanceId(), r.generation(), r.writer(), caps,
                r.sentAt(), r.expiresAt(), r.method(), r.encodedPathAndQuery(), r.authorityNotAfter(), r.authentication());
        caps.add("addressed"); assertEquals(List.of("request-response"), copied.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> copied.capabilities().add("addressed"));
    }
}
