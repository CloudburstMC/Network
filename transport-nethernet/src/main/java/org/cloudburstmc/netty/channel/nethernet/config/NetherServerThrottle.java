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

import java.net.InetSocketAddress;

/**
 * Decides whether a join may go ahead, before its native peer is created. Set on the server channel with
 * {@link NetherChannelOption#NETHER_SERVER_THROTTLE}.
 *
 * @see DefaultNetherServerThrottle
 */
public interface NetherServerThrottle {

    /**
     * @param address The address the peer signaled from
     * @return Whether the join may go ahead. An accepted join is followed by {@link #closed} once its
     * connection closes.
     */
    boolean accept(InetSocketAddress address);

    /**
     * @param address The address a join was accepted for
     */
    void closed(InetSocketAddress address);
}
