package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(5)
class NetherNetDiscoverySignalingTest {
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 7551);

    @Test
    void unansweredDiscoveryTimesOutAndClearsItsCallback() throws Exception {
        StubDiscovery discovery = new StubDiscovery();
        NetherNetDiscoverySignaling signaling = signaling(discovery, 20);
        try {
            CompletableFuture<?> connected = signaling.connect(REMOTE);

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> connected.get(2, TimeUnit.SECONDS));

            assertInstanceOf(TimeoutException.class, error.getCause());
            assertNull(discovery.callback.get());
        } finally {
            signaling.close();
        }
    }

    @Test
    void closeFailsPendingConnectAndPreventsFutureBinds() throws Exception {
        StubDiscovery discovery = new StubDiscovery();
        NetherNetDiscoverySignaling signaling = signaling(discovery, 10_000);
        CompletableFuture<?> connected = signaling.connect(REMOTE);

        signaling.close();

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> connected.get(2, TimeUnit.SECONDS));
        assertInstanceOf(ClosedChannelException.class, error.getCause());
        assertTrue(discovery.closed);
        assertNull(discovery.callback.get());
        assertTrue(signaling.connect(REMOTE).isCompletedExceptionally());
        assertThrows(IllegalStateException.class, () -> signaling.bind(new InetSocketAddress(0)));
        assertEquals(1, discovery.binds);
    }

    @Test
    void retriesShareThePendingFutureAndResendTheDiscoveryPacket() {
        StubDiscovery discovery = new StubDiscovery();
        NetherNetDiscoverySignaling signaling = signaling(discovery, 10_000);
        try {
            CompletableFuture<?> first = signaling.connect(REMOTE);
            CompletableFuture<?> retry = signaling.connect(REMOTE);

            assertSame(first, retry);
            assertEquals(2, discovery.requests);
            assertEquals(1, discovery.binds);
            assertFalse(first.isDone());
        } finally {
            signaling.close();
        }
    }

    @Test
    void responseReleasesItsBufferAndCompletesOnlyItsOwnAttempt() throws Exception {
        StubDiscovery discovery = new StubDiscovery();
        NetherNetDiscoverySignaling signaling = signaling(discovery, 10_000);
        try {
            CompletableFuture<?> cancelled = signaling.connect(REMOTE);
            BiConsumer<Long, ByteBuf> stale = discovery.callback.get();
            cancelled.cancel(false);
            assertNull(discovery.callback.get());
            CompletableFuture<?> replacement = signaling.connect(REMOTE);
            BiConsumer<Long, ByteBuf> current = discovery.callback.get();

            ByteBuf stalePayload = Unpooled.buffer().writeByte(1);
            stale.accept(99L, stalePayload);

            assertEquals(0, stalePayload.refCnt());
            assertFalse(replacement.isDone());
            assertSame(current, discovery.callback.get());

            ByteBuf payload = Unpooled.buffer().writeByte(2);
            current.accept(42L, payload);

            replacement.get(2, TimeUnit.SECONDS);
            assertEquals(0, payload.refCnt());
            assertNull(discovery.callback.get());
            signaling.sendSignal("0", "Ping");
            assertEquals(42, discovery.lastTargetId);
            assertFalse(signaling.connect(REMOTE).isDone());
            assertEquals(3, discovery.requests, "A new handshake must rediscover a restarted server's ID");
        } finally {
            signaling.close();
        }
    }

    @Test
    void sendFailureCompletesConnectAndClearsItsCallback() {
        StubDiscovery discovery = new StubDiscovery();
        IllegalStateException failure = new IllegalStateException("send failed");
        discovery.sendFailure = failure;
        NetherNetDiscoverySignaling signaling = signaling(discovery, 10_000);
        try {
            CompletableFuture<?> connected = signaling.connect(REMOTE);

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> connected.get(2, TimeUnit.SECONDS));

            assertSame(failure, error.getCause());
            assertNull(discovery.callback.get());
        } finally {
            signaling.close();
        }
    }

    @Test
    void delayedResponseFromAnotherTargetCannotCompleteTheReplacementAttempt() throws Exception {
        try (DatagramSocket firstServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             DatagramSocket secondServer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            firstServer.setSoTimeout(2000);
            secondServer.setSoTimeout(2000);
            CountDownLatch oldResponseHandled = new CountDownLatch(1);
            NetherNetDiscovery discovery = new NetherNetDiscovery(1) {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, io.netty.channel.socket.DatagramPacket packet) throws Exception {
                    super.channelRead0(ctx, packet);
                    if (packet.sender().equals(firstServer.getLocalSocketAddress())) {
                        oldResponseHandled.countDown();
                    }
                }
            };
            NetherNetDiscoverySignaling signaling = new NetherNetDiscoverySignaling(1,
                    new InetSocketAddress("127.0.0.1", 0), discovery, 10_000);
            try {
                CompletableFuture<?> first = signaling.connect(firstServer.getLocalSocketAddress());
                DatagramPacket firstRequest = receive(firstServer);
                assertTrue(first.cancel(false));
                CompletableFuture<?> second = signaling.connect(secondServer.getLocalSocketAddress());
                DatagramPacket secondRequest = receive(secondServer);

                respond(firstServer, firstRequest, 11);
                assertTrue(oldResponseHandled.await(2, TimeUnit.SECONDS));
                assertFalse(second.isDone(), "A delayed response from the previous target must be ignored");

                respond(secondServer, secondRequest, 22);
                second.get(2, TimeUnit.SECONDS);
                signaling.sendSignal("0", "Ping");
                DatagramPacket signal = receive(secondServer);
                ByteBuf encrypted = Unpooled.wrappedBuffer(signal.getData(), signal.getOffset(), signal.getLength());
                ByteBuf decrypted = null;
                try {
                    decrypted = NetherNetConstants.decryptDiscoveryPacket(encrypted);
                    assertEquals(NetherNetConstants.ID_DISCOVERY_MESSAGE, decrypted.readUnsignedShortLE());
                    decrypted.skipBytes(16);
                    assertEquals(22, decrypted.readLongLE());
                } finally {
                    if (decrypted != null) {
                        decrypted.release();
                    }
                    encrypted.release();
                }
            } finally {
                signaling.close();
            }
        }
    }

    private static DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return packet;
    }

    private static void respond(DatagramSocket socket, DatagramPacket request, long networkId) throws Exception {
        ByteBuf response = Unpooled.buffer();
        try {
            response.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
            response.writeLongLE(networkId);
            response.writeZero(8);
            response.writeIntLE(0);
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(response);
            socket.send(new DatagramPacket(encrypted, encrypted.length, request.getSocketAddress()));
        } finally {
            response.release();
        }
    }

    private static NetherNetDiscoverySignaling signaling(StubDiscovery discovery, long timeoutMillis) {
        return new NetherNetDiscoverySignaling(1, new InetSocketAddress(0), discovery, timeoutMillis);
    }

    private static final class StubDiscovery extends NetherNetDiscovery {
        private final AtomicReference<BiConsumer<Long, ByteBuf>> callback = new AtomicReference<>();
        private volatile boolean active;
        private volatile boolean closed;
        private int binds;
        private int requests;
        private long lastTargetId;
        private RuntimeException sendFailure;

        private StubDiscovery() {
            super(1);
        }

        @Override
        public void bind(InetSocketAddress address) {
            binds++;
            active = true;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        void sendDiscoveryRequestToPeer(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
            requests++;
            callback.set(onServerFound);
            if (sendFailure != null) {
                throw sendFailure;
            }
        }

        @Override
        void clearDiscoveryCallback(BiConsumer<Long, ByteBuf> callback) {
            this.callback.compareAndSet(callback, null);
        }

        @Override
        public void sendSignal(InetSocketAddress recipient, long targetNetworkId, String data) {
            lastTargetId = targetNetworkId;
        }

        @Override
        public void close() {
            closed = true;
            active = false;
            callback.set(null);
        }
    }
}
