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

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A child starts out with the address the peer signaled from, which a relay or proxy may stand in
 * for, and only learns the peer's own address once ICE selects a pair.
 */
class RemoteAddressTest {
    private static final InetSocketAddress SIGNALED = new InetSocketAddress("198.51.100.1", 19132);
    private static final InetSocketAddress SELECTED = new InetSocketAddress("203.0.113.7", 54321);

    @Test
    void theSelectedPairReplacesAnAddressAlreadyRead() {
        NetherNetChildChannel child = new NetherNetChildChannel(null, null, SIGNALED, SIGNALED);
        // An initializer reads it before ICE connects, which Netty caches
        assertEquals(SIGNALED, child.remoteAddress());

        child.setRemoteAddress(SELECTED);
        assertEquals(SELECTED, child.remoteAddress());
    }
}
