package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.*;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeMaintainedCandidateTest {
    @TempDir Path directory;
    private static final List<ProviderTransport.TicketKey> KEYS = List.of(
            new ProviderTransport.TicketKey("K001", TestSignalingProvider.SECRET));

    private final class Host implements AutoCloseable {
        final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        final NativeHostIdentity identity;
        final NativeProviderTransport transport;
        final int port;
        Host(Map<EndpointSelection.Family, InetSocketAddress> servers) throws Exception { this(servers, List.of()); }
        Host(Map<EndpointSelection.Family, InetSocketAddress> servers, List<String> direct) throws Exception {
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
            transport = NativeProviderTransport.openMaintained(bootstrap,
                    EndpointSelection.select(new InetSocketAddress("::", port), List.of(), direct.stream().map(ip ->
                            new EndpointSelection.Candidate(new InetSocketAddress(ip, port), EndpointSelection.Provenance.SERVER_PROPERTIES)).toList()), servers,
                    identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(transport.channel().isServing());
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

    private static ProviderTransport.HostProfileSnapshot awaitMaintained(NativeProviderTransport transport, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        do {
            var capture = transport.captureHostProfile().toCompletableFuture().get();
            var candidates = capture.profile().getAsJsonArray("candidates");
            if (candidates.size() == 2 && candidates.asList().stream().allMatch(value -> {
                var candidate = value.getAsJsonObject();
                assertFalse(candidate.has("expiresAt"), "STUN uses the existing candidate wire shape");
                return candidate.get("port").getAsInt() == port && candidate.get("type").getAsString().equals("srflx");
            })) return capture;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Native maintained publication did not advance");
    }

    @Test @Timeout(60)
    void maintainedMuxRenewsAndRemapsWithoutControlTrafficOrReinstallingPeers() throws Exception {
        var responders = Executors.newFixedThreadPool(2);
        try (var stun4 = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)); var stun6 = new DatagramSocket(new InetSocketAddress("::1", 0));
             var host = new Host(Map.of(EndpointSelection.Family.IPV4, (InetSocketAddress) stun4.getLocalSocketAddress(), EndpointSelection.Family.IPV6, (InetSocketAddress) stun6.getLocalSocketAddress()), List.of("10.0.0.8", "fd00::8"))) {
            var v4 = responders.submit(() -> { maintainedResponses(stun4, host.port); return null; });
            var v6 = responders.submit(() -> { maintainedResponses(stun6, host.port); return null; });
            host.transport.installTicketKeys(KEYS).toCompletableFuture().get();
            // No publisher/heartbeat call drives the monitor or samples its native observations.
            Thread.sleep(1200);
            assertTrue(host.transport.candidatePublicationVersion() > 1);
            var first = awaitMaintained(host.transport, 43000);
            String incarnation = first.profile().getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
            assertEquals(2, first.profile().getAsJsonArray("candidates").size(), "Mapping publication needs no regional proof");
            try (var player4 = new ConnectedPlayer(host, "127.0.0.1", 55000); var player6 = new ConnectedPlayer(host, "::1", 55000)) {
                player4.exchange(); player6.exchange();
                var remapped = awaitMaintained(host.transport, 43001);
                assertThrows(IllegalStateException.class, first::requireCurrent);
                assertTrue(remapped.candidateRevision() > first.candidateRevision());
                assertEquals(incarnation, remapped.profile().getAsJsonObject("statelessAdmission").get("incarnation").getAsString());
                assertEquals("K001", remapped.profile().get("credentialKeyId").getAsString());
                assertTrue(host.transport.channel().isServing());
                assertEquals(2, host.transport.channel().creationAttempts());
                long failedAt = System.currentTimeMillis();
                var failure = new ArrayList<ProviderTransport.ConnectivityCheck>();
                for (var value : remapped.profile().getAsJsonArray("candidates")) {
                    var candidate = value.getAsJsonObject();
                    var target = new InetSocketAddress(candidate.get("address").getAsString(), candidate.get("port").getAsInt());
                    failure.add(new ProviderTransport.ConnectivityCheck(target.getAddress() instanceof Inet4Address ? 4 : 6,
                            "warm_stun", target, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, failedAt, failedAt + 10000));
                }
                host.transport.reportConnectivityChecks(remapped.candidateRevision(), failure).toCompletableFuture().get();
                var after = host.transport.captureHostProfile().toCompletableFuture().get();
                assertEquals(remapped.candidateRevision(), after.candidateRevision(), "Offer decisions must preserve recovery probe authority");
                assertTrue(after.publicationVersion() > remapped.publicationVersion());
                assertEquals(2, after.profile().getAsJsonArray("candidates").size(), "Failed public mappings are withheld; LAN/VPN fallback remains");
                assertEquals(2, after.probeCandidates().size(), "Public mappings remain testable");
                assertThrows(IllegalStateException.class, remapped::requireCurrent);
                player4.exchange(); player6.exchange();
            }
            v4.get(1, TimeUnit.SECONDS); v6.get(1, TimeUnit.SECONDS);
            System.out.println("maintained-native PASS families=2 sameGameplayPort=true observationsPerFamily=3 controlTraffic=false peersPreserved=2 syntheticPublicMappings=true");
        } finally { responders.shutdownNow(); assertTrue(responders.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test @Timeout(20)
    void maintainedFeedbackRechecksRevisionAndOriginalExpiryAfterEventLoopDelay() throws Exception {
        try (var stun = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             var host = new Host(Map.of(EndpointSelection.Family.IPV4, (InetSocketAddress) stun.getLocalSocketAddress()), List.of("8.8.8.8"))) {
            host.transport.installTicketKeys(KEYS).toCompletableFuture().get();
            var before = host.transport.captureHostProfile().toCompletableFuture().get();
            long revision = before.candidateRevision(), now = System.currentTimeMillis();
            var target = new InetSocketAddress("8.8.8.8", host.port);
            var negative = new ProviderTransport.ConnectivityCheck(4, "discovered", target, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, now - 2, now + 10000);
            var positive = new ProviderTransport.ConnectivityCheck(4, "discovered", target, ProviderTransport.ConnectivityOutcome.ESTABLISHED, now - 1, now + 100);
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
            var expired = new ProviderTransport.ConnectivityCheck(4, "discovered", target, ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED,
                    System.currentTimeMillis(), System.currentTimeMillis() + 100);
            var queued = host.transport.reportConnectivityChecks(revision, List.of(expired));
            assertThrows(ExecutionException.class, () -> host.transport.reportConnectivityChecks(revision, List.of(negative)).toCompletableFuture().get());
            try { Thread.sleep(150); } finally { release.countDown(); }
            queued.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(0, host.transport.channel().nativeStats()[2], "Queued expired check cannot start a monitor");
            before.requireCurrent();
            long failedAt = System.currentTimeMillis();
            host.transport.reportConnectivityChecks(revision, List.of(new ProviderTransport.ConnectivityCheck(4, "discovered", target,
                    ProviderTransport.ConnectivityOutcome.NOT_ESTABLISHED, failedAt, failedAt + 10000))).toCompletableFuture().get();
            stun.setSoTimeout(250);
            assertThrows(SocketTimeoutException.class, () -> stun.receive(new DatagramPacket(new byte[1024], 1024)), "Direct failure cannot start STUN");
            var after = host.transport.captureHostProfile().toCompletableFuture().get();
            assertEquals(revision, after.candidateRevision(), "Direct feedback cannot change host policy");
            assertEquals(Set.of(4, 6), after.assistedFamilies());
            assertEquals(0, after.profile().getAsJsonArray("candidates").size(), "Failed direct endpoint is withheld without assistance");
            assertEquals(1, after.probeCandidates().size());
            assertTrue(after.publicationVersion() > before.publicationVersion());
            long establishedAt = System.currentTimeMillis();
            host.transport.reportConnectivityChecks(revision, List.of(new ProviderTransport.ConnectivityCheck(4, "discovered", target,
                    ProviderTransport.ConnectivityOutcome.ESTABLISHED, establishedAt, establishedAt + 10000))).toCompletableFuture().get();
            host.transport.reportConnectivityChecks(revision, List.of(negative)).toCompletableFuture().get();
            var recovered = host.transport.captureHostProfile().toCompletableFuture().get();
            assertEquals(revision, recovered.candidateRevision());
            assertEquals(before.assistedFamilies(), recovered.assistedFamilies());
            assertEquals(1, recovered.profile().getAsJsonArray("candidates").size());
            assertThrows(IllegalStateException.class, after::requireCurrent);
            assertThrows(IllegalStateException.class, before::requireCurrent);
            assertTrue(host.transport.channel().isServing());
            assertEquals(0, host.transport.channel().creationAttempts());
        }
    }

}
