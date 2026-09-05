package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class NetherNetHttpSignalingLifecycleTest {
    private final EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final List<EventLoopGroup> acceptGroups = new ArrayList<>();
    private final List<NetherNetHttpSignaling> listeners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        listeners.forEach(NetherNetHttpSignaling::close);
        acceptGroups.forEach(group -> group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly());
        worker.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void failedBindTerminatesTheAcceptGroupAndPreservesTheWorker() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        NetherNetHttpSignaling signaling = listener(accept);
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            assertThrows(ConnectException.class, () -> signaling.bind(occupied.getLocalSocketAddress()));
        }

        assertTrue(accept.terminationFuture().await(2, TimeUnit.SECONDS));
        assertNull(signaling.boundAddress());
        assertFalse(worker.isShuttingDown());
        assertEquals(42, worker.submit(() -> 42).get(2, TimeUnit.SECONDS).intValue());
    }

    @Test
    void duplicateBindPreservesTheOriginalListener() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        AtomicInteger creations = new AtomicInteger();
        NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> null, worker, () -> {
            creations.incrementAndGet();
            return accept;
        });
        listeners.add(signaling);
        signaling.setNewConnectionHandler((connection, network, offer) -> {});
        signaling.bind(new InetSocketAddress("127.0.0.1", 0));
        InetSocketAddress original = signaling.boundAddress();

        assertThrows(ConnectException.class, () -> signaling.bind(new InetSocketAddress("127.0.0.1", 0)));

        assertEquals(original, signaling.boundAddress());
        assertEquals(1, creations.get());
        assertEquals(200, probe(original));
        assertFalse(accept.isShuttingDown());
    }

    @Test
    void closedListenerCannotBeRebound() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        NetherNetHttpSignaling signaling = listener(accept);
        signaling.bind(new InetSocketAddress("127.0.0.1", 0));

        signaling.close();

        assertThrows(ConnectException.class, () -> signaling.bind(new InetSocketAddress("127.0.0.1", 0)));
        assertTrue(accept.terminationFuture().await(2, TimeUnit.SECONDS));
        assertNull(signaling.boundAddress());
        assertFalse(worker.isShuttingDown());
    }

    @Test
    void bindDoesNotConsultTheTlsSupplier() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        AtomicInteger calls = new AtomicInteger();
        NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("TLS context unavailable");
        }, worker, () -> accept);
        listeners.add(signaling);
        signaling.setNewConnectionHandler((connection, network, offer) -> {});

        signaling.bind(new InetSocketAddress("127.0.0.1", 0));

        assertEquals(0, calls.get());
        assertEquals(200, probe(signaling.boundAddress()));
        assertEquals(0, calls.get());
    }

    @Test
    void closeClosesAcceptedIdleConnectionsWithoutStoppingTheWorker() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        NetherNetHttpSignaling signaling = listener(accept);
        signaling.bind(new InetSocketAddress("127.0.0.1", 0));
        InetSocketAddress address = signaling.boundAddress();
        try (Socket idle = new Socket()) {
            idle.connect(address, 2000);
            idle.setSoTimeout(2000);
            // The next accepted connection completes a request, so the
            // earlier idle connection has also reached the worker loop.
            assertEquals(200, probe(address));

            signaling.close();

            assertEquals(-1, idle.getInputStream().read());
            assertTrue(accept.terminationFuture().await(2, TimeUnit.SECONDS));
            assertFalse(worker.isShuttingDown());
        }
    }

    @Test
    void interruptedBindRestoresInterruptAndCleansUpOwnedResources() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        block(accept, blocked, resume);
        NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> null, worker, () -> {
            acquired.countDown();
            return accept;
        });
        listeners.add(signaling);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread binder = new Thread(() -> {
            try {
                signaling.bind(new InetSocketAddress("127.0.0.1", 0));
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "test-http-bind");
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            binder.start();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            binder.interrupt();
            binder.join(2000);

            assertFalse(binder.isAlive());
            assertInstanceOf(ConnectException.class, failure.get());
            assertInstanceOf(InterruptedException.class, failure.get().getCause());
            assertTrue(interrupted.get());
        } finally {
            resume.countDown();
            binder.interrupt();
            binder.join(2000);
        }
        assertTrue(accept.terminationFuture().await(2, TimeUnit.SECONDS));
        assertFalse(worker.isShuttingDown());
    }

    @Test
    void closeDuringBindDoesNotWaitForTheAcceptLoop() throws Exception {
        EventLoopGroup accept = newAcceptGroup();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        block(accept, blocked, resume);
        NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> null, worker, () -> {
            acquired.countDown();
            return accept;
        });
        listeners.add(signaling);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread binder = new Thread(() -> {
            try {
                signaling.bind(new InetSocketAddress("127.0.0.1", 0));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "test-http-bind-close-race");
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            binder.start();
            assertTrue(acquired.await(2, TimeUnit.SECONDS));

            worker.submit(signaling::close).get(2, TimeUnit.SECONDS);

            assertFalse(worker.isShuttingDown());
            resume.countDown();
            binder.join(2000);
            assertFalse(binder.isAlive());
            assertInstanceOf(ConnectException.class, failure.get());
        } finally {
            resume.countDown();
            binder.interrupt();
            binder.join(2000);
        }
        assertTrue(accept.terminationFuture().await(2, TimeUnit.SECONDS));
        assertNull(signaling.boundAddress());
    }

    @Test
    void completedExchangeCancelsATimeoutInstalledAfterwards() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.freezeTime();
        AtomicInteger expirations = new AtomicInteger();
        NetherNetHttpSignaling.PendingExchange exchange = new NetherNetHttpSignaling.PendingExchange(null, null);
        try {
            exchange.cancelTimeout();
            var timeout = channel.eventLoop().schedule(() -> {
                expirations.incrementAndGet();
            }, 30, TimeUnit.SECONDS);

            exchange.setTimeout(timeout);

            assertTrue(timeout.isCancelled());
            channel.advanceTimeBy(30, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertEquals(0, expirations.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static int probe(InetSocketAddress address) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(address, 2000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("GET /v1/join HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static void block(EventLoopGroup group, CountDownLatch blocked, CountDownLatch resume) {
        group.execute(() -> {
            blocked.countDown();
            try {
                resume.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private EventLoopGroup newAcceptGroup() {
        EventLoopGroup accept = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        acceptGroups.add(accept);
        return accept;
    }

    private NetherNetHttpSignaling listener(EventLoopGroup accept) {
        NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> null, worker, () -> accept);
        signaling.setNewConnectionHandler((connection, network, offer) -> {});
        listeners.add(signaling);
        return signaling;
    }
}
