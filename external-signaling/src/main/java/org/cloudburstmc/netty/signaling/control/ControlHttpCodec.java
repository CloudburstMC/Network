package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.List;

/** Draft signed operational HTTP carrier. This does not select routes, reserve sequence or commit state. */
public final class ControlHttpCodec {
    public static final int MAX_ENVELOPE_BYTES = 16384;

    public record Request(int version, String audience, String method, String encodedPathAndQuery, long sentAt,
                          long expiresAt, ControlLifecycleCodec.Intent intent, String sessionId, long sessionEpoch,
                          String connectionId, String writerTransport, List<String> capabilities,
                          ControlFrameCodec.Authentication authentication) {
        public Request { capabilities = List.copyOf(capabilities); }
    }

    /** Expected writer/key revision comes from trusted authority, never from the request's key ID. */
    public record Context(String audience, String instanceId, long generation, String method, String encodedPathAndQuery,
                          String operation, ControlWriterFence writer, List<String> capabilities, long now,
                          long authorityExpiresAt, long clockSkewMillis) {
        public Context {
            capabilities = List.copyOf(capabilities);
            ControlProof.contextTime(now, authorityExpiresAt, clockSkewMillis);
            if (writer.transport().equals("legacy-http")) throw ControlJson.invalid("controlled HTTP writer");
            ControlProof.capabilities(writer.transport(), capabilities);
        }
    }

    private ControlHttpCodec() { }

    public static Request decode(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_ENVELOPE_BYTES);
        ControlJson.fields(value, "version", "audience", "method", "encodedPathAndQuery", "sentAt", "expiresAt", "intent",
                "sessionId", "sessionEpoch", "connectionId", "writerTransport", "capabilities", "authentication");
        Request result = new Request(ControlJson.version(value), ControlJson.string(value, "audience"),
                ControlJson.string(value, "method"), ControlJson.string(value, "encodedPathAndQuery"),
                ControlJson.number(value, "sentAt"), ControlJson.number(value, "expiresAt"),
                ControlLifecycleCodec.readIntent(ControlJson.object(value, "intent")), ControlJson.string(value, "sessionId"),
                ControlJson.number(value, "sessionEpoch"), ControlJson.string(value, "connectionId"),
                ControlJson.string(value, "writerTransport"), ControlJson.strings(value, "capabilities"), ControlProof.authentication(value));
        validate(result, true);
        return result;
    }

    public static Request verify(String wire, byte[] originalBody, Context context, ControlFrameCodec.VerificationKey key) {
        Request request = decode(wire);
        ControlLifecycleCodec.Intent intent = request.intent();
        ControlWriterFence writer = context.writer();
        if (!request.audience().equals(context.audience()) || !intent.instanceId().equals(context.instanceId())
                || intent.generation() != context.generation() || !request.method().equals(context.method())
                || !request.encodedPathAndQuery().equals(context.encodedPathAndQuery()) || !intent.operation().equals(context.operation())
                || !request.sessionId().equals(writer.sessionId()) || request.sessionEpoch() != writer.sessionEpoch()
                || !request.connectionId().equals(writer.connectionId()) || !request.writerTransport().equals(writer.transport())
                || !request.authentication().keyId().equals(writer.keyId()) || !request.capabilities().equals(context.capabilities())) {
            throw ControlJson.invalid("HTTP delivery context");
        }
        ControlLifecycleCodec.verifyBody(intent, originalBody);
        ControlProof.verify(signingBytes(request), request.authentication(), ControlFrameCodec.KeyFamily.MACHINE, key,
                request.sentAt(), request.expiresAt(), context.now(), context.authorityExpiresAt(), context.clockSkewMillis());
        // The durable handler must first reconcile the immutable intent, then atomically check the
        // current writer/key/generation for any new mutation and again fence sensitive delivery.
        return request;
    }

    public static Request sign(Request value, PrivateKey key) throws GeneralSecurityException {
        validate(value, false);
        return new Request(value.version(), value.audience(), value.method(), value.encodedPathAndQuery(), value.sentAt(),
                value.expiresAt(), value.intent(), value.sessionId(), value.sessionEpoch(), value.connectionId(),
                value.writerTransport(), value.capabilities(), new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME,
                value.authentication().keyId(), ControlProof.sign(signingBytes(value), key)));
    }

    public static byte[] signingBytes(Request value) {
        validate(value, false);
        return ControlProof.array("nethernet-control-http-request-v1", 1, ControlFrameCodec.SCHEME, value.audience(),
                value.method(), value.encodedPathAndQuery(), value.sentAt(), value.expiresAt(), value.intent().instanceId(),
                value.intent().generation(), value.sessionId(), value.sessionEpoch(), value.connectionId(), value.writerTransport(),
                value.capabilities(), value.authentication().keyId(), ControlLifecycleCodec.intentDigest(value.intent()), value.intent().payloadSha256());
    }

    public static String encode(Request value) {
        validate(value, true);
        JsonObject object = new JsonObject();
        object.addProperty("version", value.version());
        object.addProperty("audience", value.audience());
        object.addProperty("method", value.method());
        object.addProperty("encodedPathAndQuery", value.encodedPathAndQuery());
        object.addProperty("sentAt", value.sentAt());
        object.addProperty("expiresAt", value.expiresAt());
        object.add("intent", ControlLifecycleCodec.intentObject(value.intent()));
        object.addProperty("sessionId", value.sessionId());
        object.addProperty("sessionEpoch", value.sessionEpoch());
        object.addProperty("connectionId", value.connectionId());
        object.addProperty("writerTransport", value.writerTransport());
        object.add("capabilities", ControlProof.capabilitiesObject(value.capabilities()));
        object.add("authentication", ControlProof.authenticationObject(value.authentication()));
        String wire = object.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) throw ControlJson.invalid("HTTP envelope size");
        return wire;
    }

    private static void validate(Request value, boolean signature) {
        if (value.version() != 1 || !"POST".equals(value.method())) throw ControlJson.invalid("HTTP version or method");
        ControlJson.audience(value.audience());
        ControlProof.path(value.encodedPathAndQuery());
        ControlProof.lifetime(value.sentAt(), value.expiresAt());
        ControlLifecycleCodec.intentDigest(value.intent());
        if (!value.intent().audience().equals(value.audience())) throw ControlJson.invalid("intent audience");
        ControlJson.opaque(value.sessionId());
        ControlJson.safe(value.sessionEpoch(), true);
        ControlJson.opaque(value.connectionId());
        ControlProof.capabilities(value.writerTransport(), value.capabilities());
        ControlProof.authentication(value.authentication(), signature);
    }
}
