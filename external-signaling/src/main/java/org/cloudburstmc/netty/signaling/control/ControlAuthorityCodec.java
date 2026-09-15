package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.List;

/** Separate cache-only authority proof. Expired previous authority does not prevent renewal. */
public final class ControlAuthorityCodec {
    public static final int MAX_ENVELOPE_BYTES = 8192;
    public static final long MAX_DELIVERY_MILLIS = 30000, MAX_SOURCE_AGE_MILLIS = 300000;
    private ControlAuthorityCodec() { }

    public record Request(int version, String kind, String requestId, String audience, String instanceId, long generation,
            ControlWriterFence writer, List<String> capabilities, long sentAt, long expiresAt,
            String method, String encodedPathAndQuery, long authorityNotAfter, ControlFrameCodec.Authentication authentication) {
        public Request { capabilities = List.copyOf(capabilities); }
    }
    public record Source(String sourceId, long sourceRevision, long sourceWatermark, long sourceCheckedAt, long sourceExpiresAt) { }
    public record Response(int version, String kind, String requestId, String audience, String instanceId, long generation,
            ControlWriterFence writer, List<String> capabilities, long sentAt, long expiresAt, String requestDigest,
            Source source, long subjectExpiresAt, long authorityExpiresAt, List<String> permissions,
            ControlFrameCodec.Authentication authentication) {
        public Response { capabilities = List.copyOf(capabilities); permissions = List.copyOf(permissions); }
    }
    public record RequestContext(String audience, String instanceId, long generation, String method, String encodedPathAndQuery,
            ControlWriterFence writer, List<String> capabilities, long now, long sessionExpiresAt, long clockSkewMillis) {
        public RequestContext { capabilities = List.copyOf(capabilities); }
    }
    /** Persist within the trusted provider/instance scope across reconnect and selected-key changes. */
    public record Floor(String audience, String instanceId, long generation, Source source, ControlWriterFence writer, List<String> capabilities, long subjectExpiresAt, List<String> permissions) {
        public Floor { capabilities = List.copyOf(capabilities); permissions = List.copyOf(permissions); }
    }
    public record ResponseContext(Request originalRequest, long now, long sessionExpiresAt, long clockSkewMillis, Floor floor) { }
    public static final class Verified {
        private final Response response;
        private final String originalWire;
        private Verified(Response response, String originalWire) { this.response = response; this.originalWire = originalWire; }
        public Response response() { return response; }
        public String originalWire() { return originalWire; }
        public Floor floor() { return new Floor(response.audience(), response.instanceId(), response.generation(), response.source(), response.writer(), response.capabilities(), response.subjectExpiresAt(), response.permissions()); }
        /** Before installing: also atomically consume the pending nonce and recheck the current writer/key catalog. */
        public void requireFreshDelivery(long now, Floor currentFloor) {
            requireUnexpired(now);
            if (now >= response.expiresAt()) throw ControlJson.invalid("authority delivery expired");
            if (currentFloor != null) checkFloor(response, currentFloor);
        }
        /** After installation, recheck the currently installed proof before each protected action. */
        public void requireUnexpired(long now) {
            ControlJson.safe(now, false);
            if (now >= response.authorityExpiresAt()) throw ControlJson.invalid("authority expired");
        }
    }
    public static Request decodeRequest(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(o, "version", "kind", "requestId", "audience", "instanceId", "generation", "writer", "capabilities",
                "sentAt", "expiresAt", "method", "encodedPathAndQuery", "authorityNotAfter", "authentication");
        Request value = new Request((int) ControlJson.number(o, "version"), ControlJson.string(o, "kind"), ControlJson.string(o, "requestId"),
                ControlJson.string(o, "audience"), ControlJson.string(o, "instanceId"), ControlJson.number(o, "generation"),
                ControlWriterFence.read(ControlJson.object(o, "writer")), strings(o, "capabilities"), ControlJson.number(o, "sentAt"),
                ControlJson.number(o, "expiresAt"), ControlJson.string(o, "method"), ControlJson.string(o, "encodedPathAndQuery"),
                ControlJson.number(o, "authorityNotAfter"), ControlProof.authentication(o));
        if (ControlJson.number(o, "version") != 1) throw ControlJson.invalid("authority version");
        validate(value, true); return value;
    }
    public static Response decodeResponse(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(o, "version", "kind", "requestId", "audience", "instanceId", "generation", "writer", "capabilities",
                "sentAt", "expiresAt", "requestDigest", "sourceId", "sourceRevision", "sourceWatermark", "sourceCheckedAt",
                "sourceExpiresAt", "subjectExpiresAt", "authorityExpiresAt", "permissions", "authentication");
        Response value = new Response((int) ControlJson.number(o, "version"), ControlJson.string(o, "kind"), ControlJson.string(o, "requestId"),
                ControlJson.string(o, "audience"), ControlJson.string(o, "instanceId"), ControlJson.number(o, "generation"),
                ControlWriterFence.read(ControlJson.object(o, "writer")), strings(o, "capabilities"), ControlJson.number(o, "sentAt"),
                ControlJson.number(o, "expiresAt"), ControlJson.string(o, "requestDigest"), new Source(ControlJson.string(o, "sourceId"),
                ControlJson.number(o, "sourceRevision"), ControlJson.number(o, "sourceWatermark"), ControlJson.number(o, "sourceCheckedAt"),
                ControlJson.number(o, "sourceExpiresAt")), ControlJson.number(o, "subjectExpiresAt"), ControlJson.number(o, "authorityExpiresAt"),
                strings(o, "permissions"), ControlProof.authentication(o));
        if (ControlJson.number(o, "version") != 1) throw ControlJson.invalid("authority version");
        validate(value, true); return value;
    }
    private static List<String> strings(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw ControlJson.invalid("authority list");
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (var item : object.getAsJsonArray(name)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw ControlJson.invalid("authority list item");
            values.add(item.getAsString());
        }
        return List.copyOf(values);
    }
    public static byte[] signingBytes(Request v) {
        validate(v, false); var w = v.writer();
        return ControlProof.array("nethernet-control-authority-request-v1", 1, ControlFrameCodec.SCHEME, v.audience(), v.method(),
                v.encodedPathAndQuery(), v.requestId(), v.instanceId(), v.generation(), w.transport(), w.sessionEpoch(), w.sessionId(),
                w.connectionId(), w.keyId(), w.machineKeyRevision(), v.capabilities(), v.sentAt(), v.expiresAt(), v.authorityNotAfter(), v.authentication().keyId());
    }
    public static byte[] signingBytes(Response v) {
        validate(v, false); var w = v.writer(); var s = v.source();
        return ControlProof.array("nethernet-control-authority-response-v1", 1, ControlFrameCodec.SCHEME, v.audience(), v.requestId(),
                v.requestDigest(), v.instanceId(), v.generation(), w.transport(), w.sessionEpoch(), w.sessionId(), w.connectionId(), w.keyId(),
                w.machineKeyRevision(), v.capabilities(), s.sourceId(), s.sourceRevision(), s.sourceWatermark(), s.sourceCheckedAt(),
                s.sourceExpiresAt(), v.subjectExpiresAt(), v.authorityExpiresAt(), v.permissions(), v.sentAt(), v.expiresAt(), v.authentication().keyId());
    }
    public static String requestDigest(Request value) { return ControlFrameCodec.payloadDigest(signingBytes(value)); }
    public static Request sign(Request v, PrivateKey key) throws GeneralSecurityException {
        return new Request(v.version(), v.kind(), v.requestId(), v.audience(), v.instanceId(), v.generation(), v.writer(), v.capabilities(),
                v.sentAt(), v.expiresAt(), v.method(), v.encodedPathAndQuery(), v.authorityNotAfter(),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, v.authentication().keyId(), ControlProof.sign(signingBytes(v), key)));
    }
    public static Response sign(Response v, PrivateKey key) throws GeneralSecurityException {
        return new Response(v.version(), v.kind(), v.requestId(), v.audience(), v.instanceId(), v.generation(), v.writer(), v.capabilities(),
                v.sentAt(), v.expiresAt(), v.requestDigest(), v.source(), v.subjectExpiresAt(), v.authorityExpiresAt(), v.permissions(),
                new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, v.authentication().keyId(), ControlProof.sign(signingBytes(v), key)));
    }
    public static String encode(Request value) {
        validate(value, true);
        var o = base(value.version(), value.kind(), value.requestId(), value.audience(), value.instanceId(), value.generation(), value.writer(),
                value.capabilities(), value.sentAt(), value.expiresAt(), value.authentication());
        o.addProperty("method", value.method()); o.addProperty("encodedPathAndQuery", value.encodedPathAndQuery());
        o.addProperty("authorityNotAfter", value.authorityNotAfter()); return bounded(o);
    }
    public static String encode(Response value) {
        validate(value, true);
        var o = base(value.version(), value.kind(), value.requestId(), value.audience(), value.instanceId(), value.generation(), value.writer(),
                value.capabilities(), value.sentAt(), value.expiresAt(), value.authentication());
        o.addProperty("requestDigest", value.requestDigest()); var s = value.source();
        o.addProperty("sourceId", s.sourceId()); o.addProperty("sourceRevision", s.sourceRevision()); o.addProperty("sourceWatermark", s.sourceWatermark());
        o.addProperty("sourceCheckedAt", s.sourceCheckedAt()); o.addProperty("sourceExpiresAt", s.sourceExpiresAt());
        o.addProperty("subjectExpiresAt", value.subjectExpiresAt()); o.addProperty("authorityExpiresAt", value.authorityExpiresAt());
        o.add("permissions", ControlProof.capabilitiesObject(value.permissions())); return bounded(o);
    }
    private static JsonObject base(int version, String kind, String requestId, String audience, String instanceId, long generation,
            ControlWriterFence writer, List<String> capabilities, long sentAt, long expiresAt, ControlFrameCodec.Authentication auth) {
        JsonObject o = new JsonObject();
        o.addProperty("version", version); o.addProperty("kind", kind); o.addProperty("requestId", requestId); o.addProperty("audience", audience);
        o.addProperty("instanceId", instanceId); o.addProperty("generation", generation); o.add("writer", writer.object());
        o.add("capabilities", ControlProof.capabilitiesObject(capabilities)); o.addProperty("sentAt", sentAt); o.addProperty("expiresAt", expiresAt);
        o.add("authentication", ControlProof.authenticationObject(auth)); return o;
    }
    private static String bounded(JsonObject object) {
        String wire = object.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) throw ControlJson.invalid("authority size");
        return wire;
    }
    private static void common(int version, String requestId, String audience, String instanceId, long generation,
            ControlWriterFence writer, List<String> capabilities, long sentAt, long expiresAt, ControlFrameCodec.Authentication auth, boolean signature) {
        if (version != 1) throw ControlJson.invalid("authority version");
        ControlJson.opaque(requestId); ControlJson.audience(audience); ControlJson.identifier(instanceId); ControlJson.safe(generation, true);
        ControlProof.lifetime(sentAt, expiresAt);
        if (expiresAt - sentAt > MAX_DELIVERY_MILLIS || writer.transport().equals("legacy-http")) throw ControlJson.invalid("authority lifetime/writer");
        writer.encode(); ControlProof.capabilities(writer.transport(), capabilities); ControlProof.authentication(auth, signature);
    }
    private static void validate(Request value, boolean signature) {
        common(value.version(), value.requestId(), value.audience(), value.instanceId(), value.generation(), value.writer(), value.capabilities(),
                value.sentAt(), value.expiresAt(), value.authentication(), signature);
        ControlProof.path(value.encodedPathAndQuery()); ControlJson.safe(value.authorityNotAfter(), false);
        if (!value.kind().equals("authority-request") || !value.method().equals("POST") || value.authorityNotAfter() < value.expiresAt()
                || value.authorityNotAfter() - value.sentAt() > MAX_SOURCE_AGE_MILLIS || !value.authentication().keyId().equals(value.writer().keyId())) throw ControlJson.invalid("authority request");
    }
    private static void source(Source value) {
        ControlJson.identifier(value.sourceId()); ControlJson.safe(value.sourceRevision(), false); ControlJson.safe(value.sourceWatermark(), false);
        ControlJson.safe(value.sourceCheckedAt(), false); ControlJson.safe(value.sourceExpiresAt(), false);
        if (value.sourceExpiresAt() <= value.sourceCheckedAt() || value.sourceExpiresAt() - value.sourceCheckedAt() > MAX_SOURCE_AGE_MILLIS) throw ControlJson.invalid("authority source");
    }
    private static void permissions(List<String> values, ControlWriterFence writer, List<String> capabilities) {
        if (values.size() > 2) throw ControlJson.invalid("authority permissions");
        String previous = "";
        for (String value : values) {
            if (!List.of("control.assisted", "control.status").contains(value) || value.compareTo(previous) <= 0) throw ControlJson.invalid("authority permissions");
            previous = value;
        }
        if (values.contains("control.assisted") && (writer.transport().equals("https") || !capabilities.contains("addressed")
                || !capabilities.contains("assisted-gameplay") && !capabilities.contains("assisted-diagnostic"))) throw ControlJson.invalid("authority assisted permissions");
    }
    private static void validate(Response value, boolean signature) {
        common(value.version(), value.requestId(), value.audience(), value.instanceId(), value.generation(), value.writer(), value.capabilities(),
                value.sentAt(), value.expiresAt(), value.authentication(), signature);
        ControlJson.digest(value.requestDigest()); source(value.source()); permissions(value.permissions(), value.writer(), value.capabilities());
        ControlJson.safe(value.subjectExpiresAt(), false); ControlJson.safe(value.authorityExpiresAt(), false);
        if (!value.kind().equals("authority-response") || value.authorityExpiresAt() > value.source().sourceExpiresAt()
                || value.authorityExpiresAt() > value.subjectExpiresAt() || value.authorityExpiresAt() < value.expiresAt()
                || value.authorityExpiresAt() <= value.source().sourceCheckedAt()) throw ControlJson.invalid("authority response");
    }
    /** The exact selected key/binding must come from a current verified cached source projection. */
    public static Request verifyRequest(String wire, RequestContext context, ControlFrameCodec.VerificationKey key) {
        Request v = decodeRequest(wire);
        if (!v.audience().equals(context.audience()) || !v.instanceId().equals(context.instanceId()) || v.generation() != context.generation()
                || !v.method().equals(context.method()) || !v.encodedPathAndQuery().equals(context.encodedPathAndQuery())
                || !v.writer().equals(context.writer()) || !v.capabilities().equals(context.capabilities())) throw ControlJson.invalid("authority request context");
        ControlProof.verify(signingBytes(v), v.authentication(), ControlFrameCodec.KeyFamily.MACHINE, key, v.sentAt(), v.expiresAt(), context.now(), context.sessionExpiresAt(), context.clockSkewMillis());
        return v;
    }
    /** Verifies against fixed session/provider trust; never against the old short-lived authority proof. */
    public static Verified verifyResponse(String wire, ResponseContext context, ControlFrameCodec.VerificationKey key) {
        Response v = decodeResponse(wire); Request r = context.originalRequest(); validate(r, true);
        if (key == null || r.authorityNotAfter() > context.sessionExpiresAt() || !v.audience().equals(r.audience()) || !v.instanceId().equals(r.instanceId())
                || v.generation() != r.generation() || !v.requestId().equals(r.requestId()) || !v.writer().equals(r.writer())
                || !v.capabilities().equals(r.capabilities()) || !v.requestDigest().equals(requestDigest(r)) || v.expiresAt() > r.expiresAt()
                || v.authorityExpiresAt() > r.authorityNotAfter() || v.authorityExpiresAt() > key.validUntil()
                || v.source().sourceCheckedAt() > context.now() + context.clockSkewMillis()) throw ControlJson.invalid("authority response context");
        ControlProof.verify(signingBytes(v), v.authentication(), ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, key, v.sentAt(), v.expiresAt(), context.now(), context.sessionExpiresAt(), context.clockSkewMillis());
        if (context.floor() != null) checkFloor(v, context.floor());
        return new Verified(v, wire);
    }
    static void checkFloor(Response v, Floor floor) {
        source(floor.source()); floor.writer().encode(); ControlProof.capabilities(floor.writer().transport(), floor.capabilities());
        ControlJson.safe(floor.subjectExpiresAt(), false); permissions(floor.permissions(), floor.writer(), floor.capabilities());
        ControlJson.audience(floor.audience()); ControlJson.identifier(floor.instanceId()); ControlJson.safe(floor.generation(), true);
        Source s = v.source(), f = floor.source();
        if (!v.audience().equals(floor.audience()) || !v.instanceId().equals(floor.instanceId()) || v.generation() < floor.generation()
                || !s.sourceId().equals(f.sourceId()) || s.sourceRevision() < f.sourceRevision() || s.sourceWatermark() < f.sourceWatermark()
                || s.sourceCheckedAt() < f.sourceCheckedAt()) throw ControlJson.invalid("authority source rollback");
        if (s.sourceRevision() == f.sourceRevision()) {
            if (v.generation() != floor.generation() || !v.writer().equals(floor.writer()) || v.subjectExpiresAt() != floor.subjectExpiresAt() || !v.capabilities().equals(floor.capabilities())
                    || !v.permissions().equals(floor.permissions())) throw ControlJson.invalid("authority policy rollback");
            if (s.sourceCheckedAt() == f.sourceCheckedAt() && s.sourceExpiresAt() > f.sourceExpiresAt()) throw ControlJson.invalid("authority deadline rollback");
        }
    }
}
