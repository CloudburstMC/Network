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

import org.cloudburstmc.netty.channel.nethernet.signaling.ClientInfo;
import org.jose4j.jwt.JwtClaims;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The validated identity of a player attempting to join.
 * <p>
 * How much of this can be trusted depends on the {@link TokenTrust} the offer was validated with.
 * Under {@link TokenTrust#MINECRAFT_AUTH} the token is Xbox issued, so the claims are attested.
 * Under {@link TokenTrust#ANY} the peer signed its own token and every claim below is self asserted,
 * with only {@link #clientPublicKey()} bound to a key the peer had to hold. The client info is self
 * reported under any trust.
 *
 * @param xuid          The Xbox user ID of the player, self asserted under {@link TokenTrust#ANY}
 * @param displayName   The Xbox gamertag of the player, self asserted under {@link TokenTrust#ANY}
 * @param networkId     The Network ID the player is joining with
 * @param remoteAddress The address the join request came from
 * @param client        What the client said about itself in the join request, or null if it said
 *                      nothing usable
 * @param claims        The full set of validated JWT claims, for anything not surfaced above
 */
public record PlayerInfo(String xuid, String displayName, String networkId, InetSocketAddress remoteAddress,
                         @Nullable ClientInfo client, JwtClaims claims) {

    /**
     * The key the peer proved it holds, from the token's {@code cpk} claim.
     * <p>
     * Nothing below the transport ties this to whatever identity your protocol carries afterwards.
     * If your login step presents its own signed identity, compare its key to this one and reject a
     * mismatch, or a peer can present an identity it captured elsewhere and did not sign for.
     *
     * @return The peer's public key
     * @throws GeneralSecurityException If the claim is missing or is not an EC public key
     */
    public PublicKey clientPublicKey() throws GeneralSecurityException {
        String cpk = claims.getClaimValueAsString("cpk");
        if (cpk == null) {
            throw new GeneralSecurityException("The token carries no cpk claim");
        }
        try {
            return KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(cpk)));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("The cpk claim is not valid base64", e);
        }
    }
}
