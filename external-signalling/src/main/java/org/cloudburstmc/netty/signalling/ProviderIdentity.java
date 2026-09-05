package org.cloudburstmc.netty.signalling;

import com.google.gson.JsonObject;
import java.net.URI;
import java.security.KeyPair;

/** Bootstrap controllers need the public key before issuing a key-bound attachment grant. */
public final class ProviderIdentity {
    private ProviderIdentity() {}
    public static JsonObject initialize(ProviderStateStore store, URI provider) throws Exception {
        String origin = ProviderCrypto.origin(provider); JsonObject state = store.read();
        if (state.has("provider") && !origin.equals(state.get("provider").getAsString())) throw new IllegalArgumentException("Provider state mismatch");
        if (!state.has("privateKey")) {
            KeyPair pair = ProviderCrypto.generate(); state.addProperty("provider", origin); state.addProperty("privateKey", ProviderCrypto.base64(pair.getPrivate().getEncoded())); state.add("publicKeyJwk", ProviderCrypto.publicJwk(pair.getPublic())); state.addProperty("generation", 0); state.addProperty("sequence", 0); store.write(state);
        }
        return state.getAsJsonObject("publicKeyJwk").deepCopy();
    }
}
