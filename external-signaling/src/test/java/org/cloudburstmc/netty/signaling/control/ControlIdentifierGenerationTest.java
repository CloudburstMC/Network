package org.cloudburstmc.netty.signaling.control;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ControlIdentifierGenerationTest {
    @Test void productionIdentifierPrefixKeepsAll192RandomBitsAndFitsBothGrammars() {
        var ids = ControlClientCoordinator.secureIdentifiers();
        for (int i = 0; i < 128; i++) {
            String id = ids.get();
            assertEquals(33, id.length()); assertEquals('c', id.charAt(0));
            assertEquals(24, Base64.getUrlDecoder().decode(id.substring(1)).length);
            ControlJson.identifier(id); ControlJson.opaque(id);
        }
    }
    @Test void invalidInjectedIdsCannotReachTheJournalOrNetwork() throws Exception {
        for (String id : new String[]{"_" + "a".repeat(31), "-" + "a".repeat(31), "short", "a".repeat(129)}) {
            var h = new ControlClientCoordinatorTest.Harness(); h.ready();
            int writes = h.journal.writes.size(), calls = h.bootstrapCalls;
            h.identifierSupplier = () -> id;
            assertThrows(IllegalArgumentException.class, () -> h.client.submit("heartbeat", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
            assertThrows(IllegalArgumentException.class, h.client::rotateMachineKey);
            assertEquals(writes, h.journal.writes.size()); assertNull(h.client.snapshot().pending());
            assertEquals(calls, h.bootstrapCalls); assertTrue(h.operations.isEmpty()); assertTrue(h.links.get(0).sent.isEmpty());
        }
    }
}
