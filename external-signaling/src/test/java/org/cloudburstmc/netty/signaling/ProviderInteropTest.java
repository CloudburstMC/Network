package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProviderInteropTest {
    static JsonObject fixture(String name) throws Exception {
        try (var in = ProviderInteropTest.class.getResourceAsStream("/nxs/" + name)) {
            return JsonParser.parseString(
                            new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void verifiesJavaScriptCanonicalBytesAndRejectsMutations() throws Exception {
        JsonObject f = fixture("nxs-v1.fixtures.json"), c = f.getAsJsonObject("challenge"), pub =
                f.getAsJsonObject("publicKeyJwk");
        assertEquals(c.get("thumbprint").getAsString(), ProviderCrypto.thumbprint(pub));
        assertEquals(c.get("contextDigest").getAsString(), ProviderCrypto.contextDigest(c.getAsJsonObject("context")));
        assertEquals(f.getAsJsonObject("proof").get("payload").getAsString(),
                ProviderCrypto.proof(c, f.get("proofNonce").getAsString(), f.get("idempotencyKey").getAsString()));
        for (String field : List.of("proof", "request")) {
            JsonObject v = f.getAsJsonObject(field);
            assertTrue(ProviderCrypto.verify(pub, v.get("signature").getAsString(), v.get("payload").getAsString()));
            assertFalse(
                    ProviderCrypto.verify(pub, v.get("signature").getAsString(), v.get("payload").getAsString() + " "));
        }
        JsonObject request = f.getAsJsonObject("request"), i = request.getAsJsonObject("input");
        assertEquals(request.get("payload").getAsString(),
                ProviderCrypto.request(i.get("audience").getAsString(), i.get("method").getAsString(),
                        i.get("path").getAsString(), i.get("timestamp").getAsLong(), i.get("instanceId").getAsString(),
                        i.get("keyId").getAsString(), i.get("idempotencyKey").getAsString(),
                        i.get("generation").getAsLong(), i.get("sequence").getAsLong(), i.get("body").getAsString()));
        JsonObject optional = pub.deepCopy();
        optional.addProperty("ext", false);
        assertEquals(ProviderCrypto.thumbprint(pub), ProviderCrypto.thumbprint(optional));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCrypto.thumbprint(f.getAsJsonObject("privateKeyJwk")));
        KeyPair keys = ProviderCrypto.generate();
        String signature = ProviderCrypto.sign(keys.getPrivate(), "test");
        assertEquals(96, ProviderCrypto.decode(signature).length);
        assertTrue(ProviderCrypto.verify(ProviderCrypto.publicJwk(keys.getPublic()), signature, "test"));
        Signature der = Signature.getInstance("SHA384withECDSA");
        der.initSign(keys.getPrivate());
        der.update("test".getBytes());
        assertFalse(ProviderCrypto.verify(ProviderCrypto.publicJwk(keys.getPublic()), ProviderCrypto.base64(der.sign()),
                "test"));
    }

    @Test
    void durablePrivateStateAndDuplicateDirectoryFencing(@TempDir Path dir) throws Exception {
        try (ProviderStateStore s = new ProviderStateStore(dir)) {
            JsonObject data = new JsonObject();
            data.addProperty("privateKey", "fixture-only");
            s.write(data);
            assertEquals(data, s.read());
            assertThrows(IOException.class, () -> new ProviderStateStore(dir));
            assertEquals("rw-------", PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(dir.resolve("provider-state.json"))));
        }
        try (ProviderStateStore s = new ProviderStateStore(dir)) {
            assertEquals("fixture-only", s.read().get("privateKey").getAsString());
        }
    }

    @Test
    void exactStatusBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ServerStatus("name", "", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerStatus("name\n", "", 1, 0));
        assertDoesNotThrow(() -> new ServerStatus("name", "", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderClient.Health(true, 1, null, null));
    }

    @Test
    void exactBedrockProtocolBounds() {
        List<Integer> most = java.util.stream.IntStream.rangeClosed(1, 512).boxed().toList();
        assertDoesNotThrow(() -> new ProviderClient.BedrockProtocols(List.of(1), "1"));
        assertDoesNotThrow(() -> new ProviderClient.BedrockProtocols(most, "1".repeat(64)));
        assertDoesNotThrow(() -> new ProviderClient.BedrockProtocols(List.of(Integer.MAX_VALUE), "\uD83D\uDE00".repeat(64)));
        List<Integer> tooMany = java.util.stream.IntStream.rangeClosed(1, 513).boxed().toList();
        for (List<Integer> accepted : Arrays.<List<Integer>>asList(null, List.of(), tooMany, List.of(0), List.of(-1),
                List.of(2, 1), List.of(1, 1), Arrays.asList(1, null))) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderClient.BedrockProtocols(accepted, "26.60"),
                    String.valueOf(accepted));
        }
        for (String version : Arrays.asList(null, "", "1".repeat(65), "26.60\n", "26\u007f", "26\u0085",
                "26\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException.class, () -> new ProviderClient.BedrockProtocols(List.of(1), version),
                    String.valueOf(version));
        }

        var source = new ArrayList<>(List.of(818, 2225));
        var protocols = new ProviderClient.BedrockProtocols(source, "26.60");
        source.add(3000);
        assertEquals(List.of(818, 2225), protocols.accepted(), "A defensive copy");
        assertThrows(UnsupportedOperationException.class, () -> protocols.accepted().add(3000));
        assertEquals("{\"accepted\":[818,2225],\"version\":\"26.60\"}", protocols.toJson().toString());
    }
}
