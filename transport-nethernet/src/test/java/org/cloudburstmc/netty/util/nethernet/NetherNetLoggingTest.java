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

import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetherNetLoggingTest {

    @Test
    void coversTheChannelsTheirUtilitiesAndExternalSignaling() {
        // The channel package also holds HTTP and Xbox signaling, below it
        assertEquals(List.of(NetherNetChannel.class.getPackageName(), StunObserver.class.getPackageName(),
                "org.cloudburstmc.netty.signaling"), NetherNetLogging.javaLoggers());
    }

    @Test
    void refusesALevelItDoesNotKnow() {
        assertFalse(NetherNetLogging.setLogLevel("loud"));
        assertFalse(NetherNetLogging.setLogLevel(" "));
    }
}
