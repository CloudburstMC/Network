package org.cloudburstmc.netty.util.nethernet;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
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
 * {@code 2001:db8::/32}. An address with no prefix matches only itself.
 */
public final class IpRangeSet {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(IpRangeSet.class);

    private static final IpRangeSet EMPTY = new IpRangeSet(List.of());

    private final List<IpSubnetFilterRule> rules;

    private IpRangeSet(List<IpSubnetFilterRule> rules) {
        this.rules = rules;
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
        for (String entry : entries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                rules.add(rule(trimmed));
            } catch (IllegalArgumentException | UnknownHostException e) {
                log.warn("Ignoring {}, which is not an address or CIDR range: {}", trimmed, e.getMessage());
            }
        }
        return rules.isEmpty() ? EMPTY : new IpRangeSet(rules);
    }

    private static IpSubnetFilterRule rule(String entry) throws UnknownHostException {
        int slash = entry.lastIndexOf('/');
        if (slash < 0) {
            // A bare address covers itself only, so the prefix is the whole address
            InetAddress address = InetAddress.getByName(entry);
            return new IpSubnetFilterRule(address, address instanceof Inet6Address ? 128 : 32, IpFilterRuleType.ACCEPT);
        }

        InetAddress address = InetAddress.getByName(entry.substring(0, slash));
        return new IpSubnetFilterRule(address, Integer.parseInt(entry.substring(slash + 1).trim()),
                IpFilterRuleType.ACCEPT);
    }

    public boolean isEmpty() {
        return this.rules.isEmpty();
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
