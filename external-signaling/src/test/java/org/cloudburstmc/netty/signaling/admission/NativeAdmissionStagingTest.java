package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
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
        Host() throws Exception { this(false); }
        Host(boolean version2) throws Exception { this(version2, null); }
        Host(Map<EndpointSelection.Family, InetSocketAddress> servers) throws Exception { this(true, servers); }
        Host(boolean version2, Map<EndpointSelection.Family, InetSocketAddress> servers) throws Exception { this(version2, servers, List.of()); }
        Host(boolean version2, Map<EndpointSelection.Family, InetSocketAddress> servers, List<String> direct) throws Exception {
            var helper = new NativeAdmissionIntegrationTest();
            helper.directory = directory;
            identity = helper.identity();
            try (var socket = new DatagramSocket(new InetSocketAddress("::", 0))) { port = socket.getLocalPort(); }
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel channel) {
                    channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        private boolean reliable = true;
                        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
                            if (event instanceof NetherNetPacket.Delivery delivery) reliable = delivery.reliable();
                            else super.userEventTriggered(ctx, event);
                        }
                        @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                            ctx.writeAndFlush(new NetherNetPacket(bytes.retain(), reliable));
                        }
                    });
                }
            });
            transport = (servers != null ? NativeProviderTransport.openMaintained(bootstrap,
                    EndpointSelection.select(new InetSocketAddress("::", port), List.of(), direct.stream().map(ip ->
                            new EndpointSelection.Candidate(new InetSocketAddress(ip, port), EndpointSelection.Provenance.SERVER_PROPERTIES)).toList()), servers,
                    identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    : version2 ? NativeProviderTransport.openControlledVersion2(bootstrap, new InetSocketAddress("::", port),
                    NativeCandidateSnapshot.hosts(List.of()), identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    : NativeProviderTransport.openControlled(bootstrap, new InetSocketAddress("::", port),
                    () -> List.of(new InetSocketAddress("127.0.0.1", port), new InetSocketAddress("::1", port)),
                    identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults()))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(servers == null, transport.supportsAdmissionStaging());
            assertEquals(servers != null, transport.channel().isServing());
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
            var nativeCreations = NativeDiagnostics.creationAttempts();
            long children = host.transport.channel().creationAttempts();
            socket.setSoTimeout(250);
            socket.send(new DatagramPacket(packet, packet.length, target));
            assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[2048], 2048)));
            NativeDiagnostics.assertCreations(nativeCreations, 0);
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

    private final class ConnectedPlayer implements AutoCloseable {
        final PeerConnection player;
        final DataChannel reliable, unreliable;
        final AtomicInteger reliableEchoes = new AtomicInteger(), unreliableEchoes = new AtomicInteger();
        ConnectedPlayer(Host host, String ip) throws Exception { this(host, ip, 30000); }
        ConnectedPlayer(Host host, String ip, long ticketMillis) throws Exception {
            player = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                    .withBindAddress(InetAddress.getByName(ip)), Runnable::run);
            reliable = player.createDataChannel("ReliableDataChannel");
            unreliable = player.createDataChannel("UnreliableDataChannel", DataChannelInitSettings.DEFAULT
                    .withReliability(new DataChannelReliability(true, true, 0, 0)));
            reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
                if (bytes.remaining() == 2 && bytes.get() == 0 && bytes.get() == 42) reliableEchoes.incrementAndGet();
            }));
            unreliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel, bytes) -> {
                if (bytes.remaining() == 2 && bytes.get() == 0 && bytes.get() == 42) unreliableEchoes.incrementAndGet();
            }));
            player.setLocalDescription("offer", ip.equals("::1") ? "snapshotPlayer6" : "snapshotPlayer4", "p".repeat(32));
            var answer = TestSignalingProvider.answer(player.localDescription(), host.identity.fingerprint(), host.port,
                    System.currentTimeMillis() + ticketMillis, host.audience(), false);
            player.setRemoteDescription(answer.sdp().replace("127.0.0.1", ip), SessionDescriptionType.ANSWER);
            NativeAdmissionIntegrationTest.await(() -> reliable.isOpen() && unreliable.isOpen());
        }
        void exchange() throws Exception {
            int expectedReliable = reliableEchoes.get() + 1, expectedUnreliable = unreliableEchoes.get() + 1;
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) 42).flip());
            unreliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) 42).flip());
            NativeAdmissionIntegrationTest.await(() -> reliableEchoes.get() == expectedReliable && unreliableEchoes.get() == expectedUnreliable);
            assertTrue(reliable.isOpen() && unreliable.isOpen());
        }
        @Override public void close() { assertTrue(player.closeAndAwait(Duration.ofSeconds(5))); }
    }

    /** Synthetic public mapping response tests publication only; source tuples remain real loopback gameplay mux tuples. */
    private static void maintainedResponses(DatagramSocket server, int gameplayPort) throws Exception {
        server.setSoTimeout(20000);
        for (int sequence = 1; sequence <= 3; sequence++) {
            byte[] bytes = new byte[1024]; var request = new DatagramPacket(bytes, bytes.length); server.receive(request);
            assertEquals(gameplayPort, request.getPort());
            var input = ByteBuffer.wrap(bytes); assertEquals(1, Short.toUnsignedInt(input.getShort()));
            input.getShort(); assertEquals(0x2112a442, input.getInt()); var transaction = new byte[12]; input.get(transaction);
            byte[] address = InetAddress.getByName(server.getLocalAddress() instanceof Inet4Address ? "8.8.8.8" : "2606:4700:4700::1001").getAddress();
            var response = ByteBuffer.allocate(address.length == 4 ? 32 : 44);
            response.putShort((short) 0x101).putShort((short) (response.capacity() - 20)).putInt(0x2112a442).put(transaction)
                    .putShort((short) 0x20).putShort((short) (address.length + 4)).put((byte) 0).put((byte) (address.length == 4 ? 1 : 2))
                    .putShort((short) ((43000 + (sequence == 3 ? 1 : 0)) ^ 0x2112));
            for (int i = 0; i < address.length; i++) response.put((byte) (address[i] ^ bytes[4 + i]));
            server.send(new DatagramPacket(response.array(), response.capacity(), request.getSocketAddress()));
        }
    }

    private static long minimumExpiry(ProviderTransport.HostProfileSnapshot snapshot) {
        var candidates = snapshot.profile().getAsJsonArray("candidates");
        if (candidates.size() != 2) return 0;
        long result = Long.MAX_VALUE;
        for (var value : candidates) {
            var candidate = value.getAsJsonObject();
            assertEquals("srflx", candidate.get("type").getAsString());
            result = Math.min(result, candidate.get("expiresAt").getAsLong());
        }
        return result;
    }

    private static ProviderTransport.HostProfileSnapshot awaitMaintained(NativeProviderTransport transport, long afterExpiry, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var capture = transport.captureHostProfile().toCompletableFuture().get();
            if (minimumExpiry(capture) > afterExpiry && capture.profile().getAsJsonArray("candidates").asList().stream()
                    .allMatch(value -> value.getAsJsonObject().get("port").getAsInt() == port)) return capture;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Native maintained publication did not advance");
    }

    @Test @Timeout(60)
    void maintainedMuxRenewsAndRemapsWithoutControlTrafficOrReinstallingPeers() throws Exception {
        var responders = Executors.newFixedThreadPool(2);
        try (var stun4 = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)); var stun6 = new DatagramSocket(new InetSocketAddress("::1", 0));
             var host = new Host(Map.of(EndpointSelection.Family.IPV4, (InetSocketAddress) stun4.getLocalSocketAddress(), EndpointSelection.Family.IPV6, (InetSocketAddress) stun6.getLocalSocketAddress()))) {
            var v4 = responders.submit(() -> { maintainedResponses(stun4, host.port); return null; });
            var v6 = responders.submit(() -> { maintainedResponses(stun6, host.port); return null; });
            host.transport.installTicketKeys(KEYS).toCompletableFuture().get();
            // No publisher/heartbeat call drives the monitor or samples its native observations.
            Thread.sleep(1200);
            assertTrue(host.transport.candidatePublicationVersion() > 1);
            var first = awaitMaintained(host.transport, 0, 43000);
            assertFalse(first.profile().has("version"));
            String incarnation = first.profile().getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            var endpoints = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
            for (var value : first.profile().getAsJsonArray("candidates")) {
                var candidate = value.getAsJsonObject();
                var address = InetAddress.getByName(candidate.get("address").getAsString());
                int family = address instanceof Inet4Address ? 4 : 6;
                endpoints.put(new DiagnosticHostPolicy.Endpoint(family, DiagnosticAdmissionCodec.address(family, address.getHostAddress()),
                        candidate.get("port").getAsInt(), first.candidateRevision()), candidate.get("expiresAt").getAsLong());
            }
            long policyExpiry = System.currentTimeMillis() + 299000;
            var context = new DiagnosticAdmissionCodec.Context("https://provider.example", "maintained-host", incarnation, 1);
            var keys = List.of(new DiagnosticAdmissionCodec.Key("K001", TestSignalingProvider.SECRET, 0, 9007199254740991L));
            assertThrows(ExecutionException.class, () -> host.transport.configureDiagnostics(
                    new DiagnosticHostPolicy(context, keys, endpoints.keySet(), policyExpiry)).toCompletableFuture().get());
            host.transport.configureDiagnostics(new DiagnosticHostPolicy(context, keys, endpoints.keySet(), policyExpiry, endpoints))
                    .toCompletableFuture().get();
            try (var player4 = new ConnectedPlayer(host, "127.0.0.1", 55000); var player6 = new ConnectedPlayer(host, "::1", 55000)) {
                var second = awaitMaintained(host.transport, minimumExpiry(first), 43000);
                first.requireCurrent(); assertEquals(first.candidateRevision(), second.candidateRevision());
                assertTrue(second.publicationVersion() > first.publicationVersion());
                player4.exchange(); player6.exchange();
                var remapped = awaitMaintained(host.transport, minimumExpiry(second), 43001);
                assertThrows(IllegalStateException.class, first::requireCurrent);
                assertThrows(IllegalStateException.class, second::requireCurrent);
                assertTrue(remapped.candidateRevision() > first.candidateRevision());
                assertEquals(incarnation, remapped.profile().getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
                assertEquals("K001", remapped.profile().get("credentialKeyId").getAsString());
                assertTrue(host.transport.channel().isServing());
                assertEquals(2, host.transport.channel().creationAttempts());
                player4.exchange(); player6.exchange();
            }
            v4.get(1, TimeUnit.SECONDS); v6.get(1, TimeUnit.SECONDS);
            System.out.println("maintained-native PASS families=2 sameGameplayPort=true observationsPerFamily=3 controlTraffic=false peersPreserved=2 syntheticPublicMappings=true");
        } finally { responders.shutdownNow(); assertTrue(responders.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test @Timeout(20)
    void maintainedFeedbackRechecksRevisionAndOriginalExpiryAfterEventLoopDelay() throws Exception {
        try (var stun = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             var host = new Host(false, Map.of(EndpointSelection.Family.IPV4, (InetSocketAddress) stun.getLocalSocketAddress()), List.of("8.8.8.8"))) {
            host.transport.installTicketKeys(KEYS).toCompletableFuture().get();
            var before = host.transport.captureHostProfile().toCompletableFuture().get();
            long revision = before.candidateRevision(), now = System.currentTimeMillis();
            var negative = new ProviderTransport.ConnectivityCheck(4, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now, now + 10000);
            var positive = new ProviderTransport.ConnectivityCheck(4, ProviderTransport.ConnectivityOutcome.ESTABLISHED, now, now + 100);
            host.transport.reportConnectivityChecks(revision + 1, List.of(negative)).toCompletableFuture().get();
            host.transport.reportConnectivityChecks(revision, List.of(negative, positive)).toCompletableFuture().get();
            assertEquals(0, host.transport.channel().nativeStats()[2], "No monitor for old revision or positive direct check");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            host.transport.channel().eventLoop().execute(() -> {
                entered.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var expired = new ProviderTransport.ConnectivityCheck(4, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED,
                    System.currentTimeMillis(), System.currentTimeMillis() + 100);
            var queued = host.transport.reportConnectivityChecks(revision, List.of(expired));
            assertThrows(ExecutionException.class, () -> host.transport.reportConnectivityChecks(revision, List.of(negative)).toCompletableFuture().get());
            try { Thread.sleep(150); } finally { release.countDown(); }
            queued.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, host.transport.channel().nativeStats()[2], "Queued expired check cannot start a monitor");
            before.requireCurrent();
            host.transport.reportConnectivityChecks(revision, List.of(negative)).toCompletableFuture().get();
            stun.setSoTimeout(2000); var packet = new DatagramPacket(new byte[1024], 1024); stun.receive(packet);
            assertEquals(host.port, packet.getPort(), "Fallback uses the exact gameplay mux");
            var after = host.transport.captureHostProfile().toCompletableFuture().get();
            assertTrue(after.candidateRevision() > revision);
            assertTrue(after.profile().getAsJsonArray("candidates").isEmpty(), "Unconfirmed mapping stays unpublished");
            assertThrows(IllegalStateException.class, before::requireCurrent);
            assertTrue(host.transport.channel().isServing());
            assertEquals(0, host.transport.channel().creationAttempts());
        }
    }

    @Test @Timeout(35)
    void versionTwoWithdrawalPreservesBothFamiliesEstablishedPeersKeysAndIdentity() throws Exception {
        try (var host = new Host(true)) {
            var lifetime = host.transport.captureNativeIdentity(); lifetime.requireCurrent();
            var initial = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(initial, KEYS).toCompletableFuture().get();
            var empty = host.transport.captureHostProfile().toCompletableFuture().get();
            assertEquals(2, empty.profile().get("version").getAsInt());
            assertTrue(empty.profile().getAsJsonArray("candidates").isEmpty(), "bind and key install precede endpoint discovery");
            var v4 = new InetSocketAddress("127.0.0.1", host.port);
            var v6 = new InetSocketAddress("::1", host.port);
            var dual = NativeCandidateSnapshot.hosts(List.of(v4, v6));
            assertTrue(host.transport.replaceCandidates(dual));
            assertThrows(IllegalStateException.class, empty::requireCurrent);
            assertEquals(ProviderTransport.ApplyResult.REJECTED,
                    host.transport.commitAdmissionUpdate(initial, () -> {}).toCompletableFuture().get(), "changed candidates retire pending native staging");
            var ready = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(ready, KEYS).toCompletableFuture().get();
            assertFalse(host.transport.replaceCandidates(NativeCandidateSnapshot.hosts(List.of(v6, v4, v4))));
            assertEquals(ProviderTransport.ApplyResult.APPLIED, host.transport.commitAdmissionUpdate(ready, () -> {}).toCompletableFuture().get());
            var first = host.transport.captureHostProfile().toCompletableFuture().get();
            var stableMetadata = first.profile(); stableMetadata.remove("candidates");
            long creations;
            try (var player4 = new ConnectedPlayer(host, "127.0.0.1"); var player6 = new ConnectedPlayer(host, "::1")) {
                creations = host.transport.channel().creationAttempts(); assertEquals(2, creations);
                for (var selection : List.of(dual, NativeCandidateSnapshot.hosts(List.of(v6)),
                        NativeCandidateSnapshot.hosts(List.of()), NativeCandidateSnapshot.hosts(List.of(v4)))) {
                    var before = host.transport.captureHostProfile().toCompletableFuture().get();
                    boolean changed = host.transport.replaceCandidates(selection);
                    if (changed) assertThrows(IllegalStateException.class, before::requireCurrent); else before.requireCurrent();
                    assertTrue(host.transport.channel().isServing(), "publication updates do not drop native identity or existing tickets");
                    var profile = host.transport.captureHostProfile().toCompletableFuture().get();
                    lifetime.requireCurrent(); assertEquals(lifetime.incarnation(), profile.profile().getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
                    assertEquals(selection.candidates().size(), profile.profile().getAsJsonArray("candidates").size());
                    var metadata = profile.profile(); metadata.remove("candidates"); assertEquals(stableMetadata, metadata);
                    var stage = host.transport.beginAdmissionUpdate(deadline());
                    host.transport.installTicketKeys(stage, KEYS).toCompletableFuture().get();
                    player4.exchange(); player6.exchange();
                    assertEquals(ProviderTransport.ApplyResult.APPLIED,
                            host.transport.commitAdmissionUpdate(stage, profile::requireCurrent).toCompletableFuture().get());
                    assertTrue(host.transport.channel().isServing());
                    player4.exchange(); player6.exchange();
                    assertEquals(creations, host.transport.channel().creationAttempts());
                    assertEquals(2, host.transport.channel().liveNativePeers());
                }
                host.transport.replaceCandidates(dual);
                assertThrows(IllegalStateException.class, first::requireCurrent, "A to B to A cannot revive old ownership");
                var current = host.transport.captureHostProfile().toCompletableFuture().get();
                assertThrows(IllegalArgumentException.class, () -> host.transport.replaceCandidates(new NativeCandidateSnapshot(List.of(
                        new NativeCandidateSnapshot.Candidate(v4, NativeCandidateSnapshot.Type.SRFLX)))));
                current.requireCurrent(); assertEquals(first.profile(), current.profile());
                player4.exchange(); player6.exchange();
            }
        }
    }

    @Test @Timeout(20)
    void nativeLifetimeSurvivesKeyChangesAndDesiredDrainButNeverCloseOrPermanentDrain() throws Exception {
        ProviderTransport.NativeIdentitySnapshot predecessor;
        try (var host = new Host(true)) {
            predecessor = host.transport.captureNativeIdentity();
            var token = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(token, KEYS).toCompletableFuture().get(); predecessor.requireCurrent();
            var replacement = host.transport.beginAdmissionUpdate(deadline());
            host.transport.installTicketKeys(replacement, List.of(new ProviderTransport.TicketKey("K002", TestSignalingProvider.SECRET))).toCompletableFuture().get();
            predecessor.requireCurrent(); host.transport.applyState("draining").toCompletableFuture().get(); predecessor.requireCurrent();
            host.transport.close().toCompletableFuture().get(); assertThrows(IllegalStateException.class, predecessor::requireCurrent);
        }
        try (var replacement = new Host(true)) {
            var current = replacement.transport.captureNativeIdentity(); current.requireCurrent();
            assertNotEquals(predecessor.incarnation(), current.incarnation()); assertThrows(IllegalStateException.class, predecessor::requireCurrent);
            replacement.transport.drain().toCompletableFuture().get(); assertThrows(IllegalStateException.class, current::requireCurrent);
        }
        try (var legacy = new Host()) {
            assertFalse(legacy.transport.supportsNativeIdentityCapture());
            assertThrows(UnsupportedOperationException.class, legacy.transport::captureNativeIdentity);
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
