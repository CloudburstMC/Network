package org.cloudburstmc.netty.signalling;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderInteropTest {
    static JsonObject fixture(String name) throws Exception { try (var in = ProviderInteropTest.class.getResourceAsStream("/" + name)) { return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); } }
    @Test void verifiesJavaScriptCanonicalBytesAndRejectsMutations() throws Exception {
        JsonObject f = fixture("nxs-v1.fixtures.json"), c = f.getAsJsonObject("challenge"), pub = f.getAsJsonObject("publicKeyJwk");
        assertEquals(c.get("thumbprint").getAsString(), ProviderCrypto.thumbprint(pub));
        assertEquals(c.get("contextDigest").getAsString(), ProviderCrypto.contextDigest(c.getAsJsonObject("context")));
        assertEquals(f.getAsJsonObject("proof").get("payload").getAsString(), ProviderCrypto.proof(c, f.get("proofNonce").getAsString(), f.get("idempotencyKey").getAsString()));
        for (String field : List.of("proof", "request")) { JsonObject v = f.getAsJsonObject(field); assertTrue(ProviderCrypto.verify(pub, v.get("signature").getAsString(), v.get("payload").getAsString())); assertFalse(ProviderCrypto.verify(pub, v.get("signature").getAsString(), v.get("payload").getAsString() + " ")); }
        JsonObject request = f.getAsJsonObject("request"), i = request.getAsJsonObject("input");
        assertEquals(request.get("payload").getAsString(), ProviderCrypto.request(i.get("audience").getAsString(), i.get("method").getAsString(), i.get("path").getAsString(), i.get("timestamp").getAsLong(), i.get("instanceId").getAsString(), i.get("keyId").getAsString(), i.get("idempotencyKey").getAsString(), i.get("generation").getAsLong(), i.get("sequence").getAsLong(), i.get("body").getAsString()));
        JsonObject optional = pub.deepCopy(); optional.addProperty("ext", false); assertEquals(ProviderCrypto.thumbprint(pub), ProviderCrypto.thumbprint(optional));
        assertThrows(IllegalArgumentException.class, () -> ProviderCrypto.thumbprint(f.getAsJsonObject("privateKeyJwk")));
        KeyPair keys = ProviderCrypto.generate(); String signature = ProviderCrypto.sign(keys.getPrivate(), "test"); assertEquals(96, ProviderCrypto.decode(signature).length); assertTrue(ProviderCrypto.verify(ProviderCrypto.publicJwk(keys.getPublic()), signature, "test"));
        Signature der = Signature.getInstance("SHA384withECDSA"); der.initSign(keys.getPrivate()); der.update("test".getBytes()); assertFalse(ProviderCrypto.verify(ProviderCrypto.publicJwk(keys.getPublic()), ProviderCrypto.base64(der.sign()), "test"));
    }
    @Test void durablePrivateStateAndDuplicateDirectoryFencing(@TempDir Path dir) throws Exception {
        try (ProviderStateStore s = new ProviderStateStore(dir)) { JsonObject data = new JsonObject(); data.addProperty("privateKey", "fixture-only"); s.write(data); assertEquals(data, s.read()); assertThrows(java.io.IOException.class, () -> new ProviderStateStore(dir)); assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("provider-state.json")))); }
        try (ProviderStateStore s = new ProviderStateStore(dir)) { assertEquals("fixture-only", s.read().get("privateKey").getAsString()); }
    }
    @Test void exactStatusBounds() { assertThrows(IllegalArgumentException.class, () -> new ServerStatus("name", 0, "version", "", 0, 1, 0)); assertThrows(IllegalArgumentException.class, () -> new ServerStatus("name\n", 1, "version", "", 0, 1, 0)); assertDoesNotThrow(() -> new ServerStatus("name", 1, "version", "", 20, 1, 0)); }
}
