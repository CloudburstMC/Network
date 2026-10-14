/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

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
