package org.cloudburstmc.netty.signalling.admission;

import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionFixture {
    static JsonObject fixture(String name) {
        try (var in = new InputStreamReader(
                Objects.requireNonNull(StatelessAdmissionValidatorTest.class.getResourceAsStream("/nxs/" + name)),
                StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    final JsonObject f = fixture("stateless-admission-v1.fixtures.json");
    final String token = f.getAsJsonObject("expected").get("localUfrag").getAsString();
    final String password = f.getAsJsonObject("expected").get("icePwd").getAsString();
    final String remote = f.get("clientIceUfrag").getAsString();
    final long now = f.get("now").getAsLong();

    StatelessAdmissionValidator validator(String audience) {
        var v = new StatelessAdmissionValidator(audience, f.get("maxTtlMs").getAsLong());
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001",
                f.getAsJsonObject("context").get("secret").getAsString())));
        return v;
    }

    StatelessAdmissionValidator validator() {
        return validator(f.getAsJsonObject("context").get("audience").getAsString());
    }

    AdmissionRequest request() {
        return request(token, remote);
    }

    static AdmissionRequest request(String local, String remote) {
        return new AdmissionRequest(local, remote, new InetSocketAddress("127.0.0.1", 23450));
    }

    static byte[] binding(String username, String password) {
        try {
            byte[] u = username.getBytes(StandardCharsets.US_ASCII);
            int offset = 24 + ((u.length + 3) & ~3);
            ByteBuffer b = ByteBuffer.allocate(offset + 24);
            b.putShort((short) 1).putShort((short) (b.capacity() - 20)).putInt(0x2112a442).put(new byte[12]);
            b.putShort((short) 6).putShort((short) u.length).put(u);
            b.position(offset);
            b.putShort((short) 8).putShort((short) 20);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            b.put(mac.doFinal(Arrays.copyOf(b.array(), offset)));
            return b.array();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

class StatelessAdmissionValidatorTest extends AdmissionFixture {
    @Test
    void canonicalJavaScriptTokenMatchesJavaClaims() {
        var a = validator().validate(request(), now);
        assertNotNull(a);
        var c = f.getAsJsonObject("claims");
        assertEquals(c.get("clientIcePwd").getAsString(), a.remotePassword());
        assertEquals(c.get("clientSctpPort").getAsInt(), a.remoteSctpPort());
        assertEquals(c.get("networkId").getAsString(), a.networkId());
        assertEquals(c.get("callerContextHashHex").getAsString(), a.callerContextHash());
        assertEquals(password, a.localPassword());
        assertEquals(c.get("clientFingerprintHex").getAsString(),
                a.remoteFingerprint().substring(8).replace(":", "").toLowerCase(Locale.ROOT));
        assertFalse(a.toString().contains(token));
        assertFalse(a.toString().contains(password));
    }

    @Test
    void negativeAdmissionHasNoTrustedOutput() {
        var v = validator();
        assertNull(v.validate(request(), now + 60_000));
        assertNull(v.validate(request(), now - 60_000));
        for (String audience : List.of("sig_fixture/gs_two/profile_boot_001", "sig_fixture/gs_one/profile_boot_002")) {
            assertNull(validator(audience).validate(request(), now));
        }
        String altered = token.substring(0, 90) + (token.charAt(90) == 'A' ? 'B' : 'A') + token.substring(91);
        assertNull(v.validate(request(altered, remote), now));
        assertNull(v.validate(request(token, "clientOtherUfrag"), now));
        assertThrows(IllegalArgumentException.class, () -> request(token + "=", remote));
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001",
                "a-different-secret-that-has-32-characters")));
        assertNull(v.validate(request(), now));
        v.clear();
        assertFalse(v.ready());
        assertNull(v.validate(request(), now));
    }

    @Test
    void callbackMetadataAndClaimsDoNotPrintCredentials() {
        assertFalse(request().toString().contains(token));
        assertFalse(request().toString().contains(remote));
        assertNotNull(validator().validate(request(), now)); // Native code owns STUN integrity verification.
    }

    @Test
    void keyUpdatesAreBoundedAtomicAndRedacted() {
        var v = validator();
        var duplicate =
                new StatelessAdmissionValidator.TicketKey("K002", "a-valid-background-key-of-at-least-32-bytes");
        assertThrows(IllegalArgumentException.class, () -> v.installKeys(List.of(duplicate, duplicate)));
        assertEquals(Set.of("K001"), v.keyIds());
        assertFalse(duplicate.toString().contains(duplicate.secret()));
        assertThrows(IllegalArgumentException.class, () -> v.installKeys(Collections.nCopies(9, duplicate)));
    }

    @Test
    void fixturesHavePinnedHashesAndCanonicalFrames() throws Exception {
        var provenance = fixture("provenance.json");
        assertEquals("urn:nethernet:external-signalling:v1", provenance.get("specification").getAsString());
        for (var entry : provenance.getAsJsonObject("files").entrySet()) {
            try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/nxs/" + entry.getKey()))) {
                assertEquals(entry.getValue().getAsString(), HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
        }
        for (var entry : fixture("cloudburst-protocol-vectors.v1.json").getAsJsonArray("nethernetFrames")) {
            var frame = entry.getAsJsonObject();
            var decoder = new NetherNetFrameDecoder();
            ByteBuf decoded = decoder.decode(Unpooled.wrappedBuffer(
                    HexFormat.of().parseHex(frame.get("frameHex").getAsString())), true);
            byte[] actual = decoded == null ? null : ByteBufUtil.getBytes(decoded);
            if (decoded != null) {
                decoded.release();
            }
            if (frame.getAsJsonObject("decoded").get("complete").getAsBoolean()) {
                assertArrayEquals(HexFormat.of().parseHex(frame.get("payloadHex").getAsString()), actual);
            } else {
                assertNull(actual);
                decoder.clear();
                assertEquals(0, decoder.retainedBytes());
            }
        }
    }

    @Test
    void backgroundKeyValidityBoundsDoNotExtendTokens() {
        var v = validator();
        String secret = f.getAsJsonObject("context").get("secret").getAsString();
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", secret, now + 1, now + 20_000)));
        assertNull(v.validate(request(), now));
        assertNotNull(v.validate(request(), now + 1));
        assertNull(v.validate(request(), now + 20_000));
    }

}
