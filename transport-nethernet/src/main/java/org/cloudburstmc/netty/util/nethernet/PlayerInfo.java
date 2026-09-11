package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwt.JwtClaims;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The validated identity of a player attempting to join.
 *
 * @param xuid          The Xbox user ID of the player
 * @param displayName   The Xbox gamertag of the player
 * @param networkId     The Network ID the player is joining with
 * @param remoteAddress The address the join request came from
 * @param claims        The full set of validated JWT claims, for anything not surfaced above
 */
public record PlayerInfo(String xuid, String displayName, String networkId, InetSocketAddress remoteAddress,
                         JwtClaims claims) {

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
