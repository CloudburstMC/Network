package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class XboxSignalingTlsTest {

    /**
     * The websocket upgrade carries the Xbox token, so the certificate has to name the host it is
     * sent to. Netty validates the chain either way, but leaves the name unchecked unless asked,
     * which would make any publicly trusted certificate good enough to receive the token.
     */
    @Test
    public void verifiesTheSignalingHostname() throws Exception {
        SslContext context = AbstractNetherNetXboxSignaling.signalingSslContext();
        SSLEngine engine = context
                .newHandler(ByteBufAllocator.DEFAULT, "signal.franchise.minecraft-services.net", 443)
                .engine();

        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm(),
                "hostname verification is off, the token would go to whoever answers");
    }
}
