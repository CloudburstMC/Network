package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Draft active-session envelope only. Callers supply already trusted active connection/key/authority
 * context, retain receive sequence state, and validate typed payloads before acting. No bootstrap,
 * session activation, durable lifecycle, storage lookup or application authorization occurs here.
 */
public final class ControlFrameCodec {
    public static final String DOMAIN = "nethernet-control-frame-v1";
    public static final String SCHEME = "nxs-control-es384-v1";
    public static final int MAX_FRAME_BYTES = 131072;
    public static final int MAX_PAYLOAD_BYTES = 65536;
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
    public static final long MAX_FRAME_TTL_MILLIS = 60000;
    public static final long MAX_CLOCK_SKEW_MILLIS = 30000;
    private static final Set<String> FIELDS = Set.of("version", "type", "id", "sequence", "direction", "audience",
            "instanceId", "generation", "sessionId", "sessionEpoch", "connectionId", "capabilities", "sentAt",
            "expiresAt", "payload", "payloadSha256", "authentication");
    private static final Set<String> AUTH_FIELDS = Set.of("scheme", "keyId", "signature");
    private static final Set<String> NUMBER_FIELDS = Set.of("version", "sequence", "generation", "sessionEpoch",
            "sentAt", "expiresAt");
    private static final Set<String> CAPABILITIES = Set.of("request-response", "addressed", "assisted-gameplay", "assisted-diagnostic");
    private static final Set<String> HOST_TYPES = Set.of("session.ready", "session.resync", "session.draining",
            "lifecycle.request", "state.applied", "outcomes.batch", "assisted.answer", "assisted.error",
            "diagnostic.answer", "diagnostic.error");
    private static final Set<String> PROVIDER_TYPES = Set.of("session.ready", "session.resync", "session.reconnect",
            "lifecycle.receipt", "outcomes.receipt", "connectivity.report", "state.desired", "assisted.offer", "assisted.cancel",
            "diagnostic.offer", "diagnostic.cancel");

    public enum Direction {
        HOST_TO_PROVIDER("host-to-provider", KeyFamily.MACHINE),
        PROVIDER_TO_HOST("provider-to-host", KeyFamily.PROVIDER_CONTROL);
        private final String wire;
        private final KeyFamily keyFamily;

        Direction(String wire, KeyFamily keyFamily) {
            this.wire = wire;
            this.keyFamily = keyFamily;
        }

        public String wire() { return this.wire; }

        static Direction parse(String value) {
            for (Direction direction : values()) {
                if (direction.wire.equals(value)) return direction;
            }
            throw invalid("direction");
        }
    }

    public enum KeyFamily { MACHINE, PROVIDER_CONTROL }

    public record Authentication(String scheme, String keyId, String signature) { }

    /** Immutable syntactically valid frame; decoding is not proof verification. */
    public record Frame(int version, String type, String id, long sequence, Direction direction, String audience,
                        String instanceId, long generation, String sessionId, long sessionEpoch, String connectionId,
                        List<String> capabilities, long sentAt, long expiresAt, String payload, String payloadSha256,
                        Authentication authentication) {
        public Frame {
            capabilities = List.copyOf(capabilities);
        }

        public byte[] payloadBytes() { return decodeBase64(this.payload, MAX_PAYLOAD_BYTES, true); }
    }

    /** Construct from authenticated session state, never from the unverified frame itself. */
    public record Context(Direction direction, String audience, String instanceId, long generation,
                          String sessionId, long sessionEpoch, String connectionId, List<String> capabilities,
                          long expectedSequence, long now, long authorityExpiresAt, long clockSkewMillis) {
        public Context {
            capabilities = List.copyOf(capabilities);
            if (clockSkewMillis < 0 || clockSkewMillis > MAX_CLOCK_SKEW_MILLIS) throw invalid("clock skew");
            safe(now, false);
            safe(authorityExpiresAt, false);
            safe(expectedSequence, true);
        }
    }

    /** Keys must come from a separately trusted ring for the declared family, not an embedded frame JWK. */
    public record VerificationKey(KeyFamily family, String keyId, PublicKey key, long validFrom, long validUntil) {
        public VerificationKey {
            identifier(keyId);
            p384(key);
            safe(validFrom, false);
            safe(validUntil, false);
            if (family == null || validUntil <= validFrom) throw invalid("verification key");
        }
    }

    private ControlFrameCodec() { }

    /** Strict JSON: duplicate/unknown fields, trailing data and alternate field types are rejected. */
    public static Frame decode(String wire) {
        if (wire == null || wire.length() > MAX_FRAME_BYTES
                || wire.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) throw invalid("frame size");
        if (wire.startsWith("\uFEFF")) throw invalid("JSON byte order mark");
        try (JsonReader reader = new JsonReader(new StringReader(wire))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject object = readObject(reader, false);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid("trailing data");
            JsonObject auth = object.getAsJsonObject("authentication");
            List<String> capabilities = new ArrayList<>();
            object.getAsJsonArray("capabilities").forEach(value -> capabilities.add(value.getAsString()));
            Frame frame = new Frame(Math.toIntExact(object.get("version").getAsLong()),
                    string(object, "type"), string(object, "id"), object.get("sequence").getAsLong(),
                    Direction.parse(string(object, "direction")), string(object, "audience"),
                    string(object, "instanceId"), object.get("generation").getAsLong(), string(object, "sessionId"),
                    object.get("sessionEpoch").getAsLong(), string(object, "connectionId"), capabilities,
                    object.get("sentAt").getAsLong(), object.get("expiresAt").getAsLong(), string(object, "payload"),
                    string(object, "payloadSha256"), new Authentication(string(auth, "scheme"), string(auth, "keyId"),
                    string(auth, "signature")));
            validate(frame, true);
            return frame;
        } catch (IOException | ArithmeticException | IllegalStateException failure) {
            throw new IllegalArgumentException("Invalid control frame", failure);
        }
    }

    /** Signature and exact active-context verification; no state is advanced on either success or failure. */
    public static Frame verify(String wire, Context context, VerificationKey key) {
        Frame frame = decode(wire);
        if (key == null || key.family() != frame.direction().keyFamily || !key.keyId().equals(frame.authentication().keyId())
                || frame.direction() != context.direction() || !frame.audience().equals(context.audience())
                || !frame.instanceId().equals(context.instanceId()) || frame.generation() != context.generation()
                || !frame.sessionId().equals(context.sessionId()) || frame.sessionEpoch() != context.sessionEpoch()
                || !frame.connectionId().equals(context.connectionId()) || !frame.capabilities().equals(context.capabilities())
                || frame.sequence() != context.expectedSequence()) throw invalid("active context");
        // Skew can tolerate a sender timestamp, but never slides any authority/key/operation expiry.
        if (frame.sentAt() > context.now() && frame.sentAt() - context.now() > context.clockSkewMillis()
                || context.now() >= frame.expiresAt() || context.now() < key.validFrom()
                || frame.sentAt() < key.validFrom() || frame.sentAt() >= key.validUntil()
                || frame.expiresAt() > key.validUntil() || frame.expiresAt() > context.authorityExpiresAt()) {
            throw invalid("deadline");
        }
        try {
            Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format");
            verifier.initVerify(key.key());
            verifier.update(signingBytes(frame));
            if (!verifier.verify(decodeBase64(frame.authentication().signature(), 96, false))) throw invalid("signature");
            return frame;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid control frame signature", failure);
        }
    }

    /** Sign an otherwise complete frame. The input signature is replaced, never trusted. */
    public static Frame sign(Frame frame, KeyFamily family, PrivateKey key) throws GeneralSecurityException {
        validate(frame, false);
        if (family != frame.direction().keyFamily) throw invalid("signing key family");
        p384(key);
        Signature signer = Signature.getInstance("SHA384withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(signingBytes(frame));
        return new Frame(frame.version(), frame.type(), frame.id(), frame.sequence(), frame.direction(), frame.audience(),
                frame.instanceId(), frame.generation(), frame.sessionId(), frame.sessionEpoch(), frame.connectionId(),
                frame.capabilities(), frame.sentAt(), frame.expiresAt(), frame.payload(), frame.payloadSha256(),
                new Authentication(SCHEME, frame.authentication().keyId(), ProviderCrypto.base64(signer.sign())));
    }

    public static byte[] signingBytes(Frame frame) {
        validate(frame, false);
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (Object value : new Object[]{DOMAIN, 1, SCHEME, frame.direction().wire(), frame.audience(), frame.type(),
                frame.id(), frame.sequence(), frame.instanceId(), frame.generation(), frame.sessionId(), frame.sessionEpoch(),
                frame.connectionId(), frame.capabilities(), frame.sentAt(), frame.expiresAt(), frame.authentication().keyId(),
                frame.payloadSha256()}) {
            if (value instanceof String text) {
                values.add(ProviderCrypto.quote(text));
            } else if (value instanceof List<?> list) {
                StringJoiner array = new StringJoiner(",", "[", "]");
                list.forEach(item -> array.add(ProviderCrypto.quote((String) item)));
                values.add(array.toString());
            } else {
                values.add(value.toString());
            }
        }
        return values.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String encode(Frame frame) {
        validate(frame, true);
        JsonObject object = new JsonObject();
        object.addProperty("version", frame.version());
        object.addProperty("type", frame.type());
        object.addProperty("id", frame.id());
        object.addProperty("sequence", frame.sequence());
        object.addProperty("direction", frame.direction().wire());
        object.addProperty("audience", frame.audience());
        object.addProperty("instanceId", frame.instanceId());
        object.addProperty("generation", frame.generation());
        object.addProperty("sessionId", frame.sessionId());
        object.addProperty("sessionEpoch", frame.sessionEpoch());
        object.addProperty("connectionId", frame.connectionId());
        JsonArray capabilities = new JsonArray();
        frame.capabilities().forEach(capabilities::add);
        object.add("capabilities", capabilities);
        object.addProperty("sentAt", frame.sentAt());
        object.addProperty("expiresAt", frame.expiresAt());
        object.addProperty("payload", frame.payload());
        object.addProperty("payloadSha256", frame.payloadSha256());
        JsonObject authentication = new JsonObject();
        authentication.addProperty("scheme", frame.authentication().scheme());
        authentication.addProperty("keyId", frame.authentication().keyId());
        authentication.addProperty("signature", frame.authentication().signature());
        object.add("authentication", authentication);
        String wire = object.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) throw invalid("frame size");
        return wire;
    }

    public static String payloadDigest(byte[] payload) {
        try {
            return ProviderCrypto.base64(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonObject readObject(JsonReader reader, boolean authentication) throws IOException {
        Set<String> allowed = authentication ? AUTH_FIELDS : FIELDS;
        Set<String> seen = new HashSet<>();
        if (reader.peek() != JsonToken.BEGIN_OBJECT) throw invalid("object");
        reader.beginObject();
        JsonObject object = new JsonObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!allowed.contains(name) || !seen.add(name)) throw invalid("duplicate or unknown field");
            if (!authentication && NUMBER_FIELDS.contains(name)) {
                if (reader.peek() != JsonToken.NUMBER) throw invalid("number field");
                String token = reader.nextString();
                if (!token.matches("0|[1-9][0-9]{0,15}")) throw invalid("integer token");
                long value = Long.parseLong(token);
                safe(value, false);
                object.addProperty(name, value);
            } else if (!authentication && name.equals("authentication")) {
                object.add(name, readObject(reader, true));
            } else if (!authentication && name.equals("capabilities")) {
                if (reader.peek() != JsonToken.BEGIN_ARRAY) throw invalid("capabilities");
                reader.beginArray();
                JsonArray values = new JsonArray();
                while (reader.hasNext()) {
                    if (values.size() >= CAPABILITIES.size() || reader.peek() != JsonToken.STRING) throw invalid("capabilities");
                    values.add(reader.nextString());
                }
                reader.endArray();
                object.add(name, values);
            } else {
                if (reader.peek() != JsonToken.STRING) throw invalid("string field");
                object.addProperty(name, reader.nextString());
            }
        }
        reader.endObject();
        if (!seen.equals(allowed)) throw invalid("missing field");
        return object;
    }

    private static void validate(Frame frame, boolean requireSignature) {
        if (frame.version() != 1 || frame.direction() == null || frame.authentication() == null
                || !SCHEME.equals(frame.authentication().scheme())) throw invalid("version or scheme");
        if (!(frame.direction() == Direction.HOST_TO_PROVIDER ? HOST_TYPES : PROVIDER_TYPES).contains(frame.type())) {
            throw invalid("frame type or direction");
        }
        opaque(frame.id());
        opaque(frame.sessionId());
        opaque(frame.connectionId());
        identifier(frame.instanceId());
        identifier(frame.authentication().keyId());
        safe(frame.sequence(), true);
        safe(frame.generation(), true);
        safe(frame.sessionEpoch(), true);
        safe(frame.sentAt(), false);
        safe(frame.expiresAt(), false);
        if (frame.expiresAt() <= frame.sentAt() || frame.expiresAt() - frame.sentAt() > MAX_FRAME_TTL_MILLIS) {
            throw invalid("frame lifetime");
        }
        if (frame.audience() == null || frame.audience().length() > 2048
                || URI.create(frame.audience()).getPort() > 65535
                || !frame.audience().equals(ProviderCrypto.origin(URI.create(frame.audience())))) {
            throw invalid("canonical audience");
        }
        List<String> capabilities = frame.capabilities();
        if (!capabilities.contains("request-response") || capabilities.size() > CAPABILITIES.size()) throw invalid("capabilities");
        String previous = "";
        for (String capability : capabilities) {
            if (!CAPABILITIES.contains(capability) || capability.compareTo(previous) <= 0) throw invalid("capabilities");
            previous = capability;
        }
        if ((capabilities.contains("assisted-gameplay") || capabilities.contains("assisted-diagnostic"))
                && !capabilities.contains("addressed")) throw invalid("addressed capability");
        if (frame.type().equals("state.desired") && !capabilities.contains("addressed")) throw invalid("addressed desired state");
        if (frame.type().startsWith("assisted.") && !capabilities.contains("assisted-gameplay")
                || frame.type().startsWith("diagnostic.") && !capabilities.contains("assisted-diagnostic")) {
            throw invalid("unselected capability");
        }
        byte[] payload = decodeBase64(frame.payload(), MAX_PAYLOAD_BYTES, true);
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload));
        } catch (CharacterCodingException failure) {
            throw invalid("payload UTF-8");
        }
        if (decodeBase64(frame.payloadSha256(), 32, false).length != 32
                || !payloadDigest(payload).equals(frame.payloadSha256())) throw invalid("payload digest");
        if (requireSignature && decodeBase64(frame.authentication().signature(), 96, false).length != 96) {
            throw invalid("signature encoding");
        }
    }

    private static byte[] decodeBase64(String value, int maxBytes, boolean emptyAllowed) {
        if (value == null || value.length() > (maxBytes * 4L + 2) / 3
                || !(emptyAllowed ? value.matches("[A-Za-z0-9_-]*") : value.matches("[A-Za-z0-9_-]+"))) {
            throw invalid("base64url size or alphabet");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length > maxBytes || !ProviderCrypto.base64(decoded).equals(value)) throw invalid("canonical base64url");
        return decoded;
    }

    private static void p384(java.security.Key key) {
        if (!(key instanceof ECKey ec)) throw invalid("P-384 key");
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp384r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class), actual = ec.getParams();
            if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator())
                    || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor()) {
                throw invalid("P-384 key");
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid P-384 key", failure);
        }
    }

    private static String string(JsonObject object, String key) { return object.get(key).getAsString(); }
    private static void safe(long value, boolean positive) {
        if (value < (positive ? 1 : 0) || value > MAX_SAFE_INTEGER) throw invalid("safe integer");
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) throw invalid("identifier");
    }
    private static void opaque(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) throw invalid("opaque identifier");
    }
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid control frame " + field);
    }
}
