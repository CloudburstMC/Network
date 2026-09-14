package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** Shared mechanics for separate draft proof domains; no key discovery or authorization lookup. */
final class ControlProof {
    private static final Set<String> CAPABILITIES = Set.of("addressed", "assisted-diagnostic", "assisted-gameplay", "request-response");
    private ControlProof() { }

    static byte[] array(Object... values) {
        StringJoiner result = new StringJoiner(",", "[", "]");
        for (Object value : values) {
            if (value instanceof String text) result.add(ProviderCrypto.quote(text));
            else if (value instanceof List<?> list) result.add(new String(array(list.toArray()), StandardCharsets.UTF_8));
            else result.add(String.valueOf(value));
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    static ControlFrameCodec.Authentication authentication(JsonObject object) {
        JsonObject auth = ControlJson.object(object, "authentication");
        ControlJson.fields(auth, "scheme", "keyId", "signature");
        return new ControlFrameCodec.Authentication(ControlJson.string(auth, "scheme"), ControlJson.string(auth, "keyId"), ControlJson.string(auth, "signature"));
    }

    static JsonObject authenticationObject(ControlFrameCodec.Authentication auth) {
        JsonObject object = new JsonObject();
        object.addProperty("scheme", auth.scheme());
        object.addProperty("keyId", auth.keyId());
        object.addProperty("signature", auth.signature());
        return object;
    }

    static void authentication(ControlFrameCodec.Authentication auth, boolean signature) {
        if (auth == null || !ControlFrameCodec.SCHEME.equals(auth.scheme())) throw ControlJson.invalid("proof scheme");
        ControlJson.identifier(auth.keyId());
        if (signature && ControlJson.base64(auth.signature(), 96, false).length != 96) throw ControlJson.invalid("signature length");
    }

    static void capabilities(String transport, List<String> selected) {
        if (!Set.of("websocket", "https").contains(transport) || selected == null
                || selected.size() > CAPABILITIES.size() || !selected.contains("request-response")) throw ControlJson.invalid("capabilities");
        String previous = "";
        for (String value : selected) {
            if (!CAPABILITIES.contains(value) || value.compareTo(previous) <= 0) throw ControlJson.invalid("capabilities");
            previous = value;
        }
        if ((selected.contains("assisted-gameplay") || selected.contains("assisted-diagnostic")) && !selected.contains("addressed")
                || transport.equals("https") && !selected.equals(List.of("request-response"))) throw ControlJson.invalid("transport capabilities");
    }

    static JsonArray capabilitiesObject(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static void lifetime(long sentAt, long expiresAt) {
        ControlJson.safe(sentAt, false);
        ControlJson.safe(expiresAt, false);
        if (expiresAt <= sentAt || expiresAt - sentAt > ControlFrameCodec.MAX_FRAME_TTL_MILLIS) throw ControlJson.invalid("proof lifetime");
    }

    static void contextTime(long now, long authorityExpiresAt, long skew) {
        ControlJson.safe(now, false);
        ControlJson.safe(authorityExpiresAt, false);
        if (skew < 0 || skew > ControlFrameCodec.MAX_CLOCK_SKEW_MILLIS) throw ControlJson.invalid("clock skew");
    }

    static void path(String path) {
        // The trusted route must still compare byte-for-byte. Do not normalize a signed request target.
        if (path == null || path.length() > 2048 || !path.startsWith("/") || path.startsWith("//")) throw ControlJson.invalid("request target");
        for (int i = 0; i < path.length(); i++) {
            char value = path.charAt(i);
            if (value < 0x21 || value > 0x7e || value == '#' || value == '\\') throw ControlJson.invalid("request target");
            if (value == '%' && (i + 2 >= path.length() || Character.digit(path.charAt(i + 1), 16) < 0
                    || Character.digit(path.charAt(i + 2), 16) < 0)) throw ControlJson.invalid("request target escape");
        }
    }

    static String sign(byte[] bytes, PrivateKey key) throws GeneralSecurityException {
        if (!(key instanceof ECKey ec)) throw ControlJson.invalid("P-384 private key");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp384r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class), actual = ec.getParams();
        if (!expected.getCurve().equals(actual.getCurve()) || !expected.getGenerator().equals(actual.getGenerator())
                || !expected.getOrder().equals(actual.getOrder()) || expected.getCofactor() != actual.getCofactor()) throw ControlJson.invalid("P-384 private key");
        Signature signer = Signature.getInstance("SHA384withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(bytes);
        return ProviderCrypto.base64(signer.sign());
    }

    static void verify(byte[] bytes, ControlFrameCodec.Authentication auth, ControlFrameCodec.KeyFamily family,
                       ControlFrameCodec.VerificationKey key, long sentAt, long expiresAt, long now,
                       long authorityExpiresAt, long skew) {
        contextTime(now, authorityExpiresAt, skew);
        if (key == null || key.family() != family || !key.keyId().equals(auth.keyId())) throw ControlJson.invalid("trusted proof key");
        if (sentAt > now && sentAt - now > skew || now >= expiresAt || now < key.validFrom()
                || sentAt < key.validFrom() || expiresAt > key.validUntil() || expiresAt > authorityExpiresAt) throw ControlJson.invalid("proof deadline");
        try {
            Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format");
            verifier.initVerify(key.key());
            verifier.update(bytes);
            if (!verifier.verify(ControlJson.base64(auth.signature(), 96, false))) throw ControlJson.invalid("proof signature");
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid control proof signature", failure);
        }
    }
}
