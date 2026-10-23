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

package org.cloudburstmc.netty.util.nethernet;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A set of addresses written as single hosts or CIDR ranges, such as {@code 10.0.0.0/8} or
 * {@code 2001:db8::/32}. An address with no prefix matches only itself, and a host name every
 * address it resolves to.
 */
public final class IpRangeSet {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(IpRangeSet.class);

    private static final IpRangeSet EMPTY = new IpRangeSet(List.of(), List.of());

    private final List<IpSubnetFilterRule> rules;
    /** The same ranges as numeric CIDR, for matching outside Java. */
    private final List<String> entries;

    private IpRangeSet(List<IpSubnetFilterRule> rules, List<String> entries) {
        this.rules = rules;
        this.entries = entries;
    }

    public static IpRangeSet empty() {
        return EMPTY;
    }

    /**
     * Parses the entries, logging and skipping any that are not an address or a range.
     *
     * @param entries Addresses or CIDR ranges
     * @return The parsed set
     */
    public static IpRangeSet parse(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }

        List<IpSubnetFilterRule> rules = new ArrayList<>(entries.size());
        List<String> kept = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int slash = trimmed.lastIndexOf('/');
                if (slash < 0) {
                    // A bare address covers itself only, so the prefix is the whole address
                    for (InetAddress address : InetAddress.getAllByName(trimmed)) {
                        add(rules, kept, address, address instanceof Inet6Address ? 128 : 32);
                    }
                } else {
                    add(rules, kept, InetAddress.getByName(trimmed.substring(0, slash)),
                            Integer.parseInt(trimmed.substring(slash + 1).trim()));
                }
            } catch (IllegalArgumentException | UnknownHostException e) {
                log.warn("Ignoring {}, which is not an address or CIDR range: {}", trimmed, e.getMessage());
            }
        }
        return rules.isEmpty() ? EMPTY : new IpRangeSet(rules, List.copyOf(kept));
    }

    private static void add(List<IpSubnetFilterRule> rules, List<String> entries, InetAddress address, int prefix) {
        rules.add(new IpSubnetFilterRule(address, prefix, IpFilterRuleType.ACCEPT));
        entries.add(NetUtil.toAddressString(address) + "/" + prefix);
    }

    public boolean isEmpty() {
        return this.rules.isEmpty();
    }

    /**
     * @param other The set to add
     * @return A set matching any address either set matches
     */
    public IpRangeSet union(IpRangeSet other) {
        if (other.isEmpty()) {
            return this;
        }
        if (this.isEmpty()) {
            return other;
        }
        List<IpSubnetFilterRule> rules = new ArrayList<>(this.rules);
        rules.addAll(other.rules);
        List<String> entries = new ArrayList<>(this.entries);
        entries.addAll(other.entries);
        return new IpRangeSet(rules, List.copyOf(entries));
    }

    /**
     * @return The valid entries as numeric CIDR ranges, such as {@code 192.168.1.5/32}, with host names resolved
     */
    public List<String> entries() {
        return this.entries;
    }

    public boolean contains(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return false;
        }
        for (IpSubnetFilterRule rule : this.rules) {
            if (rule.matches(address)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains(InetAddress address) {
        return address != null && contains(new InetSocketAddress(address, 0));
    }
}
