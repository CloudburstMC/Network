package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;

/** Draft opt-in machine rotation body. Current-key authentication, ID uniqueness and commit remain provider work. */
public final class ControlRotationCodec {
    public static final int MAX_BODY_BYTES = 4096;
    public record Context(String audience, String instanceId, long generation, String oldKeyId, String idempotencyKey) {
        public Context {
            ControlOrigin.requireCanonical(audience); ControlJson.identifier(instanceId); ControlJson.safe(generation, true);
            ControlJson.identifier(oldKeyId); ControlJson.opaque(idempotencyKey);
        }
    }
    /** Public JWK is retained as immutable JSON; no private key is carried in the operation body. */
    public record Body(String newKeyId, String publicKeyJwk, String proof) { }
    private ControlRotationCodec() { }

    public static Body create(String newKeyId, KeyPair candidate, Context context) throws GeneralSecurityException {
        Body unsigned = new Body(newKeyId, ProviderCrypto.publicJwk(candidate.getPublic()).toString(), "");
        Body signed = new Body(newKeyId, unsigned.publicKeyJwk(), ControlProof.sign(signingBytes(unsigned, context), candidate.getPrivate()));
        verify(encode(signed), context);
        return signed;
    }

    public static Body decode(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_BODY_BYTES);
        ControlJson.fields(value, "version", "newKeyId", "publicKeyJwk", "proof"); ControlJson.version(value);
        Body body = new Body(ControlJson.string(value, "newKeyId"), ControlJson.object(value, "publicKeyJwk").toString(), ControlJson.string(value, "proof"));
        validate(body, true); return body;
    }

    public static Body verify(String wire, Context context) {
        Body body = decode(wire);
        try {
            Signature verifier = Signature.getInstance("SHA384withECDSAinP1363Format");
            verifier.initVerify(ProviderCrypto.publicKey(publicJwk(body)));
            verifier.update(signingBytes(body, context));
            if (!verifier.verify(ControlJson.base64(body.proof(), 96, false))) throw ControlJson.invalid("rotation possession proof");
            return body;
        } catch (GeneralSecurityException failure) { throw new IllegalArgumentException("Invalid control rotation proof", failure); }
    }

    public static byte[] signingBytes(Body body, Context context) {
        validate(body, false);
        if (body.newKeyId().equals(context.oldKeyId())) throw ControlJson.invalid("reused rotation key ID");
        return ControlProof.array("nethernet-control-machine-rotation-v1", 1, context.audience(), context.instanceId(), context.generation(),
                context.oldKeyId(), body.newKeyId(), ProviderCrypto.thumbprint(publicJwk(body)), context.idempotencyKey());
    }

    public static String encode(Body body) {
        validate(body, true);
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("newKeyId", body.newKeyId());
        value.add("publicKeyJwk", publicJwk(body)); value.addProperty("proof", body.proof());
        String wire = value.toString();
        if (wire.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) throw ControlJson.invalid("rotation size");
        return wire;
    }

    private static JsonObject publicJwk(Body body) {
        JsonObject jwk = ControlJson.parse(body.publicKeyJwk(), 1024);
        ControlJson.fields(jwk, "crv", "kty", "x", "y");
        if (!"EC".equals(ControlJson.string(jwk, "kty")) || !"P-384".equals(ControlJson.string(jwk, "crv"))
                || ControlJson.base64(ControlJson.string(jwk, "x"), 48, false).length != 48
                || ControlJson.base64(ControlJson.string(jwk, "y"), 48, false).length != 48) throw ControlJson.invalid("rotation public JWK");
        try { ProviderCrypto.publicKey(jwk); }
        catch (GeneralSecurityException failure) { throw new IllegalArgumentException("Invalid control rotation public JWK", failure); }
        return jwk;
    }
    private static void validate(Body body, boolean signature) {
        ControlJson.opaque(body.newKeyId()); publicJwk(body);
        if (signature && ControlJson.base64(body.proof(), 96, false).length != 96) throw ControlJson.invalid("rotation proof size");
    }
}
