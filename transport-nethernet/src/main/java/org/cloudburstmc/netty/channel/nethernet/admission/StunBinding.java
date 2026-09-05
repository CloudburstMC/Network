package org.cloudburstmc.netty.channel.nethernet.admission;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/** Bounded, strict parsing of the complete raw ICE Binding Request before admission. */
public record StunBinding(String localUfrag, String remoteUfrag, int integrityOffset) {
    public static StunBinding parse(byte[] packet) {
        if (packet.length < 20 || packet.length > 2048) return null;
        ByteBuffer b = ByteBuffer.wrap(packet);
        if (b.getShort(0) != 1 || b.getInt(4) != 0x2112a442 ||
            Short.toUnsignedInt(b.getShort(2)) + 20 != packet.length || packet.length % 4 != 0) return null;
        String username = null;
        int integrity = -1;
        Set<Integer> seen = new HashSet<>();
        for (int offset = 20; offset < packet.length;) {
            if (offset + 4 > packet.length) return null;
            int type = Short.toUnsignedInt(b.getShort(offset)), size = Short.toUnsignedInt(b.getShort(offset + 2));
            int end = offset + 4 + size;
            if (end > packet.length) return null;
            if ((type == 6 || type == 8 || type == 0x8028 || type == 0x24 || type == 0x25 || type == 0x8029 || type == 0x802a) && !seen.add(type)) return null;
            if ((type == 0x24 && size != 4) || (type == 0x25 && size != 0) || ((type == 0x8029 || type == 0x802a) && size != 8)) return null;
            if (seen.contains(0x8029) && seen.contains(0x802a)) return null;
            if (type < 0x8000 && type != 6 && type != 8 && type != 0x24 && type != 0x25) return null;
            if (type == 0x8028) {
                if (integrity < 0 || size != 4 || end != packet.length) return null;
                CRC32 crc = new CRC32(); crc.update(packet, 0, offset);
                if (((int)crc.getValue() ^ 0x5354554e) != b.getInt(offset + 4)) return null;
            }
            // Only FINGERPRINT may follow MESSAGE-INTEGRITY. Never use unsigned attributes.
            if (integrity >= 0 && type != 0x8028) return null;
            if (type == 6) {
                if (username != null || size > 513) return null;
                for (int i = offset + 4; i < end; i++) if (packet[i] < 0 || packet[i] == 0) return null;
                username = new String(packet, offset + 4, size, StandardCharsets.US_ASCII);
            } else if (type == 8) {
                if (integrity >= 0 || size != 20 || username == null) return null;
                integrity = offset;
            }
            offset = end + ((4 - (size % 4)) % 4);
            if (offset > packet.length) return null;
        }
        if (username == null || integrity < 0) return null;
        int colon = username.indexOf(':');
        if (colon < 4 || colon != username.lastIndexOf(':')) return null;
        String local = username.substring(0, colon), remote = username.substring(colon + 1);
        if (!iceString(local, 4, 256) || !iceString(remote, 4, 256)) return null;
        return new StunBinding(local, remote, integrity);
    }

    public boolean verify(byte[] packet, String password) {
        try {
            if (integrityOffset < 20 || integrityOffset + 24 > packet.length) return false;
            byte[] input = Arrays.copyOf(packet, integrityOffset);
            ByteBuffer.wrap(input).putShort(2, (short) (integrityOffset + 24 - 20));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return MessageDigest.isEqual(mac.doFinal(input), Arrays.copyOfRange(packet, integrityOffset + 4, integrityOffset + 24));
        } catch (Exception e) { return false; }
    }

    public static boolean iceString(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '+' && c != '/') return false;
        }
        return true;
    }
    @Override public String toString() { return "StunBinding[redacted]"; }
}
