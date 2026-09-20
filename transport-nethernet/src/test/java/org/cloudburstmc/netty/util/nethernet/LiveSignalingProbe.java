/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.util.nethernet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends a self signed offer at a running signaling endpoint, so the server side of a join can be
 * exercised without a game client. Only useful against a host configured for {@link TokenTrust#ANY}.
 */
@EnabledIfEnvironmentVariable(named = "PROBE_SIGNALING", matches = ".+")
class LiveSignalingProbe {

    private static final String FINGERPRINT = "a=fingerprint:sha-256 "
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void sendsAnOffer() throws Exception {
        String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\n"
                + "a=ice-ufrag:probe\r\na=ice-pwd:probeprobeprobeprobeprobe\r\n" + FINGERPRINT + "\r\n"
                + "a=setup:actpass\r\na=mid:0\r\na=sctp-port:5000\r\n"
                + "a=candidate:1 1 udp 2130706431 127.0.0.1 50000 typ host\r\n";

        String offer = OperatorIdentity.generate("https://authorization.franchise.minecraft-services.net/")
                .forPlayer("2535000000000000", "Probe").withAssertion(sdp);

        String target = System.getenv("PROBE_SIGNALING");
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
}
