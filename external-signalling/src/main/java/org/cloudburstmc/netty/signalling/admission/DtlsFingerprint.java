package org.cloudburstmc.netty.signalling.admission;

import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The SDP form of a certificate digest, as published and as accepted. One spelling either way.
 */
final class DtlsFingerprint {
    private static final Pattern PATTERN = Pattern.compile("sha-256 ([0-9A-F]{2}:){31}[0-9A-F]{2}");
    private static final HexFormat HEX = HexFormat.ofDelimiter(":").withUpperCase();

    private DtlsFingerprint() {
    }

    static String format(byte[] digest) {
        return "sha-256 " + HEX.formatHex(digest);
    }

    static boolean valid(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }
}
