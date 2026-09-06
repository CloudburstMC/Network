package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NetherNetDiscoveryProtocolTest {
    @Test
    void receivesACompleteLargeUdpSignalWithVanillasUnderstatedLength() throws Exception {
        try (Discovery discovery = new Discovery()) {
            CompletableFuture<String> received = new CompletableFuture<>();
            discovery.value.registerSignalHandler(42, received::complete);
            String signal = "CONNECTRESPONSE 42 v=0\r\na=padding:" + "x".repeat(6000);
            byte[] encrypted = encrypt(message(20, 1, 1140, signal));
            assertTrue(encrypted.length > 2048);

            discovery.client.send(new DatagramPacket(encrypted, encrypted.length, discovery.address()));

            assertEquals(signal, received.get(2, TimeUnit.SECONDS));
            assertEquals(discovery.sender(), discovery.peers.get(20));
        }
    }

    @Test
    void malformedUnknownAndWrongRecipientMessagesDoNotTeachRoutes() throws Exception {
        try (Discovery discovery = new Discovery()) {
            AtomicInteger received = new AtomicInteger();
            discovery.value.registerSignalHandler(42, signal -> received.incrementAndGet());
            String valid = "CONNECTRESPONSE 42 v=0";
            List<ByteBuf> invalid = new ArrayList<>();
            invalid.add(header(NetherNetConstants.ID_DISCOVERY_MESSAGE, 20));
            invalid.add(header(NetherNetConstants.ID_DISCOVERY_MESSAGE, 20).writeLongLE(1));
            invalid.add(message(20, 1, -1, valid));
            invalid.add(message(20, 1, Integer.MAX_VALUE, valid));
            invalid.add(message(20, 99, valid.length(), valid));
            invalid.add(message(20, 1, 0, "UNKNOWN 42 data"));
            invalid.add(message(20, 1, 0, "CONNECTRESPONSE nope data"));
            invalid.add(message(20, 1, 0, "CONNECTRESPONSE 42"));
            invalid.add(header(0x7fff, 20));
            invalid.add(Unpooled.buffer().writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST).writeLongLE(20));
            invalid.add(header(NetherNetConstants.ID_DISCOVERY_REQUEST, 20).writeByte(1));
            try {
                for (ByteBuf packet : invalid) {
                    discovery.receive(packet);
                    assertNull(discovery.peers.get(20));
                }
            } finally {
                invalid.forEach(packet -> {
                    if (packet.refCnt() != 0) {
                        packet.release();
                    }
                });
            }
            assertEquals(0, received.get());
            discovery.receive(message(20, 0, 0, "CONNECTERROR 42"));
            assertEquals(1, received.get());
        }
    }

    @Test
    void malformedResponsesAreDroppedWithoutTransferringPayloadOwnership() throws Exception {
        try (Discovery discovery = new Discovery()) {
            AtomicInteger responses = new AtomicInteger();
            AtomicReference<ByteBuf> received = new AtomicReference<>();
            discovery.value.sendDiscoveryRequest(discovery.sender(), (id, payload) -> {
                received.set(payload);
                responses.incrementAndGet();
                payload.release();
            });
            List<ByteBuf> invalid = List.of(
                    response(20).writeZero(3), response(20).writeIntLE(-1),
                    response(20).writeIntLE(4).writeBytes(new byte[]{'0', '4'}),
                    response(20).writeIntLE(2).writeBytes(new byte[]{'0', '4', '0', '0'}),
                    response(20).writeIntLE(1).writeByte('a'),
                    response(20).writeIntLE(2).writeBytes(new byte[]{'g', 'g'}));
            try {
                for (ByteBuf packet : invalid) {
                    discovery.receive(packet);
                    assertNull(discovery.peers.get(20));
                }
            } finally {
                invalid.forEach(packet -> {
                    if (packet.refCnt() != 0) {
                        packet.release();
                    }
                });
            }
            assertEquals(0, responses.get());

            discovery.receive(response(20).writeIntLE(2).writeBytes(new byte[]{'0', '4'}));

            assertEquals(1, responses.get());
            assertEquals(0, received.get().refCnt());
            assertEquals(discovery.sender(), discovery.peers.get(20));
        }
    }

    @Test
    void responsesFromAnUnrequestedSocketDoNotTeachRoutes() throws Exception {
        try (Discovery discovery = new Discovery()) {
            discovery.value.sendDiscoveryRequestToPeer(discovery.sender(), (id, payload) -> {
                payload.release();
                fail("Unexpected responder");
            });
            discovery.receive(response(20).writeIntLE(0), new InetSocketAddress("127.0.0.1", 1));
            assertNull(discovery.peers.get(20));
        }
    }

    @Test
    void callbacksCanRegisterHandlersAndQuietConnectionsKeepTheirRoutes() throws Exception {
        try (Discovery discovery = new Discovery()) {
            CompletableFuture<Void> registered = new CompletableFuture<>();
            discovery.value.setNewConnectionHandler((connectionId, sender, offer) -> {
                try {
                    CompletableFuture.runAsync(() -> discovery.value.registerSignalHandler(connectionId, signal -> { }))
                            .get(2, TimeUnit.SECONDS);
                    registered.complete(null);
                } catch (Exception e) {
                    registered.completeExceptionally(e);
                }
            });
            discovery.receive(message(20, 1, 0, "CONNECTREQUEST 42 v=0"));
            registered.get(2, TimeUnit.SECONDS);
            for (long peer = 30; peer < 35; peer++) {
                discovery.receive(header(NetherNetConstants.ID_DISCOVERY_REQUEST, peer));
            }
            discovery.now.set(100);
            assertEquals(0, discovery.peers.passiveSize());

            discovery.value.sendSignal(20, "Ping");
            DatagramPacket signal = new DatagramPacket(new byte[65535], 65535);
            discovery.client.receive(signal);
            assertTrue(signal.getLength() > 0);
            discovery.value.unregisterSignalHandler(42);
            discovery.now.set(111);
            assertThrows(IllegalArgumentException.class, () -> discovery.value.sendSignal(20, "Ping"));
            discovery.value.close();
            assertEquals(0, discovery.peers.passiveSize());
            assertEquals(0, discovery.peers.recentConnectionCount());
        }
    }

    private static ByteBuf header(int id, long peer) {
        return Unpooled.buffer().writeShortLE(id).writeLongLE(peer).writeZero(8);
    }

    private static ByteBuf response(long peer) {
        return header(NetherNetConstants.ID_DISCOVERY_RESPONSE, peer);
    }

    private static ByteBuf message(long peer, long recipient, int declaredLength, String signal) {
        return header(NetherNetConstants.ID_DISCOVERY_MESSAGE, peer).writeLongLE(recipient).writeIntLE(declaredLength)
                .writeBytes(signal.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encrypt(ByteBuf packet) throws Exception {
        try {
            return NetherNetConstants.encryptDiscoveryPacket(packet);
        } finally {
            packet.release();
        }
    }

    private static final class Discovery implements AutoCloseable {
        final AtomicLong now = new AtomicLong();
        final DiscoveryPeerRegistry peers = new DiscoveryPeerRegistry(2, 10, now::get);
        final EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final DatagramSocket client = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        ChannelHandlerContext context;
        final NetherNetDiscovery value = new NetherNetDiscovery(1, () -> group, peers) {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                context = ctx;
            }
        };

        Discovery() throws Exception {
            client.setSoTimeout(2000);
            value.bind(new InetSocketAddress("127.0.0.1", 0));
        }

        InetSocketAddress sender() {
            return (InetSocketAddress) client.getLocalSocketAddress();
        }

        InetSocketAddress address() {
            return (InetSocketAddress) context.channel().localAddress();
        }

        void receive(ByteBuf packet) throws Exception {
            receive(packet, sender());
        }

        void receive(ByteBuf packet, InetSocketAddress sender) throws Exception {
            ByteBuf encrypted = Unpooled.wrappedBuffer(encrypt(packet));
            context.executor().submit(() -> context.pipeline().fireChannelRead(
                    new io.netty.channel.socket.DatagramPacket(encrypted, address(), sender))).syncUninterruptibly();
            assertEquals(0, encrypted.refCnt());
        }

        @Override
        public void close() {
            value.close();
            client.close();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
