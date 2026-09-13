package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetOfferValidator;
import org.jose4j.http.Get;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.HttpsJwks;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.EllipticCurves;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies the auth-service token and detached fingerprint JWS described by guide section 5.1.
 * Validation is synchronous and can fetch trusted keys on a cache miss. Call it outside
 * I/O loops; NetherNetHttpSignaling provides a bounded executor for this purpose.
 */
public final class ClientAssertionValidator implements NetherNetOfferValidator {
    private static final String ISSUER = "https://authorization.franchise.minecraft-services.net/";
    private static final String AUDIENCE = "api://auth-minecraft-services/multiplayer";
    private static final String IDENTITY_PREFIX = "a=identity:";
    private static final String FINGERPRINT_PREFIX = "a=fingerprint:";
    private final JwtConsumer tokenConsumer;

    /** Uses Minecraft's trusted authorization service with a shared, lazily fetched JWKS cache. */
    public ClientAssertionValidator() {
        this.tokenConsumer = MinecraftTrust.CONSUMER;
    }

    /**
     * Uses a private issuer's JWKS endpoint, fetched lazily and cached like the Minecraft one.
     * The URL must come from trusted configuration, never from the offer or JWT headers.
     *
     * @param jwksUrl HTTPS location of the issuer's key set
     * @param issuer required token issuer
     * @param audience required token audience
     */
    public ClientAssertionValidator(String jwksUrl, String issuer, String audience) {
        this.tokenConsumer = jwksConsumer(Objects.requireNonNull(jwksUrl, "jwksUrl"), issuer, audience);
    }

    /**
     * Uses an explicitly trusted issuer key, for private issuers or deterministic verification.
     * The key must come from trusted configuration, never from the offer or JWT headers.
     *
     * @param issuerKey trusted token-signing key
     * @param issuer required token issuer
     * @param audience required token audience
     */
    public ClientAssertionValidator(PublicKey issuerKey, String issuer, String audience) {
        this.tokenConsumer = consumer(issuer, audience)
                .setVerificationKey(Objects.requireNonNull(issuerKey, "issuerKey"))
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,
                        AlgorithmIdentifiers.RSA_USING_SHA256, AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384)
                .build();
    }

    private static JwtConsumerBuilder consumer(String issuer, String audience) {
        return new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireSubject()
                .setExpectedIssuer(Objects.requireNonNull(issuer, "issuer"))
                .setExpectedAudience(true, Objects.requireNonNull(audience, "audience"))
                .setAllowedClockSkewInSeconds(30);
    }

    @Override
    public ClientIdentity validate(String offerSdp) throws GeneralSecurityException {
        try {
            ParsedOffer offer = parseOffer(offerSdp);
            String json = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getDecoder().decode(offer.identity()))).toString();
            Map<String, Object> envelope = JsonUtil.parseJson(json);
            if (!(envelope.get("idp") instanceof Map<?, ?> idp)
                    || !"default".equals(idp.get("protocol"))
                    || !(idp.get("domain") instanceof String domain) || domain.isEmpty()) {
                throw new GeneralSecurityException("Invalid client identity provider metadata");
            }
            Map<String, Object> assertion = JsonUtil.parseJson(string(envelope, "assertion"));
            String compact = string(assertion, "fingerprints");
            String[] parts = compact.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty() || !parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new GeneralSecurityException("Expected a detached fingerprint JWS");
            }
            JsonWebSignature signature = new JsonWebSignature();
            signature.setCompactSerialization(compact);
            signature.setAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT,
                    AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384));
            if (!AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384.equals(signature.getAlgorithmHeaderValue())
                    || Boolean.FALSE.equals(signature.getHeaders().getObjectHeaderValue("b64"))) {
                throw new GeneralSecurityException("Unsupported fingerprint signature encoding");
            }

            // Provider metadata, jku and x5u never select the token's trust anchor.
            JwtClaims claims = tokenConsumer.processToClaims(string(assertion, "token"));
            PublicKey publicKey = clientPublicKey(claims.getClaimValue("cpk"));
            signature.setKey(publicKey);
            signature.setPayload(offer.fingerprints());
            if (!signature.verifySignature()) {
                throw new GeneralSecurityException("Client fingerprint signature mismatch");
            }
            return new ClientIdentity(publicKey, claims.getClaimsMap());
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            // A key set that cannot be fetched is not a bad assertion; report it separately so
            // the HTTP layer can answer 503 and operators can tell an outage from forgeries.
            IOException unreachable = trustSourceFailure(e);
            if (unreachable != null) {
                throw new TrustSourceUnavailableException("Trust source unavailable: " + unreachable.getMessage());
            }
            // JWT library exceptions can contain the bearer token; do not expose it in error text.
            throw new GeneralSecurityException("Client assertion validation failed");
        }
    }

    private static IOException trustSourceFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException io) {
                return io;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    private static String string(Map<String, Object> object, String key) throws GeneralSecurityException {
        if (!(object.get(key) instanceof String value) || value.isEmpty()) {
            throw new GeneralSecurityException("Missing or invalid assertion field: " + key);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static PublicKey clientPublicKey(Object value) throws Exception {
        PublicKey key;
        if (value instanceof String encoded) {
            key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } else if (value instanceof Map<?, ?> map && !map.containsKey("d")) {
            key = (PublicKey) JsonWebKey.Factory.newJwk((Map<String, Object>) map).getKey();
        } else {
            throw new GeneralSecurityException("Missing or invalid client public key");
        }
        if (!(key instanceof ECPublicKey ec)
                || !ec.getParams().getCurve().equals(EllipticCurves.P384.getCurve())
                || !ec.getParams().getGenerator().equals(EllipticCurves.P384.getGenerator())
                || !ec.getParams().getOrder().equals(EllipticCurves.P384.getOrder())
                || ec.getParams().getCofactor() != EllipticCurves.P384.getCofactor()) {
            throw new GeneralSecurityException("Client public key must use P-384");
        }
        return key;
    }

    private static ParsedOffer parseOffer(String sdp) throws GeneralSecurityException {
        if (sdp == null || sdp.length() > 1 << 20) {
            throw new GeneralSecurityException("Invalid offer size");
        }
        String identity = null;
        boolean media = false;
        List<String> fingerprints = new ArrayList<>();
        for (String line : sdp.split("\\r?\\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.length() < 2 || line.charAt(1) != '=' || line.charAt(0) < 'a'
                    || line.charAt(0) > 'z' || line.indexOf('\r') >= 0) {
                throw new GeneralSecurityException("Malformed SDP line");
            }
            if (line.startsWith("m=")) media = true;
            if (line.startsWith(IDENTITY_PREFIX)) {
                if (identity != null || media) {
                    throw new GeneralSecurityException("Expected one session-level client identity");
                }
                identity = line.substring(IDENTITY_PREFIX.length());
            } else if (line.startsWith(FINGERPRINT_PREFIX)) {
                String[] fields = line.substring(FINGERPRINT_PREFIX.length()).trim().split("[ \\t]+", -1);
                if (fields.length != 2 || !fields[0].matches("[A-Za-z0-9-]+")
                        || !isHexDigest(fields[1])) {
                    throw new GeneralSecurityException("Malformed SDP fingerprint");
                }
                fingerprints.add("{\"algorithm\":\"" + fields[0] + "\",\"digest\":\"" + fields[1] + "\"}");
            }
        }
        if (identity == null || identity.isEmpty() || fingerprints.isEmpty()) {
            throw new GeneralSecurityException("Offer requires a client identity and DTLS fingerprints");
        }
        return new ParsedOffer(identity, "{\"fingerprint\":[" + String.join(",", fingerprints) + "]}");
    }

    private static boolean isHexDigest(String value) {
        if (value.length() % 3 != 2) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i % 3 == 2 ? c != ':' : !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return true;
    }

    private record ParsedOffer(String identity, String fingerprints) { }

    private static JwtConsumer jwksConsumer(String jwksUrl, String issuer, String audience) {
        Get http = new Get();
        http.setConnectTimeout(5000);
        http.setReadTimeout(5000);
        http.setRetries(0);
        http.setResponseBodySizeLimit(64 * 1024);
        HttpsJwks keys = new HttpsJwks(jwksUrl);
        keys.setSimpleHttpGet(http);
        return consumer(issuer, audience)
                .setVerificationKeyResolver(new HttpsJwksVerificationKeyResolver(keys))
                .setJwsAlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.RSA_USING_SHA256)
                .build();
    }

    private static final class MinecraftTrust {
        private static final JwtConsumer CONSUMER = jwksConsumer(ISSUER + ".well-known/keys", ISSUER, AUDIENCE);
    }
}
