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

import static org.cloudburstmc.netty.channel.nethernet.NetherNetServerChannel.joinFailedMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinFailedMessageTest {
    @Test
    void namesThePortAPlayerCouldNotReach() {
        assertEquals("Steve could not connect over NetherNet: no network path to this server was found. Their device "
                        + "needs to reach UDP port 19132 on one of the addresses this server advertises.",
                joinFailedMessage("Steve", false, 19132));
        assertEquals("A player could not connect over NetherNet: no network path to this server was found. Their device "
                        + "needs to reach this server over UDP on one of the addresses it advertises.",
                joinFailedMessage(null, false, 0));
    }

    @Test
    void saysWhenThePlayerGotAsFarAsThisServer() {
        assertEquals("Steve reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage("Steve", true, 19132));
    }

    @Test
    void aClaimedNameCannotForgeLogLines() {
        assertEquals("Steve[00:00 INFO] Op granted reached this server over NetherNet, but the connection did not "
                        + "finish setting up.",
                joinFailedMessage("Steve\n[00:00 INFO] Op granted", true, 19132));
        assertEquals("A player reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage(" \r\n ", true, 19132));
        assertEquals("x".repeat(32) + " reached this server over NetherNet, but the connection did not finish setting up.",
                joinFailedMessage("x".repeat(40), true, 19132));
    }
}
