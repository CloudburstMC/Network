package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscovery;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NetherNetScannerTest {
    @ParameterizedTest
    @CsvSource({"0, 2, 4", "-1, 64, 128", "2147483647, -2147483648, 16384"})
    void versionSixRoundTripsLongUnicodeNamesAndSignedEnums(int gameType, int transport, int connection) throws Exception {
        PongData pong = new PongData("世界😀".repeat(40), "Ž".repeat(80), gameType, 3, 10, false, true, transport, connection);
        ByteBuf response = discover(pong);
        try {
            NetherNetScanner.ServerInfo info = NetherNetScanner.readResponse(response);
            assertEquals(6, info.version());
            assertEquals(pong, info.data());
        } finally {
            response.release();
        }
    }

    @Test
    void discoverySendsTheStableVersionSixCapture() throws Exception {
        ByteBuf response = discover(new PongData("Dedicated Server", "Bedrock level", 0, 0, 10,
                false, false, 2, 4, true, false, "3e8a5e7a932c5aa4"));
        try {
            int length = response.readIntLE();
            assertEquals(128, length);
            try (var stream = getClass().getResourceAsStream("/discovery/stable-1.26.45.1-v6.hex")) {
                assertNotNull(stream);
                assertEquals(new String(stream.readAllBytes(), StandardCharsets.US_ASCII).strip(),
                        response.readCharSequence(length, StandardCharsets.US_ASCII).toString());
            }
        } finally {
            response.release();
        }
    }

    @Test
    void negativeAndMultiByteEnumValuesUseZigZagVarints() throws Exception {
        ByteBuf response = discover(new PongData("", "", -1, 0, 0, false, false, 64, 128, false, true, "a"));
        try {
            int length = response.readIntLE();
            assertEquals("06000001000000000000000000000001016180018002",
                    response.readCharSequence(length, StandardCharsets.US_ASCII).toString());
        } finally {
            response.release();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"06", "0680", "068001", "06ffffffff0f", "068080808010", "06808080808000", "06000000"})
    void truncatedAndOverflowingFieldsAreRejected(String hex) {
        ByteBuf response = Unpooled.buffer().writeIntLE(hex.length());
        response.writeCharSequence(hex, StandardCharsets.US_ASCII);
        try {
            assertThrows(RuntimeException.class, () -> NetherNetScanner.readResponse(response));
        } finally {
            response.release();
        }
    }

    @Test
    void largestAdvertisementFitsUdpAndAnOversizedUpdatePreservesIt() throws Exception {
        PongData largest = new PongData("x".repeat(32701), "", 0, 0, 0,
                false, false, 2, 4, true, false, "a");
        PongData oversized = new PongData("x".repeat(32702), "", 0, 0, 0,
                false, false, 2, 4, true, false, "a");
        ByteBuf response = discover(largest, discovery ->
                assertThrows(IllegalArgumentException.class, () -> discovery.setPongData(oversized)));
        try {
            assertEquals(4 + 2 * 32723, response.readableBytes());
            assertEquals(largest, NetherNetScanner.readResponse(response).data());
        } finally {
            response.release();
        }
    }

    private static ByteBuf discover(PongData pong) throws Exception {
        return discover(pong, ignored -> {});
    }

    private static ByteBuf discover(PongData pong, Consumer<NetherNetDiscovery> afterUpdate) throws Exception {
        AtomicReference<Channel> channel = new AtomicReference<>();
        NetherNetDiscovery discovery = new NetherNetDiscovery(1) {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                channel.set(ctx.channel());
            }
        };
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            socket.setSoTimeout(2000);
            discovery.setPongData(pong);
            afterUpdate.accept(discovery);
            discovery.bind(new InetSocketAddress("127.0.0.1", 0));
            ByteBuf request = Unpooled.buffer().writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST)
                    .writeLongLE(2).writeZero(8);
            byte[] encrypted;
            try {
                encrypted = NetherNetConstants.encryptDiscoveryPacket(request);
            } finally {
                request.release();
            }
            socket.send(new DatagramPacket(encrypted, encrypted.length, channel.get().localAddress()));
            DatagramPacket received = new DatagramPacket(new byte[65535], 65535);
            socket.receive(received);
            ByteBuf packet = Unpooled.wrappedBuffer(received.getData(), received.getOffset(), received.getLength());
            ByteBuf response;
            try {
                response = NetherNetConstants.decryptDiscoveryPacket(packet);
            } finally {
                packet.release();
            }
            assertNotNull(response);
            assertEquals(NetherNetConstants.ID_DISCOVERY_RESPONSE, response.readUnsignedShortLE());
            response.skipBytes(16);
            return response;
        } finally {
            discovery.close();
            Channel bound = channel.get();
            if (bound != null) {
                bound.eventLoop().terminationFuture().awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
        }
    }
}
