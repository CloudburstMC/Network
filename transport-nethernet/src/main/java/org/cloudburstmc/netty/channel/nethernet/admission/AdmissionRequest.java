package org.cloudburstmc.netty.channel.nethernet.admission;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Untrusted fields from one incoming ICE attempt. Packet bytes remain native.
 */
public record AdmissionRequest(String localUfrag, String remoteUfrag, InetSocketAddress address) {
    public AdmissionRequest {
        if (!iceString(localUfrag, 4, 256) || !iceString(remoteUfrag, 4, 256)) {
            throw new IllegalArgumentException("ICE username fragments");
        }
        Objects.requireNonNull(address);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("Resolved source address required");
        }
    }

    static boolean iceString(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '+' && c != '/') {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "AdmissionRequest[redacted]";
    }
}
