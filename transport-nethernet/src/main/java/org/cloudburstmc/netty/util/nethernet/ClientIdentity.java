package org.cloudburstmc.netty.util.nethernet;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Authenticated token claims and the client key bound to the offer's DTLS fingerprints. */
public final class ClientIdentity {
    private final PublicKey publicKey;
    private final Map<String, Object> claims;

    /** Constructs the result of a custom validator after token and fingerprint verification. */
    public ClientIdentity(PublicKey publicKey, Map<String, Object> claims) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.claims = immutableClaims(claims);
    }

    /** Returns the client key whose fingerprint signature was verified. */
    public PublicKey getPublicKey() { return publicKey; }

    /** Returns an immutable snapshot of the verified token claims, including nested values. */
    public Map<String, Object> getClaims() { return claims; }

    /**
     * Returns the player's XUID from the {@code xid} claim, or null when the token carries none.
     * The claim name is the one observed in Minecraft-issued tokens; the guide does not name it.
     */
    public String getXuid() { return stringClaim("xid"); }

    /** Returns the player's display name from the {@code xname} claim, or null when the token carries none. */
    public String getDisplayName() { return stringClaim("xname"); }

    /**
     * Checks that a Bedrock Login chain is signed by the key that opened this transport,
     * which stops a login from riding on another player's authenticated connection.
     *
     * @param loginKey the public key the Login chain is signed with
     * @return null when the keys match, otherwise the reason they do not
     */
    public String loginKeyMismatch(PublicKey loginKey) {
        if (loginKey == null) {
            return "the login chain carries no key to bind to the transport identity";
        }
        if (!Arrays.equals(publicKey.getEncoded(), loginKey.getEncoded())) {
            return "the login chain is signed with a different key than the one that opened the transport";
        }
        return null;
    }

    private String stringClaim(String name) {
        return claims.get(name) instanceof String value && !value.isEmpty() ? value : null;
    }

    private static Map<String, Object> immutableClaims(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutable(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, immutable(entry)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(entry -> copy.add(immutable(entry)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
