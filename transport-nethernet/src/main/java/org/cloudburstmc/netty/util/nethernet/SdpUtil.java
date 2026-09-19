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

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for the SDP documents exchanged during signaling.
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
     * Carrier grade NAT, which a tethered or Tailscale peer sits behind, and IPv6 unique local
     * addresses are the two that matter most here, and neither is covered by the predicates
     * {@link InetAddress} offers.
     *
     * @param address The address to judge
     * @return Whether it is routable
     */
    private static boolean isRoutable(InetAddress address) {
        return EndpointAddress.scope(address) == EndpointAddress.Scope.PUBLIC;
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
     * Candidates for the address a peer signaled from, one per port it gathered locally.
     * <p>
     * A peer that holds no reflexive candidate of its own offers nothing a host on another network
     * can reach, and its own checks die on the first NAT they meet. Its public address is known
     * anyway, because it just made an HTTP request from it, and consumer NATs usually keep the port
     * a socket already uses. Checking there costs a few packets, and if it does map that way the
     * check opens the path in both directions.
     * <p>
     * Nothing is inferred for a peer that already carries a reflexive or relayed candidate, or that
     * signaled from an address on this network, since there is a real path in both cases.
     *
     * @param sdp          The offer to read the peer's ports out of
     * @param signaledFrom The address the offer arrived from, or null if it is not known
     * @return Candidate lines to add to the peer connection, empty when there is nothing to infer
     */
    public static List<String> inferredPeerCandidates(String sdp, InetSocketAddress signaledFrom) {
        if (signaledFrom == null) {
            return List.of();
        }
        InetAddress from = signaledFrom.getAddress();
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
     * Announces only the addresses in {@code allowed}, each in the form it calls for.
     * <p>
     * ICE gathers a candidate on every interface it can see, which on a host network includes
     * container and overlay addresses that are unreachable from outside. Each one costs the remote
     * peer a round of connectivity checks before it gives up, so a host that knows which of its
     * addresses are reachable can announce only those.
     * <p>
     * An allowed address this host did not gather is the public side of a NAT that forwards the
     * media port here, and is announced as a server reflexive candidate on the port of a host
     * candidate of the same family. That works because libjuice matches inbound traffic by its
     * source and never by the address it was sent to. Nothing here can tell a forward from a
     * mistake, so the list only ever narrows the host candidates, and only to addresses this host
     * holds: one naming none of them leaves every candidate in place, and a wrong address costs the
     * peer a failed check rather than the connection. Reflexive and relayed candidates always stay,
     * since they are what the outside sees rather than an interface.
     *
     * @param sdp     The description to filter
     * @param allowed The addresses that may be announced, empty to announce everything
     * @return The filtered description
     */
    public static String withAdvertisedCandidates(String sdp, Set<String> allowed) {
        if (allowed.isEmpty()) {
            return sdp;
        }
        String[] lines = sdp.split("\r\n|\n");
        Set<String> gathered = new HashSet<>();
        List<String[]> hosts = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith(CANDIDATE_PREFIX)) {
                continue;
            }
            String address = candidateAddress(line);
            if (address != null) {
                gathered.add(normaliseAddress(address));
            }
            String[] parts = line.split(" ");
            if (isHostCandidate(line) && "udp".equalsIgnoreCase(parts[2])) {
                hosts.add(parts);
            }
        }

        Set<String> held = new HashSet<>();
        List<String> foreign = new ArrayList<>();
        for (String address : allowed) {
            String normalised = normaliseAddress(address);
            if (gathered.contains(normalised)) {
                held.add(normalised);
            } else {
                foreign.add(normalised);
            }
        }
        Collections.sort(foreign);
        // A held host is the more honest base for a translation, so it goes first for its family
        hosts.sort(Comparator.comparing((String[] host) -> !held.contains(normaliseAddress(host[4]))));
        List<String> translated = translatedCandidates(hosts, foreign);
        if (held.isEmpty() && translated.isEmpty()) {
            log.warn("None of the gathered ICE candidates match the advertised addresses {}, "
                    + "announcing all of them instead", allowed);
            return sdp;
        }

        StringBuilder out = new StringBuilder(sdp.length());
        boolean seenCandidates = false;
        for (String line : lines) {
            // A trailing empty line makes libwebrtc reject the whole description
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(CANDIDATE_PREFIX)) {
                seenCandidates = true;
                if (!held.isEmpty() && !isReflexiveOrRelayed(line)) {
                    String address = candidateAddress(line);
                    if (address == null || !held.contains(normaliseAddress(address))) {
                        continue;
                    }
                }
            } else if (seenCandidates && !translated.isEmpty()) {
                // Translations join the end of the candidate block, ahead of end-of-candidates
                appendAll(out, translated);
                translated = List.of();
            }
            out.append(line).append("\r\n");
        }
        appendAll(out, translated);
        return out.toString();
    }

    /** RFC 8445 section 5.1.2.1 with the server reflexive type preference, below any host. */
    private static final long TRANSLATED_PRIORITY = (100L << 24) | (65535L << 8) | 255;

    /**
     * A server reflexive candidate for every foreign address, based on the first host candidate
     * of the same family. One per address and port, since every interface shares the socket and
     * would otherwise give the same line.
     */
    private static List<String> translatedCandidates(List<String[]> hosts, List<String> foreign) {
        List<String> candidates = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        int foundation = 80000000;
        for (String address : foreign) {
            byte[] raw = NetUtil.createByteArrayFromIpAddressString(address);
            if (raw == null) {
                log.warn("Advertised address {} is not an IP literal, so it cannot be announced", address);
                continue;
            }
            for (String[] host : hosts) {
                byte[] local = NetUtil.createByteArrayFromIpAddressString(host[4]);
                if (local == null || local.length != raw.length || !emitted.add(address + " " + host[5])) {
                    continue;
                }
                log.debug("Announcing {} as a translation of this host on port {}", address, host[5]);
                candidates.add(CANDIDATE_PREFIX + foundation++ + " 1 " + host[2] + " " + TRANSLATED_PRIORITY + " "
                        + address + " " + host[5] + " typ srflx raddr " + host[4] + " rport " + host[5]);
            }
        }
        return candidates;
    }

    private static String candidateType(String candidate) {
        String[] parts = candidate.split(" ");
        return parts.length >= 8 && "typ".equals(parts[6]) ? parts[7] : null;
    }

    private static boolean isHostCandidate(String candidate) {
        return "host".equals(candidateType(candidate));
    }

    /** Whether a STUN or TURN server produced the candidate, describing the outside rather than an interface. */
    private static boolean isReflexiveOrRelayed(String candidate) {
        String type = candidateType(candidate);
        return "srflx".equals(type) || "prflx".equals(type) || "relay".equals(type);
    }

    private static void appendAll(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append(line).append("\r\n");
        }
    }
}
