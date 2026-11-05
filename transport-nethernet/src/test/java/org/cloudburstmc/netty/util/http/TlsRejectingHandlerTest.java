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

package org.cloudburstmc.netty.util.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsRejectingHandlerTest {
    @Test
    void closesATlsClientHello() {
        EmbeddedChannel channel = new EmbeddedChannel(new TlsRejectingHandler());
        // A handshake record, TLS 1.0 on the record layer as clients send it, then a ClientHello
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, (byte) 0xfc}));

        assertFalse(channel.isOpen(), "a TLS client was let through on the plaintext port");
        assertNull(channel.readInbound());
    }

    @Test
    void passesPlainHttpOn() {
        // A status probe and a join, which older Netty also checks for SSLv2
        for (String request : new String[]{"GET /v1/status HTTP/1.1\r\n\r\n", "POST /v1/join/4242 HTTP/1.1\r\n\r\n"}) {
            EmbeddedChannel channel = new EmbeddedChannel(new TlsRejectingHandler());
            channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));

            assertTrue(channel.isOpen(), request);
            assertNull(channel.pipeline().get(TlsRejectingHandler.class), "the check stays in the pipeline");
            ByteBuf passed = channel.readInbound();
            assertEquals(request, passed.toString(StandardCharsets.US_ASCII));
            passed.release();
        }
    }
}
