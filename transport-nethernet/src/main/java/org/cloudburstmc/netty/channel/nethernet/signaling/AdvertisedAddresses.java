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

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Advertised addresses as operators write them, the same for built-in and external signaling: IP
 * literals or host names, either with a port. A name is looked up once, when the signaling starts,
 * so a change takes a restart or reload.
 */
public final class AdvertisedAddresses {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(AdvertisedAddresses.class);

    /** The most endpoints announced, which is also what external signaling allows. */
    public static final int MAX_ENDPOINTS = 32;

    private AdvertisedAddresses() {
    }

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Reads each entry without looking anything up: a literal as its address, a name unresolved.
     *
     * @param values      The entries, such as {@code play.example.com}, {@code IPv4:port} or
     *                    {@code [IPv6]:port}
     * @param defaultPort The port of an entry that names none
     * @return The endpoints, once each
     * @throws IllegalArgumentException If an entry is neither a literal nor a name, or is an address
     *                                  no player can reach
     */
    public static List<InetSocketAddress> parse(Collection<String> values, int defaultPort) {
        return values.stream().map(value -> endpoint(value, defaultPort)).distinct().toList();
    }

    private static InetSocketAddress endpoint(String value, int defaultPort) {
        String trimmed = value == null ? "" : value.trim();
        InetSocketAddress literal;
        try {
            literal = EndpointAddress.parseEndpoint(trimmed);
        } catch (IllegalArgumentException notALiteral) {
            InetSocketAddress name = HostPort.parse(trimmed, defaultPort);
            // A bracketed or all numeric name is a literal gone wrong, not something to look up
            if (name == null || trimmed.startsWith("[") || name.getHostString().matches("[0-9.]+")) {
                throw new IllegalArgumentException("Invalid advertised address " + value
                        + ", expected an IP or host name, optionally with a port");
            }
            return name;
        }
        requireUsable(literal);
        return literal.getPort() == 0 ? new InetSocketAddress(literal.getAddress(), defaultPort) : literal;
    }

    /**
     * Looks every name up, standing for each address it resolves to on the entry's port.
     *
     * @param endpoints The endpoints from {@link #parse}
     * @return The endpoints, all resolved, once each
     * @throws UnknownHostException     If a name does not resolve
     * @throws IllegalArgumentException If a name resolves to an address no player can reach, or
     *                                  there are more than {@link #MAX_ENDPOINTS}
     */
    public static List<InetSocketAddress> resolve(List<InetSocketAddress> endpoints) throws UnknownHostException {
        return resolve(endpoints, InetAddress::getAllByName);
    }

    static List<InetSocketAddress> resolve(List<InetSocketAddress> endpoints, Resolver resolver)
            throws UnknownHostException {
        Set<InetSocketAddress> resolved = new LinkedHashSet<>();
        for (InetSocketAddress endpoint : endpoints) {
            if (!endpoint.isUnresolved()) {
                resolved.add(endpoint);
                continue;
            }
            InetAddress[] addresses;
            try {
                addresses = resolver.resolve(endpoint.getHostString());
            } catch (UnknownHostException e) {
                UnknownHostException named = new UnknownHostException(
                        "Could not resolve advertised address " + endpoint.getHostString());
                named.initCause(e);
                throw named;
            }
            for (InetAddress address : addresses) {
                InetSocketAddress found = new InetSocketAddress(address, endpoint.getPort());
                requireUsable(found);
                resolved.add(found);
            }
            log.info("Advertised address {} resolves to {}", endpoint.getHostString(),
                    Stream.of(addresses).map(NetUtil::toAddressString).collect(Collectors.joining(", ")));
        }
        if (resolved.size() > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("At most " + MAX_ENDPOINTS + " advertised addresses, got "
                    + resolved.size());
        }
        return List.copyOf(resolved);
    }

    private static void requireUsable(InetSocketAddress endpoint) {
        if (EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.UNUSABLE) {
            throw new IllegalArgumentException("Advertised address " + endpoint.getAddress().getHostAddress()
                    + " is special purpose, no player can reach it");
        }
    }
}
