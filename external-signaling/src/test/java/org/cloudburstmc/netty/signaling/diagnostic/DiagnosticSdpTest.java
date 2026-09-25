package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

class DiagnosticSdpTest {
    final JsonObject vector =
            DiagnosticCodecTest.FIXTURE.getAsJsonArray("answers").get(0).getAsJsonObject();
    final Claims claims = DiagnosticCodecTest.GSON.fromJson(vector.get("claims"), Claims.class);
    final String answer = vector.get("answer").getAsString(),
            fingerprint = vector.get("hostFingerprintHex").getAsString();

    @Test
    void allSharedAnswersRetainTheirNumericDestinationAndHostPin() {
        for (var row : DiagnosticCodecTest.FIXTURE.getAsJsonArray("answers")) {
            var v = row.getAsJsonObject();
            var c = DiagnosticCodecTest.GSON.fromJson(v.get("claims"), Claims.class);
            var sdp =
                    DiagnosticSdp.answer(
                            utf8(v.get("answer").getAsString()),
                            c,
                            v.get("hostFingerprintHex").getAsString());
            if (c.profile() == PROFILE) {
                assertEquals(c.targetAddressHex(), sdp.addressHex());
                assertEquals(c.targetPort(), sdp.port());
            } else {
                assertTrue(sdp.port() > 0);
                assertNotEquals("00".repeat(16), sdp.addressHex());
            }
        }
    }

    @Test
    void malformedOrMisboundAnswersAreRejectedBeforeNativeInstallation() {
        for (String[] change :
                new String[][] {
                    {"FE:DC", "AA:DC"},
                    {"203.0.113.8", "203.0.113.9"},
                    {"19132", "19133"},
                    {" UDP 213", " TCP 213"},
                    {"typ host", "typ relay"},
                    {"sctp-port:5000", "sctp-port:5001"},
                    {"max-message-size:262144", "max-message-size:0"},
                    {"setup:active", "setup:actpass"},
                    {"NXS1", "NXD1"},
                    {"a=mid:0", "a=mid:0\r\na=identity:player"},
                    {"a=mid:0", "a=mid:0\r\na=ice-lite"},
                    {"a=candidate:", " a=candidate:"},
                    {"a=mid:0", "a=mid:0\r\na=mid:0"}
                }) {
            String altered = answer.replace(change[0], change[1]);
            assertNotEquals(answer, altered);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DiagnosticSdp.answer(utf8(altered), claims, fingerprint));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticSdp.answer(utf8(answer), claims, "00".repeat(32)));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticSdp.answer(new byte[16385], claims, fingerprint));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticSdp.answer(new byte[] {(byte) 0xff}, claims, fingerprint));
    }

    @Test
    void assistedAnswersAllowOneAuthorizedFamilyButNoHiddenOrMultipleDestinations() {
        var c =
                new Claims(
                        claims.expiresAt(),
                        claims.clientFingerprintHex(),
                        claims.clientIcePwd(),
                        claims.attemptIdHex(),
                        claims.offerDigestHex(),
                        claims.candidateRevision(),
                        claims.family(),
                        "00".repeat(16),
                        0,
                        ASSISTED_PROFILE);
        assertDoesNotThrow(() -> DiagnosticSdp.answer(utf8(answer), c, fingerprint));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DiagnosticSdp.answer(
                                utf8(answer + "a=remote-candidates:1 127.0.0.1 19132\r\n"),
                                c,
                                fingerprint));
        String candidate =
                answer.lines().filter(l -> l.startsWith("a=candidate:")).findFirst().orElseThrow();
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticSdp.answer(utf8(answer + candidate + "\r\n"), c, fingerprint));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DiagnosticSdp.answer(
                                utf8(answer.replace(candidate + "\r\n", "")), c, fingerprint));
    }
}
