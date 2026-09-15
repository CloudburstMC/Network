package org.cloudburstmc.netty.util.nethernet;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public record Identity(Idp idp, Assertion assertion) {
    private static Gson gson = new Gson();

    public static Identity fromJson(String identityString) {
        return new Identity(gson.fromJson(identityString, Raw.class));
    }

    public static Identity fromBase64(String identityString) {
        return Identity.fromJson(new String(Base64.getDecoder().decode(identityString.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));
    }

    public static Identity fromSdpOffer(String sdpOffer) {
        String prefix = "a=identity:";
        String identity =
                Arrays.stream(sdpOffer.split("\n")).filter(line -> line.startsWith(prefix)).findFirst().orElse(null);
        if (identity == null) {
            return null;
        }
        identity = identity.substring(prefix.length()).trim();
        return Identity.fromBase64(identity);
    }

    private Identity(Raw raw) {
        this(raw.idp(), gson.fromJson(raw.assertion(), Assertion.class));
    }

    private record Raw(Idp idp, String assertion) {
    }

    public record Idp(String domain, String protocol) {
    }

    public record Assertion(String token, String fingerprints) {
    }

    public String toJson() {
        return gson.toJson(new Raw(idp, gson.toJson(assertion)));
    }

    public String toBase64() {
        return Base64.getEncoder().encodeToString(toJson().getBytes(StandardCharsets.UTF_8));
    }
}
