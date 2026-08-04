package org.cloudburstmc.netty.util.nethernet;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record Identity(Idp idp, Assertion assertion) {
    private static Gson gson = new Gson();

    private record Raw(Idp idp, String assertion) {}

    public record Idp(String domain, String protocol) {}

    public record Assertion(String token, String fingerprints) {}

    public String toJson() {
        return gson.toJson(new Raw(idp, gson.toJson(assertion)));
    }

    public String toBase64() {
        return Base64.getEncoder().encodeToString(toJson().getBytes(StandardCharsets.UTF_8));
    }
}
