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

import java.net.SocketAddress;

/**
 * The address of a NetherNet peer, which is its NetworkID.
 * <p>
 * A NetworkID is an opaque string. It is currently a 64-bit unsigned integer written in decimal,
 * but nothing may depend on that: the length, character set and range are all free to change, and
 * a Realms peer already uses a different shape.
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/main/NetherNetOnboardingGuide.md#9-networkid">NetherNet onboarding guide, section 9</a>
 */
public class NetherNetAddress extends SocketAddress {
    private final String networkId;

    /**
     * @param networkId The peer's NetworkID, as the signaling reported it
     */
    public NetherNetAddress(String networkId) {
        this.networkId = networkId;
    }

    /**
     * @return The NetworkID
     */
    public String getNetworkId() {
        return networkId;
    }

    @Override
    public String toString() {
        return networkId;
    }
}
