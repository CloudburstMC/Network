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

import org.jose4j.jwt.JwtClaims;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assertion a proxy presents when it connects out for a player is verified by the same code a
 * server runs on a retail client's offer, so that is the contract these hold it to.
 */
class PlayerIdentityTest {
    private static final String FINGERPRINT =
            "BA:02:D4:8A:F3:5B:99:20:E5:A6:89:74:36:AC:4F:55:EF:AB:EC:9B:9C:3F:7A:A1:B3:40:B9:A7:E1:1E:B1:80";

    /** Shaped like a real client offer. */
    private static String offer(String fingerprint) {
        return String.join("\r\n",
                "v=0",
                "o=- 1 2 IN IP4 127.0.0.1",
                "s=-",
                "t=0 0",
                "a=group:BUNDLE 0",
                "m=application 38992 UDP/DTLS/SCTP webrtc-datachannel",
                "c=IN IP4 172.20.0.1",
                "a=ice-ufrag:S2gu",
                "a=fingerprint:sha-256 " + fingerprint,
                "a=setup:actpass",
                "a=mid:0") + "\r\n";
    }

    @Test
    void carriesThePlayerUnderTheOperatorsKeyAndName() throws Exception {
        OperatorIdentity operator = OperatorIdentity.generate("proxy.test");
        String signed = operator.forPlayer("1234567891234678", "Tester").withAssertion(offer(FINGERPRINT));

        JwtClaims claims = IdentityUtils.validateSdp(signed, TokenTrust.ANY);
        assertEquals("1234567891234678", claims.getClaimValueAsString("xid"));
        assertEquals("Tester", claims.getClaimValueAsString("xname"));
        assertEquals("proxy.test", claims.getIssuer());
        assertTrue(claims.getExpirationTime().isAfter(claims.getIssuedAt()), "a player token has to expire");

        // The same key as the operator presents as a host, so a fleet is one identity either way
        String hostToken = Identity.fromSdpOffer(operator.withAssertion(offer(FINGERPRINT))).assertion().token();
        String hostClaims = new String(Base64.getUrlDecoder().decode(hostToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(hostClaims.contains("\"cpk\":\"" + claims.getClaimValueAsString("cpk") + "\""), hostClaims);
    }

    @Test
    void assertionsAreBoundToTheirOwnFingerprints() throws Exception {
        // Signed over one offer, presented with another, which is what a replay looks like
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("1234567891234678", "Tester");
        String signed = player.withAssertion(offer(FINGERPRINT));
        String line = signed.lines().filter(l -> l.startsWith("a=identity:")).findFirst().orElseThrow();
        String replayed = offer("AA:" + FINGERPRINT.substring(3)).replace("m=application", line + "\r\nm=application");

        assertThrows(Exception.class, () -> IdentityUtils.validateSdp(replayed, TokenTrust.ANY));
    }

    @Test
    void identityIsInsertedAheadOfTheFirstMediaLine() throws Exception {
        OperatorIdentity player = OperatorIdentity.generate("proxy.test").forPlayer("1", "Tester");
        String signed = player.withAssertion(offer(FINGERPRINT));
        assertTrue(signed.indexOf("a=identity:") < signed.indexOf("m=application"));
        assertEquals(1, signed.lines().filter(l -> l.startsWith("a=identity:")).count());
    }
}
