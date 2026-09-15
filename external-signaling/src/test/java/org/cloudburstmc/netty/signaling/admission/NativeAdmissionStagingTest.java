package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeAdmissionStagingTest {
    @TempDir Path directory;
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(30); }
    private static final List<ProviderTransport.TicketKey> KEYS = List.of(
            new ProviderTransport.TicketKey("K001", TestSignalingProvider.SECRET));

    private final class Host implements AutoCloseable {
        final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        final NativeHostIdentity identity;
        final NativeProviderTransport transport;
        final int port;
        Host() throws Exception {
            var helper = new NativeAdmissionIntegrationTest();
            helper.directory = directory;
            identity = helper.identity();
            try (var socket = new DatagramSocket(new InetSocketAddress("::", 0))) { port = socket.getLocalPort(); }
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel channel) {
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                            ctx.writeAndFlush(bytes.retain());
                        }
                    });
                }
            });
            transport = NativeProviderTransport.openControlled(bootstrap, new InetSocketAddress("::", port),
                    () -> List.of(new InetSocketAddress("127.0.0.1", port), new InetSocketAddress("::1", port)),
                    identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(transport.supportsAdmissionStaging());
            assertFalse(transport.channel().isServing(), "disabled before any keys are installed");
        }
        String audience() throws Exception {
            return NativeProviderTransport.audience(transport.hostProfile().toCompletableFuture().get()
                    .getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
        }
        @Override public void close() throws Exception {
            try { transport.close().toCompletableFuture().get(8, TimeUnit.SECONDS); }
            finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
            assertEquals(0, transport.channel().liveNativePeers());
        }
    }

    private final class FirstPacket implements AutoCloseable {
        final PeerConnection client;
        final DatagramSocket socket;
        final InetSocketAddress target;
        final byte[] packet;
        FirstPacket(Host host, String ip) throws Exception {
            InetAddress address = InetAddress.getByName(ip);
            target = new InetSocketAddress(address, host.port);
            socket = new DatagramSocket(new InetSocketAddress(address, 0));
            client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                    .withBindAddress(address), Runnable::run);
            client.createDataChannel("ReliableDataChannel");
            String ufrag = "stagedClient" + (ip.equals("::1") ? "6" : "4");
            client.setLocalDescription("offer", ufrag, "p".repeat(32));
            var answer = TestSignalingProvider.answer(client.localDescription(), host.identity.fingerprint(), host.port,
                    System.currentTimeMillis() + 30_000, host.audience(), false);
            packet = binding(answer.token() + ":" + ufrag, answer.password());
        }
        void blocked(Host host) throws Exception {
            long nativeCreations = PeerConnection.nativeCreationAttempts();
            long children = host.transport.channel().creationAttempts();
            socket.setSoTimeout(250);
            socket.send(new DatagramPacket(packet, packet.length, target));
            assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[2048], 2048)));
            assertEquals(nativeCreations, PeerConnection.nativeCreationAttempts(), "valid first STUN cannot allocate while staged");
            assertEquals(children, host.transport.channel().creationAttempts());
        }
        void accepted() throws Exception {
            socket.setSoTimeout(2000);
            socket.send(new DatagramPacket(packet, packet.length, target));
            byte[] bytes = new byte[2048];
            var reply = new DatagramPacket(bytes, bytes.length);
            socket.receive(reply);
            assertEquals(target, reply.getSocketAddress());
            assertEquals(0x0101, Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort()));
            assertArrayEquals(Arrays.copyOfRange(packet, 8, 20), Arrays.copyOfRange(bytes, 8, 20));
        }
        @Override public void close() {
            socket.close();
            assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
        }
    }

    @Test @Timeout(25)
    void bothFamiliesStayClosedThroughInstallationAndFailedSaveUntilExplicitCommit() throws Exception {
        try (var host = new Host()) {
            for (String ip : List.of("127.0.0.1", "::1")) {
                var update = host.transport.beginAdmissionUpdate(deadline());
                host.transport.installTicketKeys(update, KEYS).toCompletableFuture().get();
                assertEquals(ProviderTransport.ApplyResult.REJECTED, host.transport.applyState("serving").toCompletableFuture().get());
                try (var packet = new FirstPacket(host, ip)) {
                    packet.blocked(host);
                    assertThrows(ExecutionException.class, () -> host.transport.commitAdmissionUpdate(update,
                            () -> { throw new IllegalStateException("durable save failed"); }).toCompletableFuture().get());
                    assertEquals(ProviderTransport.ApplyResult.REJECTED,
                            host.transport.commitAdmissionUpdate(update, () -> {}).toCompletableFuture().get());
                    packet.blocked(host);
                    var fresh = host.transport.beginAdmissionUpdate(deadline());
                    host.transport.installTicketKeys(fresh, KEYS).toCompletableFuture().get();
                    assertEquals(ProviderTransport.ApplyResult.APPLIED,
                            host.transport.commitAdmissionUpdate(fresh, () -> {}).toCompletableFuture().get());
                    packet.accepted();
                    assertEquals(ProviderTransport.ApplyResult.REJECTED,
                            host.transport.commitAdmissionUpdate(fresh, () -> {}).toCompletableFuture().get(), "single use");
                }
            }
            assertEquals(2, host.transport.channel().creationAttempts());
        }
    }

    @Test @Timeout(20)
    void eachTransitionMustOwnAnInstalledSnapshotEvenWhenEarlierKeysRemain() throws Exception {
        try (var host = new Host()) {
            var installed = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(installed, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.APPLIED,
                    host.transport.commitAdmissionUpdate(installed, () -> {}).toCompletableFuture().get());
            var missing = host.transport.beginAdmissionUpdate(deadline());
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(missing, () -> {}).toCompletableFuture().get());
            assertFalse(host.transport.channel().isServing());
        }
    }

    @Test @Timeout(20)
    void replacementLegacyInstallAndNonservingCannotBeUndoneByOldTokens() throws Exception {
        try (var host = new Host()) {
            var old = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(old, KEYS).toCompletableFuture().get();
            var newer = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(newer, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(old, () -> fail("stale guard must not run")).toCompletableFuture().get());
            assertEquals(ProviderTransport.ApplyResult.APPLIED,
                    host.transport.commitAdmissionUpdate(newer, () -> {}).toCompletableFuture().get());
            var interrupted = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(interrupted, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED, host.transport.commitAdmissionUpdate(interrupted,
                    () -> host.transport.applyState("draining")).toCompletableFuture().get());
            assertFalse(host.transport.channel().isServing());
            var resumed = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(resumed, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.APPLIED,
                    host.transport.commitAdmissionUpdate(resumed, () -> {}).toCompletableFuture().get(), "desired drain is reversible");
            var legacyRace = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(legacyRace, () -> {}).toCompletableFuture().get());
            try (var packet = new FirstPacket(host, "127.0.0.1")) { packet.blocked(host); }
            var drained = host.transport.beginAdmissionUpdate(deadline());
            host.transport.drain().toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(drained, () -> {}).toCompletableFuture().get());
            assertThrows(IllegalStateException.class, () -> host.transport.beginAdmissionUpdate(deadline()));
        }
    }

    @Test @Timeout(20)
    void guardRaceCannotModifyFrozenSnapshotOrEnableClosedNativeInstance() throws Exception {
        try (var host = new Host()) {
            var update = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(update, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED, host.transport.commitAdmissionUpdate(update, () -> {
                assertTrue(host.transport.installTicketKeys(update, KEYS).toCompletableFuture().isCompletedExceptionally());
            }).toCompletableFuture().get());
            var closing = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(closing, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(closing, () -> host.transport.close()).toCompletableFuture().get());
            assertFalse(host.transport.channel().isServing());
        }
    }

    @Test @Timeout(20)
    void foreignInvalidAndExpiredKeyUpdatesCannotEnableAdmission() throws Exception {
        try (var host = new Host(); var other = new Host()) {
            var foreign = other.transport.beginAdmissionUpdate(deadline());
            var current = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(current, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(foreign, () -> fail("foreign guard")).toCompletableFuture().get());
            assertEquals(ProviderTransport.ApplyResult.APPLIED,
                    host.transport.commitAdmissionUpdate(current, () -> {}).toCompletableFuture().get());
            var expired = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(expired, List.of(new ProviderTransport.TicketKey("K001",
                    TestSignalingProvider.SECRET, 0, 1))).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(expired, () -> {}).toCompletableFuture().get());
            assertFalse(host.transport.channel().isServing());
            var invalid = host.transport.beginAdmissionUpdate(deadline());
            assertThrows(ExecutionException.class, () -> host.transport.installTicketKeys(invalid,
                    List.of(new ProviderTransport.TicketKey("K001", "short"))).toCompletableFuture().get());
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(invalid, () -> {}).toCompletableFuture().get());
        }
    }

    @Test @Timeout(20)
    void deadlineExpiringAfterGuardDuringMonitorContentionCannotEnable() throws Exception {
        try (var host = new Host()) {
            var token = host.transport.beginAdmissionUpdate(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            host.transport.installTicketKeys(token, KEYS).toCompletableFuture().get();
            var entered = new CountDownLatch(1);
            var guarded = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var result = executor.submit(() -> host.transport.commitAdmissionUpdate(token, () -> {
                    entered.countDown();
                    try { assertTrue(guarded.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }).toCompletableFuture().get());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                synchronized (host.transport) {
                    guarded.countDown();
                    Thread.sleep(1100); // Actual monitor contention exceeds the fixed monotonic deadline.
                }
                assertEquals(ProviderTransport.ApplyResult.REJECTED, result.get(2, TimeUnit.SECONDS));
                assertFalse(host.transport.channel().isServing());
                try (var packet = new FirstPacket(host, "127.0.0.1")) { packet.blocked(host); }
            } finally { guarded.countDown(); executor.shutdownNow(); }
        }
    }

    @Test @Timeout(20)
    void concurrentGuardWaitReleasesMonitorAndCannotReopenReplacement() throws Exception {
        try (var host = new Host()) {
            var old = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(old, KEYS).toCompletableFuture().get();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var completion = executor.submit(() -> host.transport.commitAdmissionUpdate(old, () -> {
                    entered.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                }).toCompletableFuture().get());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var replacement = host.transport.beginAdmissionUpdate(deadline());
                host.transport.installTicketKeys(replacement, KEYS).toCompletableFuture().get();
                release.countDown();
                assertEquals(ProviderTransport.ApplyResult.REJECTED, completion.get(3, TimeUnit.SECONDS));
                assertFalse(host.transport.channel().isServing());
                assertEquals(ProviderTransport.ApplyResult.APPLIED,
                        host.transport.commitAdmissionUpdate(replacement, () -> {}).toCompletableFuture().get());
            } finally { release.countDown(); executor.shutdownNow(); }
        }
    }

    @Test @Timeout(30)
    void establishedPeersExchangeDataWhileNewAdmissionIsStagedForBothFamilies() throws Exception {
        try (var host = new Host()) {
            var update = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(update, KEYS).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.APPLIED,
                    host.transport.commitAdmissionUpdate(update, () -> {}).toCompletableFuture().get());
            for (String ip : List.of("127.0.0.1", "::1")) {
                var resume = host.transport.beginAdmissionUpdate(deadline());
                host.transport.installTicketKeys(resume, KEYS).toCompletableFuture().get();
                host.transport.commitAdmissionUpdate(resume, () -> {}).toCompletableFuture().get();
                try (var player = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                        .withBindAddress(InetAddress.getByName(ip)), Runnable::run)) {
                    var reliable = player.createDataChannel("ReliableDataChannel");
                    var unreliable = player.createDataChannel("UnreliableDataChannel", DataChannelInitSettings.DEFAULT
                            .withReliability(new DataChannelReliability(true, true, 0, 0)));
                    var echoes = new AtomicInteger();
                    reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
                        if (bytes.remaining() == 2 && bytes.get() == 0 && bytes.get() == 42) echoes.incrementAndGet();
                    }));
                    player.setLocalDescription("offer", ip.equals("::1") ? "existingPlayer6" : "existingPlayer4", "p".repeat(32));
                    var answer = TestSignalingProvider.answer(player.localDescription(), host.identity.fingerprint(), host.port,
                            System.currentTimeMillis() + 30_000, host.audience(), false);
                    player.setRemoteDescription(answer.sdp().replace("127.0.0.1", ip), SessionDescriptionType.ANSWER);
                    NativeAdmissionIntegrationTest.await(() -> reliable.isOpen() && unreliable.isOpen());
                    host.transport.beginAdmissionUpdate(deadline());
                    assertFalse(host.transport.channel().isServing());
                    reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) 42).flip());
                    NativeAdmissionIntegrationTest.await(() -> echoes.get() == 1);
                    assertTrue(reliable.isOpen() && unreliable.isOpen());
                    assertTrue(player.closeAndAwait(Duration.ofSeconds(5)));
                }
            }
        }
    }

    private static byte[] binding(String username, String password) throws Exception {
        byte[] minimal = AdmissionFixture.binding(username, password);
        int integrityOffset = minimal.length - 24;
        ByteBuffer packet = ByteBuffer.allocate(minimal.length + 24);
        packet.put(minimal, 0, integrityOffset);
        packet.putShort((short) 0x24).putShort((short) 4).putInt(1853693695);
        packet.putShort((short) 0x802a).putShort((short) 8).putLong(42);
        packet.putShort((short) 0x25).putShort((short) 0);
        int signedLength = packet.position();
        packet.putShort((short) 8).putShort((short) 20);
        packet.putShort(2, (short) (packet.capacity() - 20));
        byte[] transaction = new byte[12];
        new SecureRandom().nextBytes(transaction);
        System.arraycopy(transaction, 0, packet.array(), 8, transaction.length);
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        packet.put(mac.doFinal(Arrays.copyOf(packet.array(), signedLength)));
        return packet.array();
    }
}
