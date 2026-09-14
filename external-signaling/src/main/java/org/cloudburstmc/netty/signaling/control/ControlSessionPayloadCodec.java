package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Closed draft bootstrap payload schemas. Decoding is not authentication or a state transition. */
public final class ControlSessionPayloadCodec {
    public static final long MAX_PREPARATION_MILLIS = 60000;
    public static final long MAX_SESSION_DURATION_MILLIS = 86400000;
    private ControlSessionPayloadCodec() { }

    public static JsonObject decodeRequest(String action, byte[] originalBytes) {
        JsonObject value = parse(originalBytes);
        switch (action) {
            case "prepare" -> {
                ControlJson.fields(value, "transport", "capabilities", "clientNonce", "expectedWriter", "sessionDurationMillis", "intentCreatedAt", "intentExpiresAt");
                selected(value);
                nonce(value);
                fence(value, "expectedWriter");
                duration(value);
                long created = timestamp(value, "intentCreatedAt"), expires = timestamp(value, "intentExpiresAt");
                if (expires <= created || expires - created > MAX_PREPARATION_MILLIS) throw ControlJson.invalid("prepare intent lifetime");
            }
            case "upgrade" -> {
                ControlJson.fields(value, "preparedProof");
                proof(value, "preparedProof");
            }
            case "activate" -> {
                ControlJson.fields(value, "expectedWriter", "preparedProof", "connectionProof");
                fence(value, "expectedWriter");
                proof(value, "preparedProof");
                if (!value.get("connectionProof").isJsonNull()) proof(value, "connectionProof");
            }
            case "status" -> {
                String query = ControlJson.string(value, "query");
                if (query.equals("current-writer")) ControlJson.fields(value, "query");
                else if (query.equals("intent-receipt")) {
                    ControlJson.fields(value, "query", "intentDigest");
                    ControlJson.digest(ControlJson.string(value, "intentDigest"));
                } else throw ControlJson.invalid("status query");
            }
            default -> throw ControlJson.invalid("session action");
        }
        return value;
    }

    public static JsonObject decodeResponse(String kind, byte[] originalBytes) {
        JsonObject value = parse(originalBytes);
        switch (kind) {
            case "prepared" -> {
                ControlJson.fields(value, "pendingSessionId", "transport", "capabilities", "clientNonce", "connectionId",
                        "expectedWriter", "intentDigest", "preparedAt", "expiresAt", "sessionDurationMillis");
                pending(value);
                selected(value);
                nonce(value);
                fence(value, "expectedWriter");
                duration(value);
                ControlJson.digest(ControlJson.string(value, "intentDigest"));
                long start = timestamp(value, "preparedAt"), end = timestamp(value, "expiresAt");
                if (end <= start || end - start > MAX_PREPARATION_MILLIS) throw ControlJson.invalid("preparation lifetime");
                if (ControlJson.string(value, "transport").equals("websocket")) {
                    if (!value.get("connectionId").isJsonNull()) throw ControlJson.invalid("unbound prepared socket");
                } else ControlJson.opaque(ControlJson.string(value, "connectionId"));
            }
            case "connection-challenge" -> {
                ControlJson.fields(value, "pendingSessionId", "transport", "capabilities", "clientNonce", "connectionId",
                        "preparedProofSha256", "expiresAt", "sessionDurationMillis");
                pending(value);
                if (!ControlJson.string(value, "transport").equals("websocket")) throw ControlJson.invalid("challenge transport");
                selected(value);
                nonce(value);
                duration(value);
                ControlJson.opaque(ControlJson.string(value, "connectionId"));
                ControlJson.digest(ControlJson.string(value, "preparedProofSha256"));
                timestamp(value, "expiresAt");
            }
            case "activated" -> {
                ControlJson.fields(value, "intentDigest", "writer", "capabilities", "activatedAt", "sessionExpiresAt",
                        "authoritySourceCheckedAt", "authorityExpiresAt");
                ControlJson.digest(ControlJson.string(value, "intentDigest"));
                current(value, false);
            }
            case "status" -> {
                String query = ControlJson.string(value, "query");
                if (query.equals("current-writer")) {
                    ControlJson.fields(value, "query", "writer", "writerEnabled", "capabilities", "activatedAt", "sessionExpiresAt",
                            "authoritySourceCheckedAt", "authorityExpiresAt");
                    if (!value.get("writerEnabled").isJsonPrimitive() || !value.getAsJsonPrimitive("writerEnabled").isBoolean()) throw ControlJson.invalid("writer policy flag");
                    if (fence(value, "writer").transport().equals("legacy-http") && value.get("writerEnabled").getAsBoolean()) throw ControlJson.invalid("legacy controlled writer enabled");
                    current(value, true);
                } else if (query.equals("intent-receipt")) {
                    ControlJson.fields(value, "query", "intentDigest", "receipt");
                    String digest = ControlJson.string(value, "intentDigest");
                    ControlJson.digest(digest);
                    if (!value.get("receipt").isJsonNull()) {
                        var receipt = ControlLifecycleCodec.decodeReceipt(ControlJson.object(value, "receipt").toString());
                        if (!receipt.intentDigest().equals(digest)) throw ControlJson.invalid("status receipt digest");
                    }
                } else throw ControlJson.invalid("status result");
            }
            default -> throw ControlJson.invalid("session response kind");
        }
        return value;
    }

    /** Compare already verified proof references and current authority. The caller must CAS this fence atomically. */
    public static ControlWriterFence checkActivationAssociation(ControlSessionCodec.Request activation,
            ControlSessionCodec.VerifiedResponse preparation, ControlSessionCodec.VerifiedResponse challenge,
            ControlWriterFence currentWriter, long now) {
        if (!activation.action().equals("activate") || !preparation.response().kind().equals("prepared")) throw ControlJson.invalid("activation proof kinds");
        preparation.requireUnexpired(now);
        if (challenge != null) challenge.requireUnexpired(now);
        JsonObject request = decodeRequest("activate", activation.payloadBytes());
        JsonObject prepared = decodeResponse("prepared", preparation.response().payloadBytes());
        ControlWriterFence expected = fence(request, "expectedWriter");
        if (!expected.equals(currentWriter) || !expected.equals(fence(prepared, "expectedWriter"))
                || !activation.authentication().keyId().equals(currentWriter.keyId())) throw ControlJson.invalid("activation expected writer");
        sameSubject(activation, preparation.response());
        if (!ControlJson.string(request, "preparedProof").equals(preparation.encodedOriginalWire())) throw ControlJson.invalid("prepared original proof reference");
        if (now >= timestamp(prepared, "expiresAt")) throw ControlJson.invalid("expired preparation");
        String transport = ControlJson.string(prepared, "transport");
        String connection;
        if (transport.equals("websocket")) {
            if (challenge == null || !challenge.response().kind().equals("connection-challenge")) throw ControlJson.invalid("missing socket challenge");
            sameSubject(activation, challenge.response());
            if (!ControlJson.string(request, "connectionProof").equals(challenge.encodedOriginalWire())) throw ControlJson.invalid("challenge original proof reference");
            JsonObject candidate = decodeResponse("connection-challenge", challenge.response().payloadBytes());
            for (String field : List.of("pendingSessionId", "transport", "capabilities", "clientNonce", "expiresAt", "sessionDurationMillis")) {
                if (!prepared.get(field).equals(candidate.get(field))) throw ControlJson.invalid("challenge preparation association");
            }
            if (!ControlJson.string(candidate, "preparedProofSha256").equals(ControlFrameCodec.payloadDigest(preparation.originalWireBytes()))) {
                throw ControlJson.invalid("challenge prepared digest");
            }
            connection = ControlJson.string(candidate, "connectionId");
        } else {
            if (challenge != null || !request.get("connectionProof").isJsonNull()) throw ControlJson.invalid("HTTPS challenge");
            connection = ControlJson.string(prepared, "connectionId");
        }
        // A proposal only. A losing CAS must never install it or advertise addressed readiness.
        return new ControlWriterFence(transport, currentWriter.sessionEpoch() + 1,
                ControlJson.string(prepared, "pendingSessionId"), connection, currentWriter.keyId(),
                currentWriter.machineKeyRevision());
    }

    static void associateResponse(ControlSessionCodec.Response response, ControlSessionCodec.Request request) {
        JsonObject result = decodeResponse(response.kind(), response.payloadBytes());
        JsonObject intent = decodeRequest(request.action(), request.payloadBytes());
        switch (response.kind()) {
            case "prepared" -> {
                if (timestamp(result, "expiresAt") > timestamp(intent, "intentExpiresAt")) throw ControlJson.invalid("prepare intent deadline");
                for (String field : List.of("transport", "capabilities", "clientNonce", "expectedWriter", "sessionDurationMillis")) {
                    if (!result.get(field).equals(intent.get(field))) throw ControlJson.invalid("prepared request association");
                }
            }
            case "connection-challenge" -> {
                byte[] prepared = proof(intent, "preparedProof");
                if (!ControlJson.string(result, "preparedProofSha256").equals(ControlFrameCodec.payloadDigest(prepared))) throw ControlJson.invalid("upgrade original proof digest");
            }
            case "status" -> {
                if (!result.get("query").equals(intent.get("query")) || intent.has("intentDigest")
                        && !result.get("intentDigest").equals(intent.get("intentDigest"))) throw ControlJson.invalid("status request association");
            }
            case "activated" -> {
                // Compare the signed result to the exact proofs in our persisted activation intent.
                // Authentication of nested proofs/current CAS still precedes activation at the provider.
                byte[] preparedBytes = proof(intent, "preparedProof");
                ControlSessionCodec.Response preparation = ControlSessionCodec.decodeResponse(new String(preparedBytes, StandardCharsets.UTF_8));
                if (!preparation.kind().equals("prepared")) throw ControlJson.invalid("activation prepared kind");
                sameSubject(request, preparation);
                JsonObject prepared = decodeResponse("prepared", preparation.payloadBytes());
                ControlWriterFence expected = fence(intent, "expectedWriter");
                if (!expected.equals(fence(prepared, "expectedWriter"))) throw ControlJson.invalid("activation prepared writer");
                String transport = ControlJson.string(prepared, "transport"), connection;
                if (transport.equals("websocket")) {
                    byte[] challengeBytes = proof(intent, "connectionProof");
                    ControlSessionCodec.Response challenge = ControlSessionCodec.decodeResponse(new String(challengeBytes, StandardCharsets.UTF_8));
                    if (!challenge.kind().equals("connection-challenge")) throw ControlJson.invalid("activation challenge kind");
                    sameSubject(request, challenge);
                    JsonObject candidate = decodeResponse("connection-challenge", challenge.payloadBytes());
                    for (String field : List.of("pendingSessionId", "transport", "capabilities", "clientNonce", "expiresAt", "sessionDurationMillis")) {
                        if (!prepared.get(field).equals(candidate.get(field))) throw ControlJson.invalid("activation challenge association");
                    }
                    if (!ControlJson.string(candidate, "preparedProofSha256").equals(ControlFrameCodec.payloadDigest(preparedBytes))) throw ControlJson.invalid("activation prepared digest");
                    connection = ControlJson.string(candidate, "connectionId");
                } else {
                    if (!intent.get("connectionProof").isJsonNull()) throw ControlJson.invalid("HTTPS activation challenge");
                    connection = ControlJson.string(prepared, "connectionId");
                }
                ControlWriterFence wanted = new ControlWriterFence(transport, expected.sessionEpoch() + 1,
                        ControlJson.string(prepared, "pendingSessionId"), connection, expected.keyId(),
                        expected.machineKeyRevision());
                long activatedAt = timestamp(result, "activatedAt");
                if (!fence(result, "writer").equals(wanted) || !result.get("capabilities").equals(prepared.get("capabilities"))
                        || activatedAt < timestamp(prepared, "preparedAt") || activatedAt >= timestamp(prepared, "expiresAt")
                        || timestamp(result, "sessionExpiresAt") - activatedAt != timestamp(prepared, "sessionDurationMillis")) {
                    throw ControlJson.invalid("activation result association");
                }
            }
            default -> throw ControlJson.invalid("session result association");
        }
    }

    private static void sameSubject(ControlSessionCodec.Request request, ControlSessionCodec.Response response) {
        if (!request.audience().equals(response.audience()) || !request.instanceId().equals(response.instanceId())
                || request.generation() != response.generation()) throw ControlJson.invalid("nested proof subject");
    }

    private static JsonObject parse(byte[] bytes) {
        if (bytes.length > ControlSessionCodec.MAX_PAYLOAD_BYTES) throw ControlJson.invalid("session payload size");
        ControlJson.utf8(bytes);
        return ControlJson.parse(new String(bytes, StandardCharsets.UTF_8), ControlSessionCodec.MAX_PAYLOAD_BYTES);
    }

    private static void current(JsonObject value, boolean legacyAllowed) {
        ControlWriterFence writer = fence(value, "writer");
        List<String> capabilities = ControlJson.strings(value, "capabilities");
        if (writer.transport().equals("legacy-http")) {
            if (!legacyAllowed || !capabilities.isEmpty()) throw ControlJson.invalid("legacy current writer");
            for (String field : List.of("activatedAt", "sessionExpiresAt", "authoritySourceCheckedAt", "authorityExpiresAt")) {
                if (!value.get(field).isJsonNull()) throw ControlJson.invalid("legacy current deadline");
            }
        } else {
            ControlProof.capabilities(writer.transport(), capabilities);
            long start = timestamp(value, "activatedAt"), end = timestamp(value, "sessionExpiresAt");
            long checked = timestamp(value, "authoritySourceCheckedAt"), authority = timestamp(value, "authorityExpiresAt");
            if (end <= start || end - start > MAX_SESSION_DURATION_MILLIS || authority <= checked || authority > end) throw ControlJson.invalid("session grant deadlines");
        }
    }

    private static void selected(JsonObject value) { ControlProof.capabilities(ControlJson.string(value, "transport"), ControlJson.strings(value, "capabilities")); }
    private static void pending(JsonObject value) { ControlJson.opaque(ControlJson.string(value, "pendingSessionId")); }
    private static void nonce(JsonObject value) { ControlJson.opaque(ControlJson.string(value, "clientNonce")); }
    private static ControlWriterFence fence(JsonObject value, String name) { return ControlWriterFence.read(ControlJson.object(value, name)); }
    private static long timestamp(JsonObject value, String name) {
        long time = ControlJson.number(value, name);
        ControlJson.safe(time, false);
        return time;
    }
    private static void duration(JsonObject value) {
        long duration = timestamp(value, "sessionDurationMillis");
        if (duration < 1 || duration > MAX_SESSION_DURATION_MILLIS) throw ControlJson.invalid("session duration");
    }
    private static byte[] proof(JsonObject value, String name) {
        byte[] bytes = ControlJson.base64(ControlJson.string(value, name), ControlSessionCodec.MAX_ENVELOPE_BYTES, false);
        ControlJson.utf8(bytes);
        return bytes;
    }
}
