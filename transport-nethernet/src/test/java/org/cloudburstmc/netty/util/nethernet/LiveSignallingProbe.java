package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Sends a self signed offer at a running signalling endpoint, so the server side of a join can be
 * exercised without a game client. Only useful against a host configured for {@link TokenTrust#ANY}.
 */
@EnabledIfEnvironmentVariable(named = "PROBE_SIGNALLING", matches = ".+")
class LiveSignallingProbe {

    private static final String FINGERPRINT = "a=fingerprint:sha-256 "
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void sendsAnOffer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = generator.generateKeyPair();

        JwtClaims claims = new JwtClaims();
        claims.setClaim("cpk", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        claims.setClaim("xid", "2535000000000000");
        claims.setClaim("xname", "Probe");
        claims.setAudience("api://auth-minecraft-services/multiplayer");
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture(5);

        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\n"
                + "a=ice-ufrag:probe\r\na=ice-pwd:probeprobeprobeprobeprobe\r\n" + FINGERPRINT + "\r\n"
                + "a=setup:actpass\r\na=mid:0\r\na=sctp-port:5000\r\n"
                + "a=candidate:1 1 udp 2130706431 127.0.0.1 50000 typ host\r\n";

        String[] detached = sign(pair, IdentityUtils.getCanonicalFingerprintJson(sdp)).split("\\.");
        Identity identity = new Identity(
                new Identity.Idp("https://authorization.franchise.minecraft-services.net/", "default"),
                new Identity.Assertion(sign(pair, claims.toJson()), detached[0] + ".." + detached[2]));
        String offer = sdp.replace("m=application", "a=identity:" + identity.toBase64() + "\r\nm=application");

        String target = System.getenv("PROBE_SIGNALLING");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target + "/v1/join/8888888888888888888"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(offer));

        // Honoured when the probe runs from a trusted proxy address, which is how a public peer
        // address is simulated. Leave it empty to let a real proxy in front of the endpoint set it.
        String forwardedFor = System.getenv().getOrDefault("PROBE_FORWARDED_FOR", "203.0.113.9");
        if (!forwardedFor.isBlank()) {
            request.header("X-Forwarded-For", forwardedFor);
        }

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());

        System.out.println("== status " + response.statusCode());
        System.out.println(response.body());
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(payload);
        jws.setKey(pair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        return jws.getCompactSerialization();
    }
}
