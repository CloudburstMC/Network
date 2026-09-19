/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

class DiagnosticCodecTest {
    static final Gson GSON = new Gson();
    static final JsonObject FIXTURE;
    static { try (var reader = new InputStreamReader(DiagnosticCodecTest.class.getResourceAsStream("/nxs/diagnostic-v1.fixtures.json"), StandardCharsets.UTF_8)) { FIXTURE = JsonParser.parseReader(reader).getAsJsonObject(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    final Context context = GSON.fromJson(FIXTURE.get("context"), Context.class);
    final Key key = GSON.fromJson(FIXTURE.get("key"), Key.class);
    final long now = FIXTURE.get("now").getAsLong(), parent = FIXTURE.get("parentExpiresAt").getAsLong();
    final Clock clock = new Clock(() -> now, () -> 1_000_000_000L);
    final JsonObject vector = FIXTURE.getAsJsonArray("vectors").get(1).getAsJsonObject();
    final Claims claims = GSON.fromJson(vector.get("claims"), Claims.class);
    String value(JsonObject v, String name) { return v.get(name).getAsString(); }
    String value(String name) { return value(vector, name); }
    Admission open() { return DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), parent, clock); }



    @Test void budgetAndAddressCanonicalization() {
        assertArrayEquals(new int[]{243, 246, 254, 255}, new int[]{ufragLength(22), ufragLength(24), ufragLength(30), ufragLength(31)});
        assertEquals(claims.targetAddressHex(), address(4, "203.0.113.8")); assertEquals("20010db8000100000000000000000008", address(6, "2001:0DB8:0001::8"));
        for (String a : new String[]{"::ffff:203.0.113.8", "::ffff:cb00:7108", "example.com", "fe80::1%eth0", "1::2::3"}) assertThrows(IllegalArgumentException.class, () -> address(6, a));
        assertThrows(IllegalArgumentException.class, () -> address(4, "203.00.113.8"));
        assertThrows(IllegalArgumentException.class, () -> new Claims(claims.expiresAt(), claims.clientFingerprintHex(), "a".repeat(31), claims.attemptIdHex(), claims.offerDigestHex(), 9, 4, claims.targetAddressHex(), 19132, 1));
    }

    @Test void keyCredentialsParentsAndExpiryFailClosed() {
        assertNull(DiagnosticAdmissionCodec.open(context, new Key(key.keyId(), "z".repeat(32), key.notBefore(), key.retireAt()), value("localUfrag"), value("remoteUfrag"), parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), "wrong", parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag") + "=", value("remoteUfrag"), parent, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), claims.expiresAt() - 1, clock));
        assertNull(DiagnosticAdmissionCodec.open(context, key, value("localUfrag"), value("remoteUfrag"), parent, new Clock(claims::expiresAt, System::nanoTime)));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), claims.expiresAt() - 1, clock, unhex(value("nonceHex"), 12)));
    }


    @Test void incompatibleGatheredOffersAreRejectedWithoutRewrite() {
        for (String[] replace : new String[][]{{"sctp-port:5000", "sctp-port:5001"}, {"max-message-size:262144", "max-message-size:0"}, {" udp ", " tcp "}, {"198.51.100.2", "2001:db8::2"}, {"a=mid:0", "a=mid:0\r\na=mid:0"}, {"a=end-of-candidates", "a=incomplete"}, {"a=mid:0", "a=mid:0\r\na=identity:x"}}) {
            byte[] bytes = utf8(value("offer").replace(replace[0], replace[1])); Claims c = new Claims(claims.expiresAt(), claims.clientFingerprintHex(), claims.clientIcePwd(), claims.attemptIdHex(), hex(digest(bytes)), 9, 4, claims.targetAddressHex(), 19132, 1);
            assertThrows(IllegalArgumentException.class, () -> DiagnosticSdp.offer(bytes, c, value("remoteUfrag")));
        }
    }

    @Test void slowIssuerCannotMintPastItsFixedDeadline() {
        AtomicLong reads = new AtomicLong();
        Clock lateWall = new Clock(() -> reads.incrementAndGet() >= 2 ? claims.expiresAt() : now, () -> 1000);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), parent, lateWall, unhex(value("nonceHex"), 12)));
        AtomicLong ticks = new AtomicLong();
        Clock lateMono = new Clock(() -> now, () -> ticks.incrementAndGet() >= 2 ? 30_000_001_000L : 1000);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), parent, lateMono, unhex(value("nonceHex"), 12)));
    }

    @Test void noncanonicalSecretUnicodeIsRejected() { assertThrows(IllegalArgumentException.class, () -> new Key("D001", "a".repeat(32) + (char) 0xd800, key.notBefore(), key.retireAt())); }

    @Test void allSharedOriginsUseTheHttps256Subset() throws Exception {
        Path path = Path.of("../docs/external-signaling/control-v1.origins.fixtures.json"); if (!Files.exists(path)) path = Path.of("docs/external-signaling/control-v1.origins.fixtures.json");
        JsonArray rows = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonArray("vectors"); assertEquals(274, rows.size());
        for (var row : rows) {
            JsonObject v = row.getAsJsonObject(); String origin = v.get("origin").getAsString();
            if (v.get("accepted").getAsBoolean() && origin.startsWith("https://") && origin.length() <= 256) assertDoesNotThrow(() -> new Context(origin, context.hostId(), context.incarnation(), 7), origin);
            else assertThrows(IllegalArgumentException.class, () -> new Context(origin, context.hostId(), context.incarnation(), 7), origin);
        }
        for (String origin : new String[]{"https://provider.example:99999", "https://[2001:0db8:0000:0000:0000:0000:0000:0001]"}) assertThrows(IllegalArgumentException.class, () -> new Context(origin, context.hostId(), context.incarnation(), 7));
    }
    @Test void closedOfferProfileRejectsLiteAndInvalidMediaPorts() {
        for (String offer : new String[]{value("offer") + "a=ice-lite\r\n", value("offer").replace("m=application 9 ", "m=application 0 "), value("offer").replace("m=application 9 ", "m=application 65536 ")}) {
            byte[] bytes = utf8(offer); Claims c = new Claims(claims.expiresAt(), claims.clientFingerprintHex(), claims.clientIcePwd(), claims.attemptIdHex(), hex(digest(bytes)), 9, 4, claims.targetAddressHex(), 19132, 1);
            assertThrows(IllegalArgumentException.class, () -> DiagnosticSdp.offer(bytes, c, value("remoteUfrag")));
        }
        assertThrows(IllegalArgumentException.class, () -> DiagnosticSdp.offer(new byte[16385], claims, value("remoteUfrag")));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), new byte[16385], parent, clock, unhex(value("nonceHex"), 12)));
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), parent, clock, new byte[13]));
    }
    @Test void backwardIssuerClockFailsClosed() {
        AtomicLong ticks = new AtomicLong(); Clock backwards = new Clock(() -> now, () -> ticks.incrementAndGet() == 1 ? 1000 : 999);
        assertThrows(IllegalArgumentException.class, () -> issueWithNonce(context, key, claims, value("remoteUfrag"), utf8(value("offer")), parent, backwards, unhex(value("nonceHex"), 12)));
    }

    @Test void sharedAdmissionVectorsHaveDiagnosticPurposeAndCannotReservePlayerSessions() throws Exception {
        var validator=new org.cloudburstmc.netty.signaling.admission.StatelessAdmissionValidator("nxs-stateless-host-v1/"+context.incarnation(),60000);
        validator.installKeys(java.util.List.of(new org.cloudburstmc.netty.signaling.admission.StatelessAdmissionValidator.TicketKey(key.keyId(),key.secret())));
        var gate=new org.cloudburstmc.netty.signaling.admission.AdmissionGate(org.cloudburstmc.netty.signaling.admission.AdmissionGate.Limits.defaults(),validator);
        JsonArray fresh=new JsonArray();
        for(var entry:FIXTURE.getAsJsonArray("vectors")) {
            JsonObject v=entry.getAsJsonObject(); Claims c=GSON.fromJson(v.get("claims"),Claims.class); String remote=value(v,"remoteUfrag");
            var credentials=issueWithNonce(context,key,c,remote,utf8(value(v,"offer")),parent,clock,unhex(value(v,"nonceHex"),12));
            assertEquals(value(v,"localUfrag"),credentials.localUfrag()); assertEquals(value(v,"icePwd"),credentials.icePwd());
            assertEquals(ufragLength(c.clientIcePwd().length()),credentials.localUfrag().length());
            { var admitted=DiagnosticAdmissionCodec.open(context,key,credentials.localUfrag(),remote,parent,clock);
                assertNotNull(admitted); assertEquals(c,admitted.claims());
            }
            var request=new org.cloudburstmc.netty.signaling.admission.AdmissionRequest(credentials.localUfrag(),remote,new java.net.InetSocketAddress("127.0.0.1",19132));
            var admitted=validator.validate(request,now); assertNotNull(admitted); assertTrue(admitted.diagnostic());
            assertEquals("0",admitted.networkId()); assertEquals(c.attemptIdHex(),admitted.identityBindingHex()); admitted.identityVerifier().close();
            assertNull(gate.reserve(request,now,System.nanoTime())); assertEquals(0,gate.stats().sessions());
            JsonObject result=new JsonObject(); result.addProperty("name",value(v,"name")); result.addProperty("localUfrag",credentials.localUfrag()); result.addProperty("icePwd",credentials.icePwd()); fresh.add(result);
        }
        Files.createDirectories(Path.of("build")); Files.writeString(Path.of("build/diagnostic-java-admissions.json"),fresh.toString());
        validator.clear();
    }
    @Test void incarnationPurposeAndEveryCiphertextByteAreAuthenticated() {
        assertNull(DiagnosticAdmissionCodec.open(new Context(context.providerOrigin(),context.hostId(),"ff".repeat(16),context.generation()),key,value("localUfrag"),value("remoteUfrag"),parent,clock));
        for(String prefix:new String[]{"NXD1","WDA2","NXS2"}) assertNull(DiagnosticAdmissionCodec.open(context,key,prefix+value("localUfrag").substring(4),value("remoteUfrag"),parent,clock));
        byte[] envelope=unbase64(value("localUfrag").substring(8));
        for(int i=0;i<envelope.length;i++) {
            envelope[i]^=1;
            assertNull(DiagnosticAdmissionCodec.open(context,key,value("localUfrag").substring(0,8)+base64(envelope),value("remoteUfrag"),parent,clock),"byte "+i);
            envelope[i]^=1;
        }
    }
    @Test void zeroPurposeRequiresMetadataAndNonzeroPurposeRejectsIt() {
        assertThrows(IllegalArgumentException.class,()->new org.cloudburstmc.netty.signaling.admission.StatelessAdmissionCodec.Claims(claims.expiresAt(),claims.clientFingerprintHex(),5000,262144,claims.attemptIdHex(),"0",claims.clientIcePwd(),new byte[0]));
        assertThrows(IllegalArgumentException.class,()->new org.cloudburstmc.netty.signaling.admission.StatelessAdmissionCodec.Claims(claims.expiresAt(),claims.clientFingerprintHex(),5000,262144,claims.attemptIdHex(),"1",claims.clientIcePwd(),new byte[59]));
    }

}
