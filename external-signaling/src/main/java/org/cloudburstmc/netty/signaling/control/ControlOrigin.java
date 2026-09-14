package org.cloudburstmc.netty.signaling.control;

import java.util.ArrayList;
import java.util.List;

/** Strict draft-control origin grammar. Never normalizes input, resolves DNS or changes core origin handling. */
public final class ControlOrigin {
    private ControlOrigin() { }

    public static boolean isCanonical(String value) {
        if (value == null || value.length() > 2048) return false;
        boolean secure = value.startsWith("https://");
        if (!secure && !value.startsWith("http://")) return false;
        String authority = value.substring(secure ? 8 : 7);
        if (authority.isEmpty() || !authority.matches("[a-z0-9.\\[\\]:-]+")) return false;
        String host, port;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) return false;
            host = authority.substring(0, end + 1);
            port = authority.substring(end + 1);
            if (!ipv6(host.substring(1, host.length() - 1))) return false;
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? "" : authority.substring(colon);
            if (!ipv4(host) && !dns(host)) return false;
        }
        if (!port.isEmpty()) {
            if (!port.matches(":[1-9][0-9]{0,4}")) return false;
            int number = Integer.parseInt(port.substring(1));
            if (number > 65535 || number == (secure ? 443 : 80)) return false;
        }
        return secure || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    public static void requireCanonical(String value) {
        if (!isCanonical(value)) throw new IllegalArgumentException("Invalid canonical control origin");
    }

    private static boolean ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static boolean dns(String host) {
        if (host.isEmpty() || host.length() > 253) return false;
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?") || label.startsWith("xn--")) return false;
        }
        // Excludes numeric/hex last-label reinterpretation by WHATWG and Java URI's non-DNS forms.
        return labels[labels.length - 1].charAt(0) >= 'a' && labels[labels.length - 1].charAt(0) <= 'z';
    }

    private static boolean ipv6(String input) {
        if (input.isEmpty() || !input.matches("[0-9a-f:]+")) return false;
        int compression = input.indexOf("::");
        if (compression != input.lastIndexOf("::")) return false;
        List<Integer> pieces = new ArrayList<>(8);
        if (compression < 0) {
            if (!pieces(input, pieces) || pieces.size() != 8) return false;
        } else {
            List<Integer> right = new ArrayList<>(8);
            if (!pieces(input.substring(0, compression), pieces) || !pieces(input.substring(compression + 2), right)) return false;
            int zeros = 8 - pieces.size() - right.size();
            if (zeros < 2) return false;
            for (int i = 0; i < zeros; i++) pieces.add(0);
            pieces.addAll(right);
        }
        int bestStart = -1, bestLength = 1;
        for (int i = 0; i < pieces.size();) {
            if (pieces.get(i) != 0) { i++; continue; }
            int start = i;
            while (i < pieces.size() && pieces.get(i) == 0) i++;
            if (i - start > bestLength) { bestStart = start; bestLength = i - start; }
        }
        String canonical;
        if (bestStart < 0) canonical = join(pieces, 0, 8);
        else canonical = join(pieces, 0, bestStart) + "::" + join(pieces, bestStart + bestLength, 8);
        return input.equals(canonical);
    }

    private static boolean pieces(String input, List<Integer> output) {
        if (input.isEmpty()) return true;
        String[] values = input.split(":", -1);
        if (values.length > 8) return false;
        for (String value : values) {
            if (!value.matches("0|[1-9a-f][0-9a-f]{0,3}")) return false;
            output.add(Integer.parseInt(value, 16));
        }
        return true;
    }

    private static String join(List<Integer> pieces, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) result.append(':');
            result.append(Integer.toHexString(pieces.get(i)));
        }
        return result.toString();
    }
}
