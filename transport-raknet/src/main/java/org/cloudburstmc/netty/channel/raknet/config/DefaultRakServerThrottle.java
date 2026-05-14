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

package org.cloudburstmc.netty.channel.raknet.config;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultRakServerThrottle implements RakServerThrottle {
    private final Map<InetAddress, AtomicInteger> connectionsPerIp;
    private final int connectionsPerIpMax;

    private final ExpiringMap<InetAddress, AtomicInteger> connects;
    private final int connectsMax;

    public DefaultRakServerThrottle() {
        this(10, 4_000, 3);
    }

    public DefaultRakServerThrottle(int connectionsPerIpMax, long connectWindowInMs, int connectsMax) {
        this.connectionsPerIp = new ConcurrentHashMap<>();
        this.connectionsPerIpMax = connectionsPerIpMax;

        this.connects = ExpiringMap.builder()
                .expiration(connectWindowInMs, TimeUnit.MILLISECONDS)
                .expirationPolicy(ExpirationPolicy.CREATED)
                .build();
        this.connectsMax = connectsMax;
    }

    @Override
    public boolean accept(InetSocketAddress address) {
        AtomicInteger connectionsPerIp = this.connectionsPerIp.computeIfAbsent(address.getAddress(), ignored -> new AtomicInteger());
        if (connectionsPerIp.get() >= connectionsPerIpMax) {
            return false;
        }
        connectionsPerIp.incrementAndGet();

        AtomicInteger attempts = this.connects.computeIfAbsent(address.getAddress(), ignored -> new AtomicInteger());
        if (attempts.get() > connectsMax) {
            return false;
        }
        attempts.incrementAndGet();

        return true;
    }

    @Override
    public void closed(InetSocketAddress address) {
        AtomicInteger connectionsPerIp = this.connectionsPerIp.get(address.getAddress());
        if (connectionsPerIp != null && connectionsPerIp.decrementAndGet() <= 0) {
            this.connectionsPerIp.remove(address.getAddress());
        }
    }
}
