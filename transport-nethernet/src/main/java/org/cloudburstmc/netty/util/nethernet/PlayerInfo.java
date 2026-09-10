package org.cloudburstmc.netty.util.nethernet;

import org.jose4j.jwt.JwtClaims;

import java.net.InetSocketAddress;

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
}
