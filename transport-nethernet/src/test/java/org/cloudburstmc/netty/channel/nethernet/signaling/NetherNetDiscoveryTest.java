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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetherNetDiscoveryTest {

    /** Each bind brings a loop thread, which kept the JVM alive after close. */
    @Test
    void closeStopsTheLoopTheSocketRanOn() throws Exception {
        NetherNetDiscovery discovery = new NetherNetDiscovery(1);
        discovery.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        assertTrue(discovery.isActive());

        discovery.close();

        assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS), "the loop outlived close");
    }

    @Test
    void aFailedBindStopsItsLoop() throws Exception {
        try (DatagramSocket taken = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            NetherNetDiscovery discovery = new NetherNetDiscovery(1);

            assertThrows(BindException.class,
                    () -> discovery.bind((InetSocketAddress) taken.getLocalSocketAddress()));

            assertTrue(discovery.group().terminationFuture().await(10, TimeUnit.SECONDS),
                    "the loop outlived the failed bind");
        }
    }

    /** A varint length, which this used to write, takes two bytes from 128 on. */
    @Test
    void aNameOver127BytesKeepsItsOneByteLength() {
        String name = "a".repeat(200);
        ByteBuf data = serverData(new PongData.Builder().setServerName(name).build());

        assertEquals(4, data.readUnsignedByte());
        assertEquals(200, data.readUnsignedByte());
        assertEquals(name, data.readCharSequence(200, StandardCharsets.UTF_8).toString());
    }

    @Test
    void aNamePast255BytesIsCutBetweenCharacters() {
        // Two bytes each, so a cut at 255 would split the 128th
        ByteBuf data = serverData(new PongData.Builder().setServerName("\u00e9".repeat(150)).build());

        data.skipBytes(1);
        int length = data.readUnsignedByte();
        assertEquals(254, length);
        assertEquals("\u00e9".repeat(127), data.readCharSequence(length, StandardCharsets.UTF_8).toString());
    }

    @Test
    void noPlayersOnlineIsReportedAsOne() {
        ByteBuf data = serverData(new PongData.Builder().setServerName("s").setLevelName("l")
                .setPlayerCount(0).setMaxPlayerCount(20).build());

        data.skipBytes(1 + 2 + 2 + 1); // Version, both names, game type
        assertEquals(1, data.readIntLE());
        assertEquals(20, data.readIntLE());
    }

    /** The server data inside an encoded pong. */
    private static ByteBuf serverData(PongData pong) {
        ByteBuf encoded = Unpooled.wrappedBuffer(NetherNetDiscovery.encodePong(pong));
        int length = encoded.readIntLE();
        assertEquals(encoded.readableBytes(), length);
        return Unpooled.wrappedBuffer(
                ByteBufUtil.decodeHexDump(encoded.readCharSequence(length, StandardCharsets.US_ASCII)));
    }
}
