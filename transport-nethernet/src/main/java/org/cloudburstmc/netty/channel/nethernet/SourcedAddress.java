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

import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A peer's address and where it came from.
 *
 * @param address The address
 * @param source  Where it came from
 * @param via     The proxy, forwarder or relay that declared it, when another party did and is known
 */
public record SourcedAddress(InetSocketAddress address, AddressSource source, @Nullable InetSocketAddress via) {

    public SourcedAddress {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(source, "source");
    }

    /** Where nothing has said the peer is. */
    static SourcedAddress unknown() {
        return new SourcedAddress(new InetSocketAddress(0), AddressSource.UNKNOWN, null);
    }
}
