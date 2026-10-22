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
 * Events that belong to the listener rather than to one connection, which is the only place a join
 * that never became a channel can be seen. Anything that happens once a connection exists belongs
 * on its channel instead.
 *
 * @see NetherChannelMetrics for the per connection counterpart
 */
public interface NetherServerMetrics {

    default void connectionAccepted(String networkId, boolean verifiedIdentity) {
    }

    /** The status names the reason, and is what the peer was refused with. */
    default void joinRefused(int httpStatus) {
    }

    /** The address already holds as many signaling connections as it may. */
    default void addressRefused(InetSocketAddress address) {
    }

    /** A request arrived without TLS where it is required. */
    default void plaintextRefused(InetSocketAddress address) {
    }
}
