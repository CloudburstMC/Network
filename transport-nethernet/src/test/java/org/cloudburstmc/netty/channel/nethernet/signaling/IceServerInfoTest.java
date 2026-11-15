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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.signaling.IceServerInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IceServerInfoTest {

    @Test
    void carriesTheCredentialsIntoTheUri() {
        List<URI> uris = new IceServerInfo("relayuser", "s3cret", List.of("turn:turn.example:3478")).toUris();

        // libdatachannel reads them out of the authority
        assertEquals(List.of(URI.create("turn:relayuser:s3cret@turn.example:3478")), uris);
    }

    @Test
    void keepsTheSecretOutOfItsOwnForm() {
        String printed = new IceServerInfo("relayuser", "s3cret",
                List.of("turn:turn.example:3478", "turn:someone:hunter2@relay.example:3478")).toString();

        assertFalse(printed.contains("s3cret"));
        assertFalse(printed.contains("hunter2"));
        assertFalse(printed.contains("relayuser"));
        assertFalse(printed.contains("someone"));
        // Still worth reading
        assertTrue(printed.contains("turn.example:3478"));
        assertTrue(printed.contains("relay.example:3478"));
    }

    @Test
    void leavesAStunServerAlone() {
        // No credentials to lose, and the host is the whole point of the line
        assertEquals("IceServerInfo[urls=[stun:stun.example:3478], username=]",
                new IceServerInfo("", "", List.of("stun:stun.example:3478")).toString());
    }
}
