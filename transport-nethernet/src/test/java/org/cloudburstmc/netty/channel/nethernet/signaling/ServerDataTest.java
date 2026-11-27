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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ServerDataTest {
    /** What BDS 1.26.51 answered with, given the nonce it picked. */
    private static final String BDS_1_26_51 = "071542445320312e32362e3531204e65746865724e6574a22207312e32362e35310774657374656e76002802"
            + "00000101106436643563343535636333343537303008";
    /** What BDS 1.26.44 answered with, given the nonce it picked. */
    private static final String BDS_1_26_44 = "061d42445320312e32362e34342052616b4e657420446f776e73747265616d0774657374656e7602000000"
            + "0014000000000000011066303533326165333863353662636661" + "0408";

    @Test
    void encodesVersion7AsBdsSendsIt() {
        PongData pong = new PongData.Builder()
                .setServerName("BDS 1.26.51 NetherNet")
                .setProtocol(2193)
                .setVersion("1.26.51")
                .setLevelName("testenv")
                .setPlayerCount(0)
                .setMaxPlayerCount(20)
                .setGameType(1)
                .setOnlineAuth(true)
                .setSelfSignedAuth(true)
                .setNonce("d6d5c455cc345700")
                .setConnectionType(4)
                .build();

        assertEquals(BDS_1_26_51, ByteBufUtil.hexDump(serverData(pong, 7)));
    }

    @Test
    void encodesVersion6AsBdsSendsIt() {
        PongData pong = new PongData.Builder()
                .setServerName("BDS 1.26.44 RakNet Downstream")
                .setLevelName("testenv")
                .setPlayerCount(0)
                .setMaxPlayerCount(20)
                .setGameType(1)
                .setOnlineAuth(false)
                .setSelfSignedAuth(true)
                .setNonce("f0532ae38c56bcfa")
                .setTransportLayer(2)
                .setConnectionType(4)
                .build();

        assertEquals(BDS_1_26_44, ByteBufUtil.hexDump(serverData(pong, 6)));
    }

    @Test
    void decodesWhatBdsSends() {
        PongData v7 = decode(BDS_1_26_51);
        assertEquals(7, v7.dataVersion());
        assertEquals("BDS 1.26.51 NetherNet", v7.serverName());
        assertEquals(2193, v7.protocol());
        assertEquals("1.26.51", v7.version());
        assertEquals(20, v7.maxPlayerCount());
        assertEquals("d6d5c455cc345700", v7.nonce());

        PongData v6 = decode(BDS_1_26_44);
        assertEquals(6, v6.dataVersion());
        assertEquals("BDS 1.26.44 RakNet Downstream", v6.serverName());
        assertEquals("testenv", v6.levelName());
        assertEquals(1, v6.gameType());
        assertEquals(false, v6.onlineAuth());
        assertEquals(true, v6.selfSignedAuth());
        assertEquals("f0532ae38c56bcfa", v6.nonce());
        assertEquals(2, v6.transportLayer());
    }

    @Test
    void decodesWhatItEncodesInEveryVersion() {
        PongData pong = new PongData.Builder()
                .setServerName("Server")
                .setProtocol(2225)
                .setVersion("1.26.60")
                .setLevelName("World")
                .setPlayerCount(5)
                .setMaxPlayerCount(300)
                .setGameType(2)
                .setIsHardcore(true)
                .setOnlineAuth(true)
                .setSelfSignedAuth(false)
                .setNonce("0123456789abcdef")
                .setConnectionType(4)
                .build();

        for (int version : ServerData.VERSIONS) {
            PongData decoded = ServerData.decode(Unpooled.wrappedBuffer(ServerData.encode(pong, version)));
            assertEquals(version, decoded.dataVersion());
            assertEquals("Server", decoded.serverName(), "version " + version);
            assertEquals("World", decoded.levelName(), "version " + version);
            assertEquals(5, decoded.playerCount(), "version " + version);
            assertEquals(300, decoded.maxPlayerCount(), "version " + version);
            assertEquals(2, decoded.gameType(), "version " + version);
            assertEquals(true, decoded.isHardcore(), "version " + version);
            assertEquals(true, decoded.onlineAuth(), "version " + version);
            assertEquals(false, decoded.selfSignedAuth(), "version " + version);
            assertEquals("0123456789abcdef", decoded.nonce(), "version " + version);
            if (version >= 7) {
                assertEquals(2225, decoded.protocol());
                assertEquals("1.26.60", decoded.version());
            }
        }
    }

    @Test
    void anUnknownVersionDecodesToNothing() {
        assertNull(decode("04"));
        assertThrows(IllegalArgumentException.class, () -> ServerData.encode(new PongData.Builder().build(), 4));
    }

    /** As BDS writes a 200 byte name. */
    @Test
    void aNameOver127BytesHasATwoByteLength() {
        for (int version : new int[]{6, 7}) {
            ByteBuf data = serverData(new PongData.Builder().setServerName("a".repeat(200)).build(), version);
            data.skipBytes(1); // Version
            assertEquals(0xC8, data.readUnsignedByte(), "version " + version);
            assertEquals(0x01, data.readUnsignedByte(), "version " + version);
        }
    }

    /** The server data inside an encoded reply. */
    private static ByteBuf serverData(PongData pong, int version) {
        ByteBuf encoded = Unpooled.wrappedBuffer(ServerData.encode(pong, version));
        int length = encoded.readIntLE();
        assertEquals(encoded.readableBytes(), length);
        return Unpooled.wrappedBuffer(
                ByteBufUtil.decodeHexDump(encoded.readCharSequence(length, StandardCharsets.US_ASCII)));
    }

    private static PongData decode(String serverDataHex) {
        byte[] hex = serverDataHex.getBytes(StandardCharsets.US_ASCII);
        return ServerData.decode(Unpooled.buffer().writeIntLE(hex.length).writeBytes(hex));
    }
}
