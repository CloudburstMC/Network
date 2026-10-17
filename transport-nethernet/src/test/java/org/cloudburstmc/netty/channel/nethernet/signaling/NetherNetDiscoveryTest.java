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

import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetherNetDiscoveryTest {

    /** Each bind brings a loop thread, which kept the JVM alive after close. */
    @Test
    void closeStopsTheLoopTheSocketRanOn() throws Exception {
        NetherNetDiscovery discovery = new NetherNetDiscovery(1);
        discovery.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        assertTrue(discovery.isActive());

        discovery.close();

        assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS), "the loop outlived close");
    }

    @Test
    void aFailedBindStopsItsLoop() throws Exception {
        try (DatagramSocket taken = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            NetherNetDiscovery discovery = new NetherNetDiscovery(1);

            assertThrows(BindException.class,
                    () -> discovery.bind((InetSocketAddress) taken.getLocalSocketAddress()));

            assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS),
                    "the loop outlived the failed bind");
        }
    }
}
