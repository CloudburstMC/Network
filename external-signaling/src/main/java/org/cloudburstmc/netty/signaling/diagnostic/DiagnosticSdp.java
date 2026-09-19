/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** The common, closed SDP profile used by diagnostic offers and answers. */
record DiagnosticSdp(String ufrag, String password, String fingerprintHex, String addressHex,
                     int port, String digestHex) {
    static DiagnosticSdp parse(byte[] input, Claims claims, boolean answer) {
        if (input.length == 0 || input.length > DiagnosticAnswerCodec.MAX_SDP_BYTES) throw invalid();
        byte[] bytes = input.clone();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException malformed) { throw invalid(); }
        if (text.indexOf(0) >= 0 || text.replace("\r\n", "").indexOf('\r') >= 0) throw invalid();
        List<String> lines = Arrays.stream(text.split("\r?\n")).filter(line -> !line.isEmpty()).toList();
        if (answer && (lines.stream().anyMatch(line -> !line.equals(line.trim()))
                || lines.stream().anyMatch(line -> line.startsWith("a=remote-candidates:")))) throw invalid();
        String media = one(lines, "m=");
        if (!media.matches("application [0-9]{1,5} UDP/DTLS/SCTP webrtc-datachannel")) throw invalid();
        integer(Long.parseLong(media.split(" ")[1]), 1, 65535);
        if (!one(lines, "a=group:").equals("BUNDLE 0") || !one(lines, "a=mid:").equals("0")
                || !one(lines, "a=setup:").equals(answer ? "active" : "actpass")
                || !one(lines, "a=sctp-port:").equals("5000") || !one(lines, "a=max-message-size:").equals("262144")
                || lines.stream().anyMatch(line -> line.startsWith("a=ice-lite") || line.startsWith("a=identity:"))
                || lines.stream().filter(line -> line.equals("a=end-of-candidates")).count() != 1) throw invalid();
        String fingerprint = one(lines, "a=fingerprint:");
        if (!fingerprint.matches("sha-256 (?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")) throw invalid();
        String ufrag = one(lines, "a=ice-ufrag:");
        String[] candidate = one(lines, "a=candidate:").split(" ", -1);
        if (candidate.length < 8 || candidate.length > 20 || candidate.length % 2 != 0
                || !candidate[0].matches("[A-Za-z0-9+/]{1,32}") || !candidate[1].equals("1")
                || !candidate[2].equalsIgnoreCase("udp") || !candidate[3].matches("[0-9]{1,10}")
                || Long.parseLong(candidate[3]) > 0xffffffffL || !candidate[5].matches("[0-9]{1,5}")
                || !candidate[6].equals("typ")) throw invalid();
        int port = Integer.parseInt(candidate[5]);
        integer(port, 1, 65535);
        boolean reflexive = candidate[7].equals("srflx");
        if (!candidate[7].equals("host") && !(reflexive && (answer || claims.profile() == ASSISTED_PROFILE))) throw invalid();
        Set<String> extensions = new HashSet<>();
        for (int index = 8; index < candidate.length; index += 2) {
            String name = candidate[index], value = candidate[index + 1];
            if (!extensions.add(name)) throw invalid();
            switch (name) {
                case "raddr" -> {
                    if (!reflexive) throw invalid();
                    int family = answer && claims.profile() == PROFILE ? (value.contains(":") ? 6 : 4) : claims.family();
                    address(family, value);
                }
                case "rport" -> {
                    if (!reflexive || !value.matches(answer ? "[0-9]{1,10}" : "[0-9]{1,5}")) throw invalid();
                    integer(Long.parseLong(value), answer ? 0 : 1, 65535);
                }
                case "ufrag" -> { if (!value.equals(ufrag)) throw invalid(); }
                case "generation", "network-id", "network-cost" -> {
                    if (!value.matches("[0-9]{1,10}")) throw invalid();
                    integer(Long.parseLong(value), 0, 0xffffffffL);
                }
                default -> throw invalid();
            }
        }
        if (extensions.contains("raddr") != extensions.contains("rport")) throw invalid();
        return new DiagnosticSdp(ufrag, one(lines, "a=ice-pwd:"), fingerprint.substring(8).replace(":", "").toLowerCase(Locale.ROOT),
                address(claims.family(), candidate[4]), port, hex(digest(bytes)));
    }

    InetSocketAddress endpoint(int family) {
        byte[] bytes = unhex(addressHex, 16);
        try { return new InetSocketAddress(InetAddress.getByAddress(family == 4 ? Arrays.copyOfRange(bytes, 12, 16) : bytes), port); }
        catch (UnknownHostException malformed) { throw invalid(); }
    }

    @Override public String toString() { return "DiagnosticSdp[redacted]"; }

    private static String one(List<String> lines, String prefix) {
        List<String> values = lines.stream().filter(line -> line.startsWith(prefix)).toList();
        if (values.size() != 1) throw invalid();
        return values.get(0).substring(prefix.length());
    }
}
