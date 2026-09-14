package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlSessionCodecTest {
    static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.sessions.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    static JsonObject vector(String name) throws Exception {
        for (var item : fixtures().getAsJsonArray("vectors")) if (item.getAsJsonObject().get("name").getAsString().equals(name)) return item.getAsJsonObject();
        throw new IllegalArgumentException(name);
    }
    static long now() throws Exception { return fixtures().get("now").getAsLong(); }
    static ControlFrameCodec.VerificationKey key(String family) throws Exception {
        JsonObject fixture = fixtures(), key = fixture.getAsJsonObject("keys").getAsJsonObject(family);
        return new ControlFrameCodec.VerificationKey(family.equals("providerControl") ? ControlFrameCodec.KeyFamily.PROVIDER_CONTROL
                : ControlFrameCodec.KeyFamily.MACHINE, key.get("keyId").getAsString(), ProviderCrypto.publicKey(key.getAsJsonObject("publicKeyJwk")),
                fixture.get("keyValidFrom").getAsLong(), fixture.get("keyValidUntil").getAsLong());
    }
    static PrivateKey privateKey(String family) throws Exception {
        return ProviderCrypto.privateKey(fixtures().getAsJsonObject("keys").getAsJsonObject(family).get("privateKeyPkcs8").getAsString());
    }
    static ControlSessionCodec.Request request(String name) throws Exception {
        return ControlSessionCodec.decodeRequest(vector(name).get("envelope").toString());
    }
    static ControlSessionCodec.RequestContext context(ControlSessionCodec.Request request) throws Exception {
        return new ControlSessionCodec.RequestContext(request.action(), request.audience(), request.method(), request.encodedPathAndQuery(),
                request.instanceId(), request.generation(), now(), now() + 300000, 1000);
    }
    static ControlSessionCodec.ResponseContext responseContext(String requestName) throws Exception {
        return new ControlSessionCodec.ResponseContext(request(requestName), now(), now() + 300000, 1000);
    }
    static ControlSessionCodec.VerifiedResponse verified(String name) throws Exception {
        JsonObject vector = vector(name);
        return ControlSessionCodec.verifyResponse(vector.get("envelope").toString(), responseContext(vector.get("requestName").getAsString()), key("providerControl"));
    }
    static ControlWriterFence writer(String field) throws Exception { return ControlWriterFence.decode(fixtures().get(field).toString()); }

    @Test
    void independentlyGeneratedProofsMatchExactArraysAndRoundTrip() throws Exception {
        for (var item : fixtures().getAsJsonArray("vectors")) {
            var vector = item.getAsJsonObject();
            String kind = vector.get("kind").getAsString(), wire = vector.get("envelope").toString();
            if (kind.equals("http")) continue;
            if (kind.equals("request")) assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest("\uFEFF" + wire));
            var key = key(vector.get("keyFamily").getAsString());
            if (kind.equals("request")) {
                var request = ControlSessionCodec.decodeRequest(wire);
                assertEquals(vector.get("signingText").getAsString(), new String(ControlSessionCodec.signingBytes(request), StandardCharsets.UTF_8));
                assertEquals(vector.get("requestIntentDigest").getAsString(), ControlSessionCodec.requestIntentDigest(request));
                assertEquals(request, ControlSessionCodec.verifyRequest(wire, context(request), key));
                assertEquals(request, ControlSessionCodec.decodeRequest(ControlSessionCodec.encode(request)));
                var signed = ControlSessionCodec.sign(request, privateKey(vector.get("keyFamily").getAsString()));
                assertEquals(signed, ControlSessionCodec.verifyRequest(ControlSessionCodec.encode(signed), context(signed), key));
            } else {
                var response = ControlSessionCodec.decodeResponse(wire);
                var context = responseContext(vector.get("requestName").getAsString());
                assertEquals(vector.get("signingText").getAsString(), new String(ControlSessionCodec.signingBytes(response), StandardCharsets.UTF_8));
                assertEquals(response, ControlSessionCodec.verifyResponse(wire, context, key).response());
                assertEquals(response, ControlSessionCodec.decodeResponse(ControlSessionCodec.encode(response)));
                var signed = ControlSessionCodec.sign(response, privateKey("providerControl"));
                assertEquals(signed, ControlSessionCodec.verifyResponse(ControlSessionCodec.encode(signed), context, key).response());
            }
        }
    }

    @Test
    void duplicateUpgradesAreDistinctCandidatesAndOnlyExpectedWriterCanActivate() throws Exception {
        var prepared = verified("prepared-ws");
        var first = verified("challenge-1");
        var second = verified("challenge-2");
        var current = writer("currentWriter");
        var proposed1 = ControlSessionPayloadCodec.checkActivationAssociation(request("activate-1"), prepared, first, current, now());
        var proposed2 = ControlSessionPayloadCodec.checkActivationAssociation(request("activate-2"), prepared, second, current, now());
        assertEquals(proposed1.sessionId(), proposed2.sessionId());
        assertEquals(proposed1.sessionEpoch(), proposed2.sessionEpoch());
        assertNotEquals(proposed1.connectionId(), proposed2.connectionId());
        assertEquals(writer("activatedWriter"), proposed1);
        // This proves a predicate only. The provider still must perform this CAS atomically with its receipt.
        assertThrows(IllegalArgumentException.class, () -> ControlSessionPayloadCodec.checkActivationAssociation(request("activate-2"), prepared, second, proposed1, now()));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionPayloadCodec.checkActivationAssociation(request("activate-1"), prepared, second, current, now()));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionPayloadCodec.checkActivationAssociation(request("activate-1"), prepared, first, current, now() + 30000));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionPayloadCodec.checkActivationAssociation(request("activate-1"), prepared, null, current, now()));
    }

    @Test
    void httpsFallbackIsAnExplicitNewWriterAndHasNoSocketChallenge() throws Exception {
        var prepared = verified("prepared-https");
        var result = ControlSessionPayloadCodec.checkActivationAssociation(request("activate-https"), prepared, null, writer("activatedWriter"), now());
        assertEquals("https", result.transport());
        assertEquals(9, result.sessionEpoch());
        assertThrows(IllegalArgumentException.class, () -> ControlSessionPayloadCodec.checkActivationAssociation(request("activate-https"), prepared,
                verified("challenge-1"), writer("activatedWriter"), now()));
        assertEquals(result, ControlWriterFence.read(ControlSessionPayloadCodec.decodeResponse("activated", verified("activated-https").response().payloadBytes()).getAsJsonObject("writer")));
    }

    @Test
    void retryMetadataDoesNotChangeIntentButExactActionBodyAndExpectedFenceDo() throws Exception {
        JsonObject original = vector("activate-1").getAsJsonObject("envelope");
        var request = ControlSessionCodec.decodeRequest(original.toString());
        var retry = original.deepCopy();
        retry.addProperty("sentAt", now() + 1000);
        retry.addProperty("expiresAt", now() + 31000);
        retry.addProperty("encodedPathAndQuery", "/control/activate?protocol=1&retry=1");
        retry.getAsJsonObject("authentication").addProperty("keyId", key("machineCandidate").keyId());
        assertEquals(ControlSessionCodec.requestIntentDigest(request), ControlSessionCodec.requestIntentDigest(ControlSessionCodec.decodeRequest(retry.toString())));
        JsonObject payload = JsonParser.parseString(new String(request.payloadBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        payload.getAsJsonObject("expectedWriter").addProperty("sessionEpoch", 8);
        var changed = replacePayload(original, payload);
        assertNotEquals(ControlSessionCodec.requestIntentDigest(request), ControlSessionCodec.requestIntentDigest(ControlSessionCodec.decodeRequest(changed.toString())));
        var result = verified("activated-ws");
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyResponse(new String(result.originalWireBytes(), StandardCharsets.UTF_8),
                new ControlSessionCodec.ResponseContext(ControlSessionCodec.decodeRequest(changed.toString()), now(), now() + 300000, 1000), key("providerControl")));
        // Re-signing an idempotent activation response cannot extend the original session grant.
        var resultPayload = JsonParser.parseString(new String(result.response().payloadBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        resultPayload.addProperty("sessionExpiresAt", resultPayload.get("sessionExpiresAt").getAsLong() + 1);
        var wrongGrant = ControlSessionCodec.sign(ControlSessionCodec.decodeResponse(replacePayload(vector("activated-ws").getAsJsonObject("envelope"), resultPayload).toString()), privateKey("providerControl"));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyResponse(ControlSessionCodec.encode(wrongGrant), responseContext("activate-1"), key("providerControl")));
    }

    @Test
    void keyFamiliesExactRequestTargetAndResponseBodyContextCannotBeSubstituted() throws Exception {
        var request = request("upgrade-1");
        String wire = vector("upgrade-1").get("envelope").toString();
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(wire, context(request), key("providerControl")));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(wire,
                new ControlSessionCodec.RequestContext("upgrade", request.audience(), "GET", "/control/upgrade?protocol=2", request.instanceId(), 3,
                        now(), now() + 300000, 1000), key("machine")));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyResponse(vector("challenge-1").get("envelope").toString(), responseContext("upgrade-2"), key("providerControl")));
        var status = request("current-writer");
        // Candidate-key reconciliation requires it to be the actual trusted current key; no old-key bypass.
        assertEquals(status, ControlSessionCodec.verifyRequest(vector("current-writer").get("envelope").toString(), context(status), key("machineCandidate")));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(vector("current-writer").get("envelope").toString(), context(status), key("machine")));
        verified("rotated-current-writer");
        verified("unknown-intent-result");
    }

    @Test
    void originalPreparationDeadlineAndNegotiatedDurationAreBoundedIndependently() throws Exception {
        JsonObject original = vector("prepared-ws").getAsJsonObject("envelope");
        JsonObject payload = JsonParser.parseString(vector("prepared-ws").get("payloadUtf8").getAsString()).getAsJsonObject();
        payload.addProperty("expiresAt", now() + 60001);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(original, payload).toString()));
        payload.addProperty("expiresAt", now() + 59999);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(original, payload).toString()));
        var prepare = vector("prepare-ws").getAsJsonObject("envelope");
        var inner = JsonParser.parseString(vector("prepare-ws").get("payloadUtf8").getAsString()).getAsJsonObject();
        inner.addProperty("sessionDurationMillis", 86400001);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(replacePayload(prepare, inner).toString()));
        inner.addProperty("sessionDurationMillis", 86400000);
        ControlSessionCodec.decodeRequest(replacePayload(prepare, inner).toString());
        var request = request("prepare-ws");
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(ControlSessionCodec.encode(request),
                new ControlSessionCodec.RequestContext(request.action(), request.audience(), request.method(), request.encodedPathAndQuery(), request.instanceId(), 3,
                        now(), request.expiresAt() - 1, 1000), key("machine")));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(ControlSessionCodec.encode(request),
                new ControlSessionCodec.RequestContext(request.action(), request.audience(), request.method(), request.encodedPathAndQuery(), request.instanceId(), 3,
                        request.expiresAt(), now() + 300000, 1000), key("machine")));
    }

    @Test
    void legacyRevisionAndDisabledCurrentStatusAreExplicitWithoutRenewingGrant() throws Exception {
        var preparation = verified("prepared-legacy");
        var legacy = ControlWriterFence.read(ControlSessionPayloadCodec.decodeRequest("prepare", request("prepare-legacy").payloadBytes()).getAsJsonObject("expectedWriter"));
        assertEquals(4, legacy.machineKeyRevision());
        assertEquals(4, ControlSessionPayloadCodec.checkActivationAssociation(request("activate-legacy"), preparation, null, legacy, now()).machineKeyRevision());
        verified("activated-legacy"); verified("disabled-current-writer"); verified("legacy-status-result");
        var envelope = vector("legacy-status-result").getAsJsonObject("envelope");
        var inner = JsonParser.parseString(vector("legacy-status-result").get("payloadUtf8").getAsString()).getAsJsonObject();
        inner.addProperty("writerEnabled", true);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(envelope, inner).toString()));
        inner.addProperty("writerEnabled", false); inner.getAsJsonObject("writer").remove("machineKeyRevision");
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(envelope, inner).toString()));
        inner.getAsJsonObject("writer").addProperty("machineKeyRevision", 0);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(envelope, inner).toString()));
    }

    @Test
    void prepareIntentCannotSlideAcrossDeliveryRetriesOrShorterPreparedLifetime() throws Exception {
        var envelope = vector("prepare-ws").getAsJsonObject("envelope");
        var inner = JsonParser.parseString(vector("prepare-ws").get("payloadUtf8").getAsString()).getAsJsonObject();
        inner.remove("intentCreatedAt");
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(replacePayload(envelope, inner).toString()));
        inner.addProperty("intentCreatedAt", now()); inner.addProperty("intentExpiresAt", now() + 60001);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(replacePayload(envelope, inner).toString()));
        inner.addProperty("intentExpiresAt", now() + 29999);
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(replacePayload(envelope, inner).toString()));
        inner.addProperty("intentCreatedAt", now() + 1001); inner.addProperty("intentExpiresAt", now() + 50000);
        var future = ControlSessionCodec.sign(ControlSessionCodec.decodeRequest(replacePayload(envelope, inner).toString()), privateKey("machine"));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyRequest(ControlSessionCodec.encode(future), context(future), key("machine")));
        inner.addProperty("intentCreatedAt", now()); inner.addProperty("intentExpiresAt", now() + 50000);
        var shortened = ControlSessionCodec.sign(ControlSessionCodec.decodeRequest(replacePayload(envelope, inner).toString()), privateKey("machine"));
        var responseEnvelope = vector("prepared-ws").getAsJsonObject("envelope");
        responseEnvelope.addProperty("requestIntentDigest", ControlSessionCodec.requestIntentDigest(shortened));
        var result = JsonParser.parseString(vector("prepared-ws").get("payloadUtf8").getAsString()).getAsJsonObject();
        result.addProperty("intentDigest", ControlSessionCodec.requestIntentDigest(shortened));
        var response = ControlSessionCodec.sign(ControlSessionCodec.decodeResponse(replacePayload(responseEnvelope, result).toString()), privateKey("providerControl"));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.verifyResponse(ControlSessionCodec.encode(response),
                new ControlSessionCodec.ResponseContext(shortened, now(), now() + 300000, 1000), key("providerControl")));
        result.addProperty("expiresAt", now() + 40000); responseEnvelope.addProperty("expiresAt", now() + 40000);
        var bounded = ControlSessionCodec.sign(ControlSessionCodec.decodeResponse(replacePayload(responseEnvelope, result).toString()), privateKey("providerControl"));
        ControlSessionCodec.verifyResponse(ControlSessionCodec.encode(bounded), new ControlSessionCodec.ResponseContext(shortened, now(), now() + 300000, 1000), key("providerControl"));
    }

    @Test
    void closedPayloadsRejectDuplicateLossySecretAndWrongVariantFields() throws Exception {
        var original = vector("prepare-ws").getAsJsonObject("envelope");
        String inner = vector("prepare-ws").get("payloadUtf8").getAsString();
        for (String bad : List.of(inner.replace("\"sessionDurationMillis\":21600000", "\"sessionDurationMillis\":21600000e0"),
                inner.replace("\"sessionEpoch\":7", "\"sessionEpoch\":7,\"\\u0073essionEpoch\":7"),
                inner.replace("\"sessionEpoch\":7", "\"sessionEpoch\":7.000000000000000001"),
                inner.replace("\"transport\":\"websocket\"", "\"transport\":\"https\""),
                inner.replace("\"machineKeyRevision\":2", "\"machineKeyRevision\":0"))) {
            var changed = replacePayload(original, bad);
            assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(changed.toString()));
        }
        var result = vector("rotated-current-writer").getAsJsonObject("envelope");
        var payload = JsonParser.parseString(vector("rotated-current-writer").get("payloadUtf8").getAsString()).getAsJsonObject();
        payload.addProperty("ticketKey", "never-a-status-field");
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeResponse(replacePayload(result, payload).toString()));
        assertThrows(IllegalArgumentException.class, () -> ControlSessionCodec.decodeRequest(original.toString().replace("\"version\":1", "\"version\":9007199254740991")));
    }

    static JsonObject replacePayload(JsonObject original, JsonObject payload) { return replacePayload(original, payload.toString()); }
    static JsonObject replacePayload(JsonObject original, String payload) {
        JsonObject changed = original.deepCopy();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        changed.addProperty("payload", ProviderCrypto.base64(bytes));
        changed.addProperty("payloadSha256", ControlFrameCodec.payloadDigest(bytes));
        return changed;
    }

    /** Independent Node verification reads these fresh Java signatures via --java-output. */
    public static void main(String[] args) throws Exception {
        JsonArray output = new JsonArray();
        for (var item : fixtures().getAsJsonArray("vectors")) {
            var vector = item.getAsJsonObject();
            String kind = vector.get("kind").getAsString(), wire = vector.get("envelope").toString();
            PrivateKey key = privateKey(vector.get("keyFamily").getAsString());
            byte[] text;
            String signature;
            if (kind.equals("request")) {
                var value = ControlSessionCodec.sign(ControlSessionCodec.decodeRequest(wire), key);
                text = ControlSessionCodec.signingBytes(value); signature = value.authentication().signature();
            } else if (kind.equals("response")) {
                var value = ControlSessionCodec.sign(ControlSessionCodec.decodeResponse(wire), key);
                text = ControlSessionCodec.signingBytes(value); signature = value.authentication().signature();
            } else {
                var value = ControlHttpCodec.sign(ControlHttpCodec.decode(wire), key);
                text = ControlHttpCodec.signingBytes(value); signature = value.authentication().signature();
            }
            JsonObject result = new JsonObject();
            result.addProperty("name", vector.get("name").getAsString());
            result.addProperty("signingText", new String(text, StandardCharsets.UTF_8));
            result.addProperty("signature", signature);
            output.add(result);
        }
        System.out.println(output);
    }
}
