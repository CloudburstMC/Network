package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;

/**
 * Helpers for the SDP documents exchanged during signalling.
 */
public final class SdpUtil {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(SdpUtil.class);

    private static final String CANDIDATE_PREFIX = "a=candidate:";

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
