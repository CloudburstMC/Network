package org.cloudburstmc.netty.signalling.admission;

import com.google.gson.*;
import org.cloudburstmc.netty.channel.nethernet.admission.*;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionFixture {
    static JsonObject fixture(String name) {
        try (var in = new InputStreamReader(Objects.requireNonNull(StatelessAdmissionValidatorTest.class.getResourceAsStream("/nxs/" + name)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    final JsonObject f = fixture("stateless-admission-v1.fixtures.json");
    final String token = f.getAsJsonObject("expected").get("localUfrag").getAsString();
    final String password = f.getAsJsonObject("expected").get("icePwd").getAsString();
    final String remote = f.get("clientIceUfrag").getAsString();
    final long now = f.get("now").getAsLong();
    StatelessAdmissionValidator validator(String audience) {
        var v = new StatelessAdmissionValidator(audience, f.get("maxTtlMs").getAsLong());
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", f.getAsJsonObject("context").get("secret").getAsString())));
        return v;
    }
    StatelessAdmissionValidator validator() { return validator(f.getAsJsonObject("context").get("audience").getAsString()); }
    static byte[] binding(String username, String password) {
        try {
            byte[] u = username.getBytes(StandardCharsets.US_ASCII);
            int offset = 24 + ((u.length + 3) & ~3);
            ByteBuffer b = ByteBuffer.allocate(offset + 24);
            b.putShort((short)1).putShort((short)(b.capacity()-20)).putInt(0x2112a442).put(new byte[12]);
            b.putShort((short)6).putShort((short)u.length).put(u);b.position(offset);
            b.putShort((short)8).putShort((short)20);
            Mac mac = Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
            b.put(mac.doFinal(Arrays.copyOf(b.array(), offset)));return b.array();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}

class StatelessAdmissionValidatorTest extends AdmissionFixture {
    @Test void canonicalJavaScriptTokenAndPacketIntegrityAgree() {
        byte[] packet = binding(token + ":" + remote, password);
        var a = validator().validate(packet, StunBinding.parse(packet), now);
        assertNotNull(a);
        var c = f.getAsJsonObject("claims");
        assertEquals(c.get("clientIcePwd").getAsString(), a.remotePassword());
        assertEquals(c.get("clientSctpPort").getAsInt(), a.remoteSctpPort());
        assertEquals(c.get("networkId").getAsString(), a.networkId());
        assertEquals(c.get("callerContextHashHex").getAsString(), a.callerContextHash());
        assertEquals(password, a.localPassword());
        assertEquals(c.get("clientFingerprintHex").getAsString(), a.remoteFingerprint().substring(8).replace(":", "").toLowerCase(Locale.ROOT));
        assertFalse(a.toString().contains(token));
        assertFalse(a.toString().contains(password));
    }
    @Test void negativeAdmissionHasNoTrustedOutput() {
        byte[] valid = binding(token + ":" + remote, password);
        var v = validator();
        assertNull(v.validate(valid, StunBinding.parse(valid), now + 60_000));
        assertNull(v.validate(valid, StunBinding.parse(valid), now - 60_000));
        for (String audience : List.of("sig_fixture/gs_two/profile_boot_001", "sig_fixture/gs_one/profile_boot_002"))
            assertNull(validator(audience).validate(valid, StunBinding.parse(valid), now));
        for (byte[] p : List.of(binding(token + ":" + remote, "forgedIntegrityPassword000"),
            binding(token.substring(0, 90) + (token.charAt(90)=='A'?'B':'A') + token.substring(91) + ":" + remote, password),
            binding(token + ":clientOtherUfrag", password), binding(token + "=:" + remote, password)))
            assertNull(v.validate(p, StunBinding.parse(p), now));
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", "a-different-secret-that-has-32-characters")));
        assertNull(v.validate(valid, StunBinding.parse(valid), now));
        v.clear();assertFalse(v.ready());
        assertNull(v.validate(valid, StunBinding.parse(valid), now));
    }
    @Test void canonicalRfcStunFixtureVerifies() {
        var stun = fixture("cloudburst-protocol-vectors.v1.json").getAsJsonObject("stun");
        // The RFC5769 vector independently verifies the header-length/HMAC rule.
        var vector = stun.getAsJsonObject("rfc5769");
        assertNotNull(vector, stun.keySet().toString());
        byte[] packet = HexFormat.of().parseHex(vector.get("packetHex").getAsString());
        var parsed = StunBinding.parse(packet);assertNotNull(parsed);
        assertTrue(parsed.verify(packet, vector.get("passwordUtf8").getAsString()));
        packet[40] ^= 1;assertFalse(parsed.verify(packet, vector.get("passwordUtf8").getAsString()));
    }
    @Test void keyUpdatesAreBoundedAtomicAndRedacted() {
        var v = validator();
        var duplicate = new StatelessAdmissionValidator.TicketKey("K002", "a-valid-background-key-of-at-least-32-bytes");
        assertThrows(IllegalArgumentException.class, () -> v.installKeys(List.of(duplicate, duplicate)));
        assertEquals(Set.of("K001"), v.keyIds());
        assertFalse(duplicate.toString().contains(duplicate.secret()));
        assertThrows(IllegalArgumentException.class, () -> v.installKeys(Collections.nCopies(9, duplicate)));
    }
    @Test void fixturesHavePinnedHashesAndCanonicalFrames() throws Exception {
        var provenance = fixture("provenance.json");
        assertEquals("urn:nethernet:external-signalling:v1", provenance.get("specification").getAsString());
        for (var entry : provenance.getAsJsonObject("files").entrySet()) {
            try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/nxs/" + entry.getKey()))) {
                assertEquals(entry.getValue().getAsString(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
        }
        for (var entry : fixture("cloudburst-protocol-vectors.v1.json").getAsJsonArray("nethernetFrames")) {
            var frame = entry.getAsJsonObject(); var decoder = new NetherNetFrameDecoder();
            byte[] actual = decoder.decode(HexFormat.of().parseHex(frame.get("frameHex").getAsString()), true);
            if (frame.getAsJsonObject("decoded").get("complete").getAsBoolean())
                assertArrayEquals(HexFormat.of().parseHex(frame.get("payloadHex").getAsString()), actual);
            else { assertNull(actual); decoder.clear(); assertEquals(0, decoder.retainedBytes()); }
        }
    }
    @Test void backgroundKeyValidityBoundsDoNotExtendTokens() {
        var v = validator(); byte[] packet = binding(token + ":" + remote, password);
        String secret = f.getAsJsonObject("context").get("secret").getAsString();
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", secret, now + 1, now + 20_000)));
        assertNull(v.validate(packet, StunBinding.parse(packet), now));
        assertNotNull(v.validate(packet, StunBinding.parse(packet), now + 1));
        assertNull(v.validate(packet, StunBinding.parse(packet), now + 20_000));
    }

}
