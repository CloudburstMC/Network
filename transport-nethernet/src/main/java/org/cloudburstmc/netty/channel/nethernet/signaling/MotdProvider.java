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

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * Functional interface providing the MOTD returned to clients querying the server.
 */
@FunctionalInterface
public interface MotdProvider {
    /**
     * Called for every status request, so the returned data can change over time.
     * <p>
     * Called on the event loop, so don't block in here. Every field of {@link PongData} is
     * written to the status document, in the order the schema lists.
     * <p>
     * Answering null serves no status at all, which is how a host says it does not take
     * NetherNet for this request. A join sent anyway still reaches the {@link PlayerFilter}.
     *
     * @param host          The host header from the join request, which may be used to identify the server
     * @param remoteAddress The address the status request came from
     * @return The MOTD to advertise, or null to leave the client to its other transport
     */
    @Nullable PongData getMotd(String host, InetSocketAddress remoteAddress);
}
