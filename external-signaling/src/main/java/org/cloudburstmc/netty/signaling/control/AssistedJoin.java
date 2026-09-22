package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import io.netty.util.NetUtil;
import org.cloudburstmc.netty.util.nethernet.IdentityPublicKey;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** The only unsolicited NXS operation. It is accepted exclusively on an owned provider TLS WebSocket. */
public record AssistedJoin(
        String id,
        String instanceId,
        long generation,
        String incarnation,
        String keyId,
        String hostFingerprint,
        long expiresAt,
        String networkId,
        String cpk,
        String localUfrag,
        String localPassword,
        String offer) {
    public boolean diagnostic() {
        return "0".equals(networkId);
    }

    public static final int MAX_BYTES = 73728;

    public AssistedJoin {
        if (id == null
                || !id.matches("[0-9a-f]{32}")
                || instanceId == null
                || !instanceId.matches("[A-Za-z0-9_-]{1,128}")
                || generation < 1
                || generation > 9007199254740991L
                || incarnation == null
                || !incarnation.matches("[0-9a-f]{32}")
                || keyId == null
                || !keyId.matches("[A-Z0-9]{4}")
                || !fingerprint(hostFingerprint)
                || expiresAt < 1
                || expiresAt > 9007199254740991L
                || !ice(localUfrag, 4)
                || !ice(localPassword, 22)
                || offer == null
                || offer.getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new IllegalArgumentException("Invalid assisted join");
        }
        if (!"0".equals(networkId)) {
            if (networkId == null
                    || !networkId.matches("[1-9][0-9]{0,19}")
                    || cpk == null
                    || cpk.length() != 160) {
                throw new IllegalArgumentException("Invalid assisted player identity");
            }
            Long.parseUnsignedLong(networkId);
            canonicalCpk(cpk);
        } else if (cpk != null
                || !localUfrag.startsWith("NXS1" + keyId)
                || offer.length() > 16384) {
            throw new IllegalArgumentException("Invalid assisted diagnostic purpose");
        }
        parseOffer(offer);
    }

    public static AssistedJoin decode(String wire) {
        JsonObject o = ControlJson.parse(wire, MAX_BYTES);
        boolean diagnostic = "0".equals(ControlJson.string(o, "networkId"));
        if (diagnostic) {
            ControlJson.fields(
                    o,
                    "kind",
                    "version",
                    "id",
                    "instanceId",
                    "generation",
                    "incarnation",
                    "keyId",
                    "hostFingerprint",
                    "expiresAt",
                    "networkId",
                    "localUfrag",
                    "localPassword",
                    "offer");
        } else {
            ControlJson.fields(
                    o,
                    "kind",
                    "version",
                    "id",
                    "instanceId",
                    "generation",
                    "incarnation",
                    "keyId",
                    "hostFingerprint",
                    "expiresAt",
                    "networkId",
                    "cpk",
                    "localUfrag",
                    "localPassword",
                    "offer");
        }
        if (!ControlJson.string(o, "kind").equals("assisted-join")
                || ControlJson.number(o, "version") != 1) {
            throw new IllegalArgumentException("Invalid assisted join kind");
        }
        return new AssistedJoin(
                ControlJson.string(o, "id"),
                ControlJson.string(o, "instanceId"),
                ControlJson.number(o, "generation"),
                ControlJson.string(o, "incarnation"),
                ControlJson.string(o, "keyId"),
                ControlJson.string(o, "hostFingerprint"),
                ControlJson.number(o, "expiresAt"),
                ControlJson.string(o, "networkId"),
                diagnostic ? null : ControlJson.string(o, "cpk"),
                ControlJson.string(o, "localUfrag"),
                ControlJson.string(o, "localPassword"),
                ControlJson.string(o, "offer"));
    }

    public record Offer(
            String ufrag,
            String password,
            String fingerprint,
            List<InetSocketAddress> candidates) {}

    public static Offer parseOffer(String sdp) {
        List<String> lines = sdp.lines().toList();
        if (lines.size() > 256
                || lines.stream().anyMatch(l -> l.indexOf('\0') >= 0)
                || !one(lines, "m=")
                        .matches("application [0-9]{1,5} UDP/DTLS/SCTP webrtc-datachannel")
                || !one(lines, "a=setup:").equals("actpass")
                || !one(lines, "a=sctp-port:").equals("5000")
                || !one(lines, "a=max-message-size:").equals("262144")
                || lines.contains("a=ice-lite")) {
            throw new IllegalArgumentException("Unsupported assisted offer");
        }
        String ufrag = one(lines, "a=ice-ufrag:"),
                password = one(lines, "a=ice-pwd:"),
                fingerprint = one(lines, "a=fingerprint:");
        if (!ice(ufrag, 4) || !ice(password, 22) || !fingerprint(fingerprint)) {
            throw new IllegalArgumentException("Invalid assisted ICE/DTLS");
        }
        List<InetSocketAddress> candidates = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("a=candidate:")) {
                String[] c = line.substring(12).split(" ");
                if (c.length < 8
                        || c.length > 24
                        || !c[1].equals("1")
                        || !c[2].equalsIgnoreCase("UDP")
                        || !c[6].equals("typ")
                        || !Set.of("host", "srflx", "prflx", "relay").contains(c[7])
                        || !c[5].matches("[0-9]{1,5}")
                        || !(NetUtil.isValidIpV4Address(c[4])
                                || NetUtil.isValidIpV6Address(c[4]))) {
                    throw new IllegalArgumentException("Invalid assisted candidate");
                }
                int port = Integer.parseInt(c[5]);
                if (port < 1 || port > 65535 || candidates.size() == 32) {
                    throw new IllegalArgumentException("Assisted candidate limit");
                }
                candidates.add(
                        new InetSocketAddress(
                                NetUtil.createInetAddressFromIpAddressString(c[4]), port));
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Assisted offer needs a numeric candidate");
        }
        return new Offer(ufrag, password, fingerprint, List.copyOf(candidates));
    }

    private static String one(List<String> lines, String prefix) {
        var found = lines.stream().filter(l -> l.startsWith(prefix)).toList();
        if (found.size() != 1) {
            throw new IllegalArgumentException("Ambiguous assisted offer");
        }
        return found.get(0).substring(prefix.length());
    }

    public static byte[] canonicalCpk(String cpk) {
        try {
            byte[] der = Base64.getDecoder().decode(cpk);
            byte[] canonical =
                    IdentityPublicKey.canonical(
                            KeyFactory.getInstance("EC")
                                    .generatePublic(new X509EncodedKeySpec(der)));
            if (!Base64.getEncoder().encodeToString(canonical).equals(cpk)) {
                throw new IllegalArgumentException("Noncanonical CPK");
            }
            return canonical;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid assisted CPK", invalid);
        }
    }

    private static boolean ice(String s, int min) {
        return s != null && s.length() >= min && s.length() <= 256 && s.matches("[A-Za-z0-9+/]+");
    }

    private static boolean fingerprint(String s) {
        return s != null && s.matches("sha-256 (?i:[0-9a-f]{2})(?::(?i:[0-9a-f]{2})){31}");
    }

    @Override
    public String toString() {
        return "AssistedJoin[id=" + id + "]";
    }
}
