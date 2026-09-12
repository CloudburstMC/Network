package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for the SDP documents exchanged during signalling.
 */
public final class SdpUtil {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(SdpUtil.class);

    private static final String CANDIDATE_PREFIX = "a=candidate:";

    /**
     * How many ports are guessed at for one peer. A peer gathers one port per interface it holds,
     * so a handful covers any real client, and the offer deciding how many packets leave here is
     * not something a peer should get to choose.
     */
    private static final int MAX_INFERRED_CANDIDATES = 8;

    private SdpUtil() {
    }

    /**
     * The connection address of an {@code a=candidate:} line, or null if it has none.
     * <p>
     * The grammar of RFC 5245 section 15.1 puts the address in the fifth token, after the
     * foundation, component, transport and priority.
     *
     * @param candidate The candidate line
     * @return The address it points at
     */
    public static String candidateAddress(String candidate) {
        String[] parts = candidate.split(" ");
        return parts.length < 5 ? null : parts[4];
    }

    /**
     * The canonical form of an IP literal, so that the same address written two ways compares equal.
     * Anything that is not an IP literal, such as an mDNS {@code .local} candidate, is left alone
     * rather than resolved, since a lookup here would block and can only answer for this host.
     *
     * @param address The address as written
     * @return A form that can be compared
     */
    private static String normaliseAddress(String address) {
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(address);
        if (bytes == null) {
            return address;
        }
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return address;
        }
    }

    /**
     * Whether an address is one a peer on another network could reach.
     * <p>
     * {@link InetAddress} covers the obvious cases but not the two that matter most here: carrier
     * grade NAT, which a tethered or Tailscale peer sits behind, and IPv6 unique local addresses.
     *
     * @param address The address to judge
     * @return Whether it is routable
     */
    private static boolean isRoutable(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10
            return !((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40);
        }
        // fc00::/7
        return (bytes[0] & 0xFE) != 0xFC;
    }

    /**
     * Whether a description holds a host candidate at a routable address.
     * <p>
     * A host that gathered one is reachable as the protocol expects, and needs none of the guessing
     * {@link #inferredPeerCandidates} does.
     *
     * @param sdp The description to read
     * @return Whether a peer elsewhere can reach one of its host candidates
     */
    public static boolean hasRoutableHostCandidate(String sdp) {
        for (String line : sdp.split("\r\n|\n")) {
            if (!line.startsWith(CANDIDATE_PREFIX)) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length < 8 || !"typ".equals(parts[6]) || !"host".equals(parts[7])) {
                continue;
            }
            byte[] bytes = NetUtil.createByteArrayFromIpAddressString(parts[4]);
            if (bytes == null) {
                continue;
            }
            try {
                if (isRoutable(InetAddress.getByAddress(bytes))) {
                    return true;
                }
            } catch (UnknownHostException e) {
                // Not an address we can judge, so not one we can count on
            }
        }
        return false;
    }

    /**
     * Candidates for the address a peer signalled from, one per port it gathered locally.
     * <p>
     * A peer that holds no reflexive candidate of its own offers nothing a host on another network
     * can reach, and its own checks die on the first NAT they meet. Its public address is known
     * anyway, because it just made an HTTP request from it, and consumer NATs usually keep the port
     * a socket already uses. Checking there costs a few packets, and if it does map that way the
     * check opens the path in both directions.
     * <p>
     * Nothing is inferred for a peer that already carries a reflexive or relayed candidate, or that
     * signalled from an address on this network, since there is a real path in both cases.
     *
     * @param sdp          The offer to read the peer's ports out of
     * @param signalledFrom The address the offer arrived from, or null if it is not known
     * @return Candidate lines to add to the peer connection, empty when there is nothing to infer
     */
    public static List<String> inferredPeerCandidates(String sdp, InetSocketAddress signalledFrom) {
        if (signalledFrom == null) {
            return List.of();
        }
        InetAddress from = signalledFrom.getAddress();
        if (from == null || !isRoutable(from)) {
            return List.of();
        }

        Set<String> ports = new LinkedHashSet<>();
        for (String line : sdp.split("\r\n|\n")) {
            if (!line.startsWith(CANDIDATE_PREFIX)) {
                continue;
            }
            String[] parts = line.split(" ");
            if (parts.length < 8 || !"typ".equals(parts[6])) {
                continue;
            }
            if (!"host".equals(parts[7])) {
                // The peer can already be reached without guessing
                return List.of();
            }
            if ("udp".equalsIgnoreCase(parts[2]) && ports.size() < MAX_INFERRED_CANDIDATES) {
                ports.add(parts[5]);
            }
        }

        String host = from.getHostAddress();
        List<String> candidates = new ArrayList<>(ports.size());
        int foundation = 90000000;
        for (String port : ports) {
            candidates.add(CANDIDATE_PREFIX + foundation++ + " 1 UDP 1677721855 " + host + " " + port
                    + " typ srflx raddr 0.0.0.0 rport 0");
        }
        return candidates;
    }

    /**
     * Drops every ICE candidate whose address is not in {@code allowed}.
     * <p>
     * ICE gathers a candidate on every interface it can see, which on a host network includes
     * container and overlay addresses that are unreachable from outside. Each one costs the remote
     * peer a round of connectivity checks before it gives up, so a host that knows which of its
     * addresses are reachable can announce only those. If nothing would be left the description is
     * returned untouched, since no candidates at all can never connect.
     *
     * @param sdp     The description to filter
     * @param allowed The addresses that may be announced, empty to announce everything
     * @return The filtered description
     */
    public static String withAdvertisedCandidates(String sdp, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return sdp;
        }
        Set<String> normalised = new HashSet<>(allowed.size());
        for (String address : allowed) {
            normalised.add(normaliseAddress(address));
        }

        StringBuilder out = new StringBuilder(sdp.length());
        boolean kept = false;
        boolean dropped = false;
        for (String line : sdp.split("\r\n|\n")) {
            // A trailing empty line makes libwebrtc reject the whole description
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(CANDIDATE_PREFIX)) {
                String address = candidateAddress(line);
                if (address == null || !normalised.contains(normaliseAddress(address))) {
                    dropped = true;
                    continue;
                }
                kept = true;
            }
            out.append(line).append("\r\n");
        }

        if (!kept && dropped) {
            log.warn("None of the gathered ICE candidates match the advertised addresses {}, "
                    + "announcing all of them instead", allowed);
            return sdp;
        }
        return out.toString();
    }
}
