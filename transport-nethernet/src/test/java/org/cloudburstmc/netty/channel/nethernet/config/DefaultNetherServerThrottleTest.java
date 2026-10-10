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

package org.cloudburstmc.netty.channel.nethernet.config;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultNetherServerThrottleTest {

    private static InetSocketAddress at(String host) {
        return new InetSocketAddress(host, 19132);
    }

    @Test
    void limitsJoinsPerWindow() {
        var throttle = new DefaultNetherServerThrottle(10, 60_000, 3);
        for (int i = 0; i < 3; i++) {
            assertTrue(throttle.accept(at("192.0.2.1")));
        }
        assertFalse(throttle.accept(at("192.0.2.1")));
        assertTrue(throttle.accept(at("192.0.2.2")), "another address has a window of its own");
    }

    @Test
    void allowsJoinsAgainOnceTheWindowPasses() throws Exception {
        var throttle = new DefaultNetherServerThrottle(10, 200, 1);
        assertTrue(throttle.accept(at("192.0.2.1")));
        assertFalse(throttle.accept(at("192.0.2.1")));
        Thread.sleep(600);
        assertTrue(throttle.accept(at("192.0.2.1")));
    }

    @Test
    void limitsOpenConnectionsUntilOneCloses() {
        var throttle = new DefaultNetherServerThrottle(2, 60_000, 100);
        assertTrue(throttle.accept(at("192.0.2.1")));
        assertTrue(throttle.accept(at("192.0.2.1")));
        assertFalse(throttle.accept(at("192.0.2.1")));
        throttle.closed(at("192.0.2.1"));
        assertTrue(throttle.accept(at("192.0.2.1")));
    }

    @Test
    void countsAnIpv6Slash64AsOneAddress() {
        var throttle = new DefaultNetherServerThrottle(10, 60_000, 1);
        assertTrue(throttle.accept(at("2001:db8::1")));
        assertFalse(throttle.accept(at("2001:db8::2")));
        assertTrue(throttle.accept(at("2001:db8:0:1::1")));
    }
}
