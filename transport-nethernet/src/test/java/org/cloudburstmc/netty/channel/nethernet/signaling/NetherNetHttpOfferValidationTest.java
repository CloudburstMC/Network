package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.util.nethernet.ClientIdentity;
import org.cloudburstmc.netty.util.nethernet.TrustSourceUnavailableException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.cloudburstmc.netty.util.nethernet.ClientAssertionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NetherNetHttpOfferValidationTest {
    private final MultiThreadIoEventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private final ManualExecutor executor = new ManualExecutor();
    private final AtomicInteger executorCreations = new AtomicInteger();
    private final NetherNetHttpSignaling signaling = new NetherNetHttpSignaling(() -> null, worker, () -> worker, () -> {
        executorCreations.incrementAndGet();
        return executor;
    });
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<ClientIdentity> admitted = new ArrayList<>();

    private EmbeddedChannel offer(String sdp) {
        signaling.setNewConnectionHandler((connection, network, payload) -> {
            admitted.add(signaling.clientIdentityOf(connection));
            signaling.sendSignal(network, NetherNetConstants.buildSignalConnectResponse(connection, "answer"));
        });
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 1234); }
        };
        channels.add(channel);
        channel.freezeTime();
        signaling.initConnection(channel);
        String request = "POST /v1/join/peer HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                + sdp.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + sdp;
        channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.UTF_8));
        channel.runPendingTasks();
        return channel;
    }

    @AfterEach
    void close() {
        signaling.close();
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        worker.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void defaultPolicyRejectsUnsignedOffersBeforeAdmission() throws Exception {
        EmbeddedChannel channel = offer("v=0\r\n");
        assertTrue(admitted.isEmpty());
        executor.finishNext();
        channel.runPendingTasks();
        assertTrue(response(channel).startsWith("HTTP/1.1 400"));
        assertTrue(admitted.isEmpty());
    }

    @Test
    void optOutDoesNotCreateAValidationExecutor() {
        signaling.setOfferValidator(null);
        EmbeddedChannel channel = offer("v=0\r\n");
        assertEquals(0, executorCreations.get());
        assertEquals(1, admitted.size());
        assertNull(admitted.getFirst());
        assertTrue(response(channel).startsWith("HTTP/1.1 200"));
    }

    @Test
    void admissionWaitsForVerificationAndReceivesVerifiedClaims() throws Exception {
        signaling.setOfferValidator(validator());
        EmbeddedChannel channel = offer(validOffer());
        assertTrue(admitted.isEmpty());
        assertNull(channel.readOutbound());
        executor.finishNext();
        assertTrue(admitted.isEmpty(), "The validator must marshal admission back to the HTTP loop");
        channel.runPendingTasks();
        assertEquals(1, admitted.size());
        assertEquals("1234567890", admitted.getFirst().getClaims().get("xid"));
        assertTrue(response(channel).startsWith("HTTP/1.1 200"));
    }

    @Test
    void applicationCanRejectAuthenticatedPlayersBeforeAdmission() throws Exception {
        signaling.setOfferValidator(sdp -> {
            ClientIdentity identity = validator().validate(sdp);
            assertEquals("1234567890", identity.getClaims().get("xid"));
            throw new GeneralSecurityException("not on allowlist");
        });
        EmbeddedChannel channel = offer(validOffer());
        executor.finishNext();
        channel.runPendingTasks();
        assertTrue(admitted.isEmpty());
        assertTrue(response(channel).startsWith("HTTP/1.1 400"));
    }

    @Test
    void unreachableTrustSourceAnswers503InsteadOfRejecting() throws Exception {
        signaling.setOfferValidator(sdp -> {
            throw new TrustSourceUnavailableException("Trust source unavailable: connection refused");
        });
        EmbeddedChannel channel = offer(validOffer());
        executor.finishNext();
        channel.runPendingTasks();
        assertTrue(admitted.isEmpty());
        assertTrue(response(channel).startsWith("HTTP/1.1 503"));
    }

    @Test
    void saturatedValidationQueueRejectsWithoutBlockingTheIoLoop() throws Exception {
        signaling.setOfferValidator(validator());
        offer(validOffer());
        EmbeddedChannel excess = offer(validOffer());
        assertTrue(response(excess).startsWith("HTTP/1.1 503"));
        assertEquals(1, executor.tasks.size());
        assertTrue(admitted.isEmpty());
    }

    @Test
    void negotiationDeadlineCancelsQueuedValidation() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        signaling.setOfferValidator(sdp -> {
            validations.incrementAndGet();
            return validator().validate(sdp);
        });
        EmbeddedChannel channel = offer(validOffer());
        channel.advanceTimeBy(16, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(response(channel).startsWith("HTTP/1.1 502"));
        executor.finishNext();
        channel.runPendingTasks();
        assertEquals(0, validations.get());
        assertTrue(admitted.isEmpty());
    }

    @Test
    void disconnectPreventsLateValidationFromAdmittingAConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        signaling.setOfferValidator(sdp -> {
            entered.countDown();
            while (true) {
                try {
                    release.await();
                    return validator().validate(sdp);
                } catch (InterruptedException ignored) {
                    // Model a third-party validator that does not cooperate with cancellation.
                }
            }
        });
        EmbeddedChannel channel = offer(validOffer());
        Thread validation = executor.startNext();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            channel.close();
            release.countDown();
            validation.join(2000);
            assertFalse(validation.isAlive());
            channel.runPendingTasks();
            assertTrue(admitted.isEmpty());
            assertNull(channel.readOutbound());
        } finally {
            release.countDown();
            validation.join(2000);
        }
    }

    @Test
    void listenerCloseCancelsValidationAndShutsDownItsExecutor() throws Exception {
        signaling.setOfferValidator(validator());
        EmbeddedChannel channel = offer(validOffer());
        Runnable queued = executor.tasks.remove();
        signaling.close();
        queued.run();
        channel.runPendingTasks();
        assertTrue(executor.isShutdown());
        assertTrue(admitted.isEmpty());
        assertTrue(response(channel).startsWith("HTTP/1.1 503"));
    }

    private static String response(EmbeddedChannel channel) {
        channel.runPendingTasks();
        StringBuilder response = new StringBuilder();
        ByteBuf bytes;
        while ((bytes = channel.readOutbound()) != null) {
            try { response.append(bytes.toString(StandardCharsets.UTF_8)); }
            finally { bytes.release(); }
        }
        return response.toString();
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>(1);
        boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown || !tasks.offer(command)) throw new RejectedExecutionException();
        }
        Thread startNext() {
            Thread thread = new Thread(tasks.remove(), "test-offer-validator");
            thread.start();
            return thread;
        }
        void finishNext() throws InterruptedException {
            Thread thread = startNext();
            thread.join(2000);
            assertFalse(thread.isAlive());
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>();
            tasks.drainTo(remaining);
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
