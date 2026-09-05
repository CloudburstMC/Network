package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class NetherNetDiscoveryLifecycleTest {
    private final List<NetherNetDiscovery> discoveries = new ArrayList<>();
    private final List<EventLoopGroup> groups = new ArrayList<>();

    @AfterEach
    void tearDown() {
        discoveries.forEach(NetherNetDiscovery::close);
        groups.forEach(group -> group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly());
    }

    @Test
    void closeTerminatesOwnedEventLoopAndPreventsRebinding() throws Exception {
        EventLoopGroup group = newGroup();
        NetherNetDiscovery discovery = discovery(group);
        discovery.bind(new InetSocketAddress("127.0.0.1", 0));
        assertTrue(discovery.isActive());

        discovery.close();

        assertTrue(group.terminationFuture().await(2, TimeUnit.SECONDS));
        assertFalse(discovery.isActive());
        assertThrows(IllegalStateException.class, () -> discovery.bind(0));
    }

    @Test
    void duplicateBindKeepsTheOriginalSocketAndDoesNotCreateAnotherGroup() {
        EventLoopGroup group = newGroup();
        AtomicInteger creations = new AtomicInteger();
        NetherNetDiscovery discovery = new NetherNetDiscovery(1, () -> {
            creations.incrementAndGet();
            return group;
        });
        discoveries.add(discovery);
        discovery.bind(new InetSocketAddress("127.0.0.1", 0));

        assertThrows(IllegalStateException.class, () -> discovery.bind(0));

        assertEquals(1, creations.get());
        assertTrue(discovery.isActive());
        assertFalse(group.isShuttingDown());
    }

    @Test
    void failedBindTerminatesItsEventLoop() throws Exception {
        EventLoopGroup group = newGroup();
        NetherNetDiscovery discovery = discovery(group);
        try (DatagramSocket occupied = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            assertThrows(Exception.class, () -> discovery.bind((InetSocketAddress) occupied.getLocalSocketAddress()));
        }

        assertTrue(group.terminationFuture().await(2, TimeUnit.SECONDS));
        assertFalse(discovery.isActive());
    }

    @Test
    void interruptedBindRestoresInterruptAndTerminatesItsEventLoop() throws Exception {
        EventLoopGroup group = newGroup();
        CountDownLatch loopBlocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch groupAcquired = new CountDownLatch(1);
        group.execute(() -> {
            loopBlocked.countDown();
            try {
                resume.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        NetherNetDiscovery discovery = new NetherNetDiscovery(1, () -> {
            groupAcquired.countDown();
            return group;
        });
        discoveries.add(discovery);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread binder = new Thread(() -> {
            try {
                discovery.bind(new InetSocketAddress("127.0.0.1", 0));
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "test-discovery-bind");
        try {
            assertTrue(loopBlocked.await(2, TimeUnit.SECONDS));
            binder.start();
            assertTrue(groupAcquired.await(2, TimeUnit.SECONDS));
            binder.interrupt();
            binder.join(2000);

            assertFalse(binder.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(interrupted.get());
        } finally {
            resume.countDown();
            binder.interrupt();
            binder.join(2000);
        }
        assertTrue(group.terminationFuture().await(2, TimeUnit.SECONDS));
        assertFalse(discovery.isActive());
    }

    @Test
    void scannerCallbackReceivesMultipleResponses() throws Exception {
        NetherNetDiscovery discovery = discovery(newGroup());
        discovery.bind(new InetSocketAddress("127.0.0.1", 0));
        List<Long> servers = new CopyOnWriteArrayList<>();
        CountDownLatch responses = new CountDownLatch(2);
        try (DatagramSocket responder = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            responder.setSoTimeout(2000);
            discovery.sendDiscoveryRequest((InetSocketAddress) responder.getLocalSocketAddress(), (sender, payload) -> {
                try {
                    servers.add(sender);
                    responses.countDown();
                } finally {
                    payload.release();
                }
            });
            DatagramPacket request = new DatagramPacket(new byte[2048], 2048);
            responder.receive(request);
            for (long sender : new long[]{2, 3}) {
                ByteBuf response = Unpooled.buffer();
                try {
                    response.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
                    response.writeLongLE(sender);
                    response.writeZero(8);
                    response.writeIntLE(0);
                    byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(response);
                    responder.send(new DatagramPacket(encrypted, encrypted.length, request.getSocketAddress()));
                } finally {
                    response.release();
                }
            }

            assertTrue(responses.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(2L, 3L), servers);
        }
    }

    private EventLoopGroup newGroup() {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        groups.add(group);
        return group;
    }

    private NetherNetDiscovery discovery(EventLoopGroup group) {
        NetherNetDiscovery discovery = new NetherNetDiscovery(1, () -> group);
        discoveries.add(discovery);
        return discovery;
    }
}
