package org.cloudburstmc.netty.signalling.admission;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Numeric endpoint classification, shared by provider adapters. No DNS or reachability claims. */
public final class EndpointAddress {
    public enum Scope { PUBLIC, PRIVATE, LOOPBACK, DOCUMENTATION, UNUSABLE }
    private EndpointAddress() {}

    public static InetAddress parse(String value) throws UnknownHostException {
        if (value == null || value.isEmpty() || value.length() > 45 || !value.matches("[0-9a-fA-F:.]+")) throw new UnknownHostException("Expected a numeric IP address");
        String dotted = value.substring(value.lastIndexOf(':') + 1);
        if ((!value.contains(":") || value.contains(".")) && !dotted.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
            throw new UnknownHostException("Expected dotted-decimal IPv4");
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(value);
        if (bytes == null) throw new UnknownHostException("Invalid IP address");
        return InetAddress.getByAddress(bytes); // Also normalizes IPv4-mapped IPv6.
    }

    /** IANA special-purpose registries, reviewed 2026-09-07. */
    public static Scope scope(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255, d = raw[3] & 255;
            if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127)) return Scope.PRIVATE;
            if (a == 127) return Scope.LOOPBACK;
            if ((a == 192 && b == 0 && c == 2) || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)) return Scope.DOCUMENTATION;
            if (a == 0 || a >= 224 || (a == 169 && b == 254) || (a == 198 && (b == 18 || b == 19)) ||
                (a == 192 && b == 88 && c == 99) || (a == 192 && b == 0 && c == 0 && d != 9 && d != 10)) return Scope.UNUSABLE;
            return Scope.PUBLIC;
        }
        int[] words = new int[8];
        for (int i = 0; i < words.length; i++) words[i] = ((raw[i * 2] & 255) << 8) | (raw[i * 2 + 1] & 255);
        int a = words[0], b = words[1];
        if ((a & 0xfe00) == 0xfc00) return Scope.PRIVATE;
        if (address.isLoopbackAddress()) return Scope.LOOPBACK;
        if ((a == 0x2001 && b == 0xdb8) || (a == 0x3fff && b < 0x1000)) return Scope.DOCUMENTATION;
        if ((a & 0xe000) != 0x2000 || a == 0x2002) return Scope.UNUSABLE;
        if (a == 0x2001 && b < 0x200) {
            boolean zeroMiddle = true;
            for (int i = 2; i < 7; i++) zeroMiddle &= words[i] == 0;
            boolean globallyReachable = (b == 1 && zeroMiddle && words[7] >= 1 && words[7] <= 3) ||
                b == 3 || (b == 4 && words[2] == 0x112) || (b >= 0x20 && b <= 0x3f);
            if (!globallyReachable) return Scope.UNUSABLE;
        }
        return Scope.PUBLIC;
    }

    public static boolean advertisable(InetAddress address, boolean localDevelopment) {
        Scope scope = scope(address);
        return scope == Scope.PUBLIC || scope == Scope.PRIVATE ||
            (localDevelopment && (scope == Scope.LOOPBACK || scope == Scope.DOCUMENTATION));
    }
}
