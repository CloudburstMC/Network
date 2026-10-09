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

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits the connections open from one address and the joins it may start per window, like RakNet's
 * {@code DefaultRakServerThrottle}. IPv6 addresses count per /64, which one host usually holds whole.
 */
public class DefaultNetherServerThrottle implements NetherServerThrottle {
    private final Map<InetAddress, AtomicInteger> connections = new ConcurrentHashMap<>();
    private final int connectionsMax;

    private final ExpiringMap<InetAddress, AtomicInteger> connects;
    private final int connectsMax;

    /**
     * 10 open connections and 3 joins per 4 seconds per address.
     */
    public DefaultNetherServerThrottle() {
        this(10, 4_000, 3);
    }

    public DefaultNetherServerThrottle(int connectionsMax, long connectWindowInMs, int connectsMax) {
        this.connectionsMax = connectionsMax;
        this.connects = ExpiringMap.builder()
                .expiration(connectWindowInMs, TimeUnit.MILLISECONDS)
                .expirationPolicy(ExpirationPolicy.CREATED)
                .build();
        this.connectsMax = connectsMax;
    }

    @Override
    public boolean accept(InetSocketAddress address) {
        InetAddress key = key(address.getAddress());
        AtomicInteger connections = this.connections.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (connections.get() >= this.connectionsMax) {
            return false;
        }

        AtomicInteger connects = this.connects.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (connects.get() >= this.connectsMax) {
            return false;
        }
        connects.incrementAndGet();
        connections.incrementAndGet();
        return true;
    }

    @Override
    public void closed(InetSocketAddress address) {
        this.connections.computeIfPresent(key(address.getAddress()),
                (ignored, connections) -> connections.decrementAndGet() <= 0 ? null : connections);
    }

    private static InetAddress key(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] prefix = address.getAddress();
        Arrays.fill(prefix, 8, 16, (byte) 0);
        try {
            return InetAddress.getByAddress(prefix);
        } catch (UnknownHostException e) {
            throw new AssertionError(e); // only thrown for a wrong length
        }
    }
}
