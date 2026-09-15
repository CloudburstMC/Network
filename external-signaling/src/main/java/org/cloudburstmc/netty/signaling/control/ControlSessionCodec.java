package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Map;

/** Draft bootstrap proofs only. No endpoint, key discovery, authority journal or socket is created. */
public final class ControlSessionCodec {
    public static final int MAX_ENVELOPE_BYTES = 16384;
    public static final int MAX_PAYLOAD_BYTES = 8192;
    private static final Map<String, String> KINDS = Map.of("prepare", "prepared", "upgrade", "connection-challenge",
            "activate", "activated", "status", "status", "cancel-intent", "cancel-intent");

    public record Request(int version, String action, String requestId, String audience, String method,
                          String encodedPathAndQuery, String instanceId, long generation, long sentAt, long expiresAt,
                          String payload, String payloadSha256, ControlFrameCodec.Authentication authentication) {
        public byte[] payloadBytes() { return ControlJson.base64(payload, MAX_PAYLOAD_BYTES, false); }
    }

    public record Response(int version, String kind, String requestId, String requestIntentDigest, String audience,
                           String instanceId, long generation, long sentAt, long expiresAt, String payload,
                           String payloadSha256, ControlFrameCodec.Authentication authentication) {
        public byte[] payloadBytes() { return ControlJson.base64(payload, MAX_PAYLOAD_BYTES, false); }
    }

    /** Exact trusted operation and actual request target, plus the effective authorization deadline. */
    public record RequestContext(String action, String audience, String method, String encodedPathAndQuery,
                                 String instanceId, long generation, long now, long authorityExpiresAt, long clockSkewMillis) {
        public RequestContext { ControlProof.contextTime(now, authorityExpiresAt, clockSkewMillis); }
    }

    /** The original locally persisted/outstanding request binds action, body and ID, not just correlation. */
    public record ResponseContext(Request originalRequest, long now, long authorityExpiresAt, long clockSkewMillis) {
        public ResponseContext { ControlProof.contextTime(now, authorityExpiresAt, clockSkewMillis); }
    }

    /** Captures exact received bytes and a fixed verification deadline for nested proof association. */
    public static final class VerifiedResponse {
        private final Response response;
        private final String originalWire;
        private final long verifiedUntil;
        private VerifiedResponse(Response response, String originalWire, long verifiedUntil) {
            this.response = response;
            this.originalWire = originalWire;
            this.verifiedUntil = verifiedUntil;
        }
        public Response response() { return response; }
        public byte[] originalWireBytes() { return originalWire.getBytes(StandardCharsets.UTF_8); }
        public String encodedOriginalWire() { return ProviderCrypto.base64(originalWireBytes()); }
        public void requireUnexpired(long now) {
            ControlJson.safe(now, false);
            if (now >= verifiedUntil) throw ControlJson.invalid("nested proof deadline");
        }
    }

    private ControlSessionCodec() { }

    public static Request decodeRequest(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(value, "version", "action", "requestId", "audience", "method", "encodedPathAndQuery", "instanceId",
                "generation", "sentAt", "expiresAt", "payload", "payloadSha256", "authentication");
        Request result = new Request(ControlJson.version(value), ControlJson.string(value, "action"), ControlJson.string(value, "requestId"),
                ControlJson.string(value, "audience"), ControlJson.string(value, "method"), ControlJson.string(value, "encodedPathAndQuery"),
                ControlJson.string(value, "instanceId"), ControlJson.number(value, "generation"), ControlJson.number(value, "sentAt"),
                ControlJson.number(value, "expiresAt"), ControlJson.string(value, "payload"), ControlJson.string(value, "payloadSha256"),
                ControlProof.authentication(value));
        validate(result, true);
        return result;
    }

    public static Response decodeResponse(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(value, "version", "kind", "requestId", "requestIntentDigest", "audience", "instanceId", "generation",
                "sentAt", "expiresAt", "payload", "payloadSha256", "authentication");
        Response result = new Response(ControlJson.version(value), ControlJson.string(value, "kind"), ControlJson.string(value, "requestId"),
                ControlJson.string(value, "requestIntentDigest"), ControlJson.string(value, "audience"), ControlJson.string(value, "instanceId"),
                ControlJson.number(value, "generation"), ControlJson.number(value, "sentAt"), ControlJson.number(value, "expiresAt"),
                ControlJson.string(value, "payload"), ControlJson.string(value, "payloadSha256"), ControlProof.authentication(value));
        validate(result, true);
        return result;
    }

    public static Request verifyRequest(String wire, RequestContext context, ControlFrameCodec.VerificationKey key) {
        Request request = decodeRequest(wire);
        if (!request.action().equals(context.action()) || !request.audience().equals(context.audience())
                || !request.method().equals(context.method()) || !request.encodedPathAndQuery().equals(context.encodedPathAndQuery())
                || !request.instanceId().equals(context.instanceId()) || request.generation() != context.generation()) {
            throw ControlJson.invalid("session request context");
        }
        ControlProof.verify(signingBytes(request), request.authentication(), ControlFrameCodec.KeyFamily.MACHINE, key,
                request.sentAt(), request.expiresAt(), context.now(), context.authorityExpiresAt(), context.clockSkewMillis());
        if (request.action().equals("prepare")) {
            JsonObject payload = ControlSessionPayloadCodec.decodeRequest("prepare", request.payloadBytes());
            if (context.now() >= ControlJson.number(payload, "intentExpiresAt")
                    || ControlJson.number(payload, "intentCreatedAt") > context.now() + context.clockSkewMillis()) throw ControlJson.invalid("prepare intent time");
        }
        return request;
    }

    public static VerifiedResponse verifyResponse(String wire, ResponseContext context, ControlFrameCodec.VerificationKey key) {
        Response response = decodeResponse(wire);
        Request request = context.originalRequest();
        validate(request, false);
        if (!response.kind().equals(KINDS.get(request.action())) || !response.audience().equals(request.audience())
                || !response.instanceId().equals(request.instanceId()) || response.generation() != request.generation()
                || !response.requestId().equals(request.requestId()) || !response.requestIntentDigest().equals(requestIntentDigest(request))) {
            throw ControlJson.invalid("session response context");
        }
        ControlProof.verify(signingBytes(response), response.authentication(), ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, key,
                response.sentAt(), response.expiresAt(), context.now(), context.authorityExpiresAt(), context.clockSkewMillis());
        ControlSessionPayloadCodec.associateResponse(response, request);
        return new VerifiedResponse(response, wire, Math.min(response.expiresAt(), Math.min(context.authorityExpiresAt(), key.validUntil())));
    }

    public static String requestIntentDigest(Request request) {
        validate(request, false);
        return ControlFrameCodec.payloadDigest(ControlProof.array("nethernet-control-session-intent-v1", 1, request.action(),
                request.audience(), request.instanceId(), request.generation(), request.requestId(), request.payloadSha256()));
    }

    public static byte[] signingBytes(Request value) {
        validate(value, false);
        return ControlProof.array("nethernet-control-session-request-v1", 1, ControlFrameCodec.SCHEME, value.action(), value.audience(),
                value.method(), value.encodedPathAndQuery(), value.requestId(), value.instanceId(), value.generation(), value.sentAt(),
                value.expiresAt(), value.authentication().keyId(), value.payloadSha256());
    }

    public static byte[] signingBytes(Response value) {
        validate(value, false);
        return ControlProof.array("nethernet-control-session-response-v1", 1, ControlFrameCodec.SCHEME, value.kind(), value.audience(),
                value.requestId(), value.requestIntentDigest(), value.instanceId(), value.generation(), value.sentAt(), value.expiresAt(),
                value.authentication().keyId(), value.payloadSha256());
    }

    public static Request sign(Request value, PrivateKey key) throws GeneralSecurityException {
        return new Request(value.version(), value.action(), value.requestId(), value.audience(), value.method(), value.encodedPathAndQuery(),
                value.instanceId(), value.generation(), value.sentAt(), value.expiresAt(), value.payload(), value.payloadSha256(),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, value.authentication().keyId(), ControlProof.sign(signingBytes(value), key)));
    }

    public static Response sign(Response value, PrivateKey key) throws GeneralSecurityException {
        return new Response(value.version(), value.kind(), value.requestId(), value.requestIntentDigest(), value.audience(), value.instanceId(),
                value.generation(), value.sentAt(), value.expiresAt(), value.payload(), value.payloadSha256(),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, value.authentication().keyId(), ControlProof.sign(signingBytes(value), key)));
    }

    public static String encode(Request value) {
        validate(value, true);
        JsonObject object = base(value.version(), value.audience(), value.instanceId(), value.generation(), value.sentAt(), value.expiresAt(),
                value.payload(), value.payloadSha256(), value.authentication());
        object.addProperty("action", value.action());
        object.addProperty("requestId", value.requestId());
        object.addProperty("method", value.method());
        object.addProperty("encodedPathAndQuery", value.encodedPathAndQuery());
        return bounded(object);
    }

    public static String encode(Response value) {
        validate(value, true);
        JsonObject object = base(value.version(), value.audience(), value.instanceId(), value.generation(), value.sentAt(), value.expiresAt(),
                value.payload(), value.payloadSha256(), value.authentication());
        object.addProperty("kind", value.kind());
        object.addProperty("requestId", value.requestId());
        object.addProperty("requestIntentDigest", value.requestIntentDigest());
        return bounded(object);
    }

    private static JsonObject base(int version, String audience, String instanceId, long generation, long sentAt, long expiresAt,
                                   String payload, String digest, ControlFrameCodec.Authentication auth) {
        JsonObject value = new JsonObject();
        value.addProperty("version", version);
        value.addProperty("audience", audience);
        value.addProperty("instanceId", instanceId);
        value.addProperty("generation", generation);
        value.addProperty("sentAt", sentAt);
        value.addProperty("expiresAt", expiresAt);
        value.addProperty("payload", payload);
        value.addProperty("payloadSha256", digest);
        value.add("authentication", ControlProof.authenticationObject(auth));
        return value;
    }

    private static String bounded(JsonObject object) {
        String wire = object.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) throw ControlJson.invalid("session envelope size");
        return wire;
    }

    private static void validate(Request request, boolean signature) {
        if (!KINDS.containsKey(request.action()) || !(request.action().equals("upgrade") ? "GET" : "POST").equals(request.method())) {
            throw ControlJson.invalid("bootstrap action or method");
        }
        common(request.version(), request.requestId(), request.audience(), request.instanceId(), request.generation(), request.sentAt(),
                request.expiresAt(), request.payload(), request.payloadSha256(), request.authentication(), signature);
        ControlProof.path(request.encodedPathAndQuery());
        JsonObject payload = ControlSessionPayloadCodec.decodeRequest(request.action(), request.payloadBytes());
        if (request.action().equals("prepare") && request.expiresAt() > ControlJson.number(payload, "intentExpiresAt")) throw ControlJson.invalid("prepare delivery deadline");
        if (request.action().equals("cancel-intent")) {
            var intent = ControlLifecycleCodec.readIntent(ControlJson.object(payload, "intent"));
            var writer = ControlWriterFence.read(ControlJson.object(payload, "expectedWriter"));
            if (!intent.audience().equals(request.audience()) || !intent.instanceId().equals(request.instanceId())
                    || intent.generation() != request.generation() || !writer.keyId().equals(request.authentication().keyId())) throw ControlJson.invalid("cancellation subject");
        }
    }

    private static void validate(Response response, boolean signature) {
        if (!KINDS.containsValue(response.kind())) throw ControlJson.invalid("bootstrap response kind");
        common(response.version(), response.requestId(), response.audience(), response.instanceId(), response.generation(), response.sentAt(),
                response.expiresAt(), response.payload(), response.payloadSha256(), response.authentication(), signature);
        ControlJson.digest(response.requestIntentDigest());
        JsonObject payload = ControlSessionPayloadCodec.decodeResponse(response.kind(), response.payloadBytes());
        if ((response.kind().equals("prepared") || response.kind().equals("activated"))
                && !ControlJson.string(payload, "intentDigest").equals(response.requestIntentDigest())) throw ControlJson.invalid("response inner intent association");
        if ((response.kind().equals("prepared") || response.kind().equals("connection-challenge"))
                && response.expiresAt() > ControlJson.number(payload, "expiresAt")) throw ControlJson.invalid("preparation proof expiry");
    }

    private static void common(int version, String requestId, String audience, String instanceId, long generation,
                               long sentAt, long expiresAt, String payload, String digest,
                               ControlFrameCodec.Authentication authentication, boolean signature) {
        if (version != 1) throw ControlJson.invalid("session version");
        ControlJson.opaque(requestId);
        ControlJson.audience(audience);
        ControlJson.identifier(instanceId);
        ControlJson.safe(generation, true);
        ControlProof.lifetime(sentAt, expiresAt);
        ControlJson.digest(digest);
        byte[] bytes = ControlJson.base64(payload, MAX_PAYLOAD_BYTES, false);
        ControlJson.utf8(bytes);
        if (!ControlFrameCodec.payloadDigest(bytes).equals(digest)) throw ControlJson.invalid("session payload digest");
        ControlProof.authentication(authentication, signature);
    }
}
