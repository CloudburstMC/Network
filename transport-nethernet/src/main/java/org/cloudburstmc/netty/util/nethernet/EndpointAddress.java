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

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Numeric endpoint classification, shared by provider adapters. No DNS or reachability claims.
 */
public final class EndpointAddress {
    public enum Scope {
        /** Globally reachable unicast. */
        PUBLIC,
        /** Reachable from the same network or overlay: RFC 1918, carrier grade NAT, unique local. */
        PRIVATE,
        /** This host only. */
        LOOPBACK,
        /** Special purpose, documentation, or otherwise no use to a peer. */
        UNUSABLE
    }

    private EndpointAddress() {
    }

    /**
     * Reads an IP literal, never a name, so that no lookup can block or leave the host.
     */
    public static InetAddress parse(String value) throws UnknownHostException {
        // The charset also turns away a zone id, brackets and surrounding space, which Netty takes
        if (value == null || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+")) {
            throw new UnknownHostException("Expected a numeric IP address");
        }
        InetAddress address = NetUtil.createInetAddressFromIpAddressString(value);
        if (address == null) {
            throw new UnknownHostException("Invalid IP address");
        }
        return address; // Also normalizes IPv4-mapped IPv6.
    }

    /**
     * Where an address sits, per the IANA special-purpose registries reviewed 2026-09-07.
     */
    public static Scope scope(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255, d = raw[3] & 255;
            if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 100 && b >= 64 && b <= 127)) {
                return Scope.PRIVATE;
            }
            if (a == 127) {
                return Scope.LOOPBACK;
            }
            // 192.0.0.9 and .10 are the PCP and TURN anycast addresses, which a peer can reach
            if (a == 0 || a >= 224 || (a == 169 && b == 254) || (a == 203 && b == 0 && c == 113)
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 192 && ((b == 88 && c == 99)
                    || (b == 0 && (c == 2 || (c == 0 && d != 9 && d != 10)))))) {
                return Scope.UNUSABLE;
            }
            return Scope.PUBLIC;
        }
        int a = word(raw, 0), b = word(raw, 1);
        if ((a & 0xfe00) == 0xfc00) {
            return Scope.PRIVATE;
        }
        if (address.isLoopbackAddress()) {
            return Scope.LOOPBACK;
        }
        // Global unicast is 2000::/3 alone, less 6to4, the protocol block and the two doc ranges
        if ((a & 0xe000) != 0x2000 || a == 0x2002 || (a == 0x2001 && (b < 0x200 || b == 0xdb8))
            || (a == 0x3fff && b < 0x1000)) {
            return Scope.UNUSABLE;
        }
        return Scope.PUBLIC;
    }

    private static int word(byte[] raw, int index) {
        return ((raw[index * 2] & 255) << 8) | (raw[index * 2 + 1] & 255);
    }

    /**
     * Whether an address is worth publishing. A private, carrier grade NAT or unique local address
     * is, since a peer on the same network or overlay reaches it; loopback only behind a proxy.
     */
    public static boolean advertisable(InetAddress address, boolean localDevelopment) {
        Scope scope = scope(address);
        return scope == Scope.PUBLIC || scope == Scope.PRIVATE
            || (localDevelopment && scope == Scope.LOOPBACK);
    }
}
