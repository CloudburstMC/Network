package org.cloudburstmc.netty.signaling.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.cloudburstmc.netty.signaling.ProviderClient;
import org.junit.jupiter.api.Test;

/** The bench runs only in Warden's CI, so a configuration the client refuses goes unnoticed here. */
class ProviderNativeBenchTest {

    private static final URI ORIGIN = URI.create("https://127.0.0.1:8443");

    @Test
    void anAssistedRunControlsOverWebSocket() {
        ProviderClient.Configuration configuration = ProviderNativeBench.configuration(ORIGIN, true, false, true);

        assertTrue(configuration.assistedJoins());
        assertEquals(ProviderClient.ControlTransport.AUTO, configuration.controlTransport());
    }
}
