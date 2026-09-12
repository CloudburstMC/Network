package org.cloudburstmc.netty.util.nethernet;

import java.security.PublicKey;
import java.util.ArrayList;
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
