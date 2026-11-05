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

package org.cloudburstmc.netty.channel.nethernet;

/**
 * Where an address a NetherNet channel reports came from, see {@link SourcedAddress}.
 */
public enum AddressSource {
    /** The peer of the signaling itself: the connection an offer came over, or the endpoint a client dialed. */
    SIGNALING,
    /** Declared in a PROXY protocol header by a trusted proxy in front of signaling. */
    SIGNALING_PROXY_PROTOCOL,
    /**
     * Taken from X-Forwarded-For, sent by a trusted forwarder in front of signaling. The header names no port, so the
     * port is the forwarder's connection's.
     */
    SIGNALING_FORWARDED_FOR,
    /** The source of the LAN discovery datagram an offer came in, which anyone on the network can forge. */
    SIGNALING_LAN,
    /** Handed in by the host along with an offer, which says nothing of where it got it. */
    HOST,
    /**
     * The source of the first STUN check an NXS admission accepted. An assisted NXS join sees only the candidates the
     * client claims, so it has none.
     */
    NXS_ADMISSION,
    /** The remote side of the selected ICE pair, when no PROXY protocol relay is known to have brought it. */
    ICE,
    /** The remote side of the selected ICE pair, declared by a PROXY protocol relay on the ICE port. */
    ICE_PROXY_PROTOCOL,
    /** The selected ICE pair runs through what the peer says is one of its TURN relays, so this is that relay. */
    TURN,
    /** Nothing said where the peer is, as with Xbox signaling or an assisted NXS join, so this is the wildcard. */
    UNKNOWN
}
