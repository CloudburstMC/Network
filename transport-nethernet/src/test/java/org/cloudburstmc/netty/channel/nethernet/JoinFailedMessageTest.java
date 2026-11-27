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

import org.junit.jupiter.api.Test;

import static org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel.JoinProgress.ICE_CONNECTED;
import static org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel.JoinProgress.NONE;
import static org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel.JoinProgress.PEER_CONNECTED;
import static org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel.joinFailedMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinFailedMessageTest {
    @Test
    void namesThePortAPlayerCouldNotReach() {
        assertEquals("Steve could not connect over NetherNet: no network path to this server was found. Their device "
                        + "needs to reach UDP port 19132 on one of the addresses this server advertises.",
                joinFailedMessage("Steve", NONE, 19132, false));
        assertEquals("A player could not connect over NetherNet: no network path to this server was found. Their device "
                        + "needs to reach this server over UDP on one of the addresses it advertises.",
                joinFailedMessage(null, NONE, 0, false));
    }

    @Test
    void pointsAtTheRelaysWhenTheMediaIsRelayed() {
        assertEquals("Steve could not connect over NetherNet: nothing arrived through the PROXY protocol relays this "
                        + "server advertises. Each relay has to forward to UDP port 19132 and be listed.",
                joinFailedMessage("Steve", NONE, 19132, true));
    }

    /** ICE connecting only means this side's checks came back, not that the device's own got through. */
    @Test
    void doesNotCallAStallAfterIceAMissingPath() {
        assertEquals("Steve could not connect over NetherNet: their device answered this server, but the connection "
                        + "stalled after that. A firewall or a hosting provider's DDoS filter may be dropping other "
                        + "packets their device sends to UDP port 19132.",
                joinFailedMessage("Steve", ICE_CONNECTED, 19132, false));
        assertEquals("Steve could not connect over NetherNet: their device answered this server, but the connection "
                        + "stalled after that. A firewall or a hosting provider's DDoS filter may be dropping other "
                        + "packets their device sends through the PROXY protocol relays.",
                joinFailedMessage("Steve", ICE_CONNECTED, 19132, true));
    }

    @Test
    void saysWhenThePlayerGotAsFarAsThisServer() {
        assertEquals("Steve reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage("Steve", PEER_CONNECTED, 19132, false));
    }

    @Test
    void aClaimedNameCannotForgeLogLines() {
        assertEquals("Steve[00:00 INFO] Op granted reached this server over NetherNet, but the connection did not "
                        + "finish setting up.",
                joinFailedMessage("Steve\n[00:00 INFO] Op granted", PEER_CONNECTED, 19132, false));
        assertEquals("A player reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage(" \r\n ", PEER_CONNECTED, 19132, false));
        assertEquals("x".repeat(32) + " reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage("x".repeat(40), PEER_CONNECTED, 19132, false));
    }
}
