package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointConnectivityController;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;

@Tag("native")
class NativeConnectivityIntegrationTest {
    @TempDir Path directory;

    /** Standard Binding fixture returns the actual received source tuple; it does not fabricate a public mapping. */
    private static void respondTwice(DatagramSocket server, int gameplayPort) throws Exception {
        server.setSoTimeout(20_000);
        for (int n = 0; n < 2; n++) {
            byte[] request = new byte[1024];
            var packet = new DatagramPacket(request, request.length);
            server.receive(packet);
            assertEquals(gameplayPort, packet.getPort(), "Monitor must use the gameplay mux source port");
            assertEquals(server.getLocalAddress(), packet.getAddress());
            ByteBuffer input = ByteBuffer.wrap(request);
            assertEquals(1, Short.toUnsignedInt(input.getShort()));
            input.getShort(); assertEquals(0x2112a442, input.getInt());
            byte[] transaction = new byte[12]; input.get(transaction);
            byte[] address = packet.getAddress().getAddress();
            ByteBuffer response = ByteBuffer.allocate(address.length == 4 ? 32 : 44);
            response.putShort((short) 0x101).putShort((short) (response.capacity() - 20));
            response.putInt(0x2112a442).put(transaction).putShort((short) 0x20).putShort((short) (address.length + 4));
            response.put((byte) 0).put((byte) (address.length == 4 ? 1 : 2)).putShort((short) (packet.getPort() ^ 0x2112));
            for (int i = 0; i < address.length; i++) response.put((byte) (address[i] ^ request[4 + i]));
            server.send(new DatagramPacket(response.array(), response.capacity(), packet.getSocketAddress()));
        }
    }

    @Test @Timeout(35)
    void sameDualStackGameplayMuxRefreshesWithoutPlayersAndClosesWithChannel() throws Exception {
        var identityTest = new NativeAdmissionIntegrationTest();
        identityTest.directory = directory;
        var identity = identityTest.identity();
        InetAddress v4 = InetAddress.getByName("127.0.0.1"), v6 = InetAddress.getByName("::1");
        int port;
        try (var unused = new DatagramSocket(new InetSocketAddress(v6, 0))) { port = unused.getLocalPort(); }
        var group = new DefaultEventLoopGroup(1);
        var responders = Executors.newFixedThreadPool(2);
        NativeProviderTransport host = null;
        try (var stun4 = new DatagramSocket(new InetSocketAddress(v4, 0));
             var stun6 = new DatagramSocket(new InetSocketAddress(v6, 0))) {
            var bind = new InetSocketAddress("::", port);
            host = NativeProviderTransport.open(new ServerBootstrap().group(group).childHandler(new ChannelInboundHandlerAdapter()),
                    bind, new InetSocketAddress(v4, port), identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            host.installTicketKeys(List.of(new ProviderTransport.TicketKey("K001", TestSignalingProvider.SECRET))).toCompletableFuture().get();
            var profile = host.hostProfile().toCompletableFuture().get();
            var channel = host.channel();
            assertEquals(0, channel.nativeStats()[2]);
            var servers = Map.of(Family.IPV4, (InetSocketAddress) stun4.getLocalSocketAddress(),
                    Family.IPV6, (InetSocketAddress) stun6.getLocalSocketAddress());
            assertThrows(ExecutionException.class, () -> channel.enableConnectivity(EndpointSelection.select(
                    new InetSocketAddress("::", port == 65535 ? port - 1 : port + 1), List.of(), List.of()), servers,
                    Duration.ofSeconds(30)).toCompletableFuture().get());
            var selection = EndpointSelection.select(bind, List.of(), List.of());
            var controller = channel.enableConnectivity(selection, servers, Duration.ofSeconds(30)).toCompletableFuture().get();
            assertThrows(ExecutionException.class, () -> channel.enableConnectivity(selection, servers,
                    Duration.ofSeconds(30)).toCompletableFuture().get());
            Future<?> first = responders.submit(() -> { respondTwice(stun4, port); return null; });
            Future<?> second = responders.submit(() -> { respondTwice(stun6, port); return null; });
            controller.snapshot();
            first.get(20, TimeUnit.SECONDS); second.get(1, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            EndpointConnectivityController.Snapshot snapshot;
            do {
                snapshot = controller.snapshot();
                if (snapshot.families().values().stream().allMatch(f -> f.observation().isPresent()
                        && f.observation().get().successfulResponses() >= 2)) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            for (var family : Family.values()) {
                var state = snapshot.families().get(family);
                assertEquals(EndpointConnectivityController.State.STUN_INELIGIBLE, state.state());
                assertTrue(state.freshStunEndpoint().isEmpty(), "Loopback mapping must never enter a public candidate profile");
                assertEquals(2, state.observation().orElseThrow().successfulResponses());
                assertEquals(port, state.observation().orElseThrow().mapped().getPort());
            }
            assertEquals(2, channel.nativeStats()[2], "Two monitor agents share the existing mux with no player agents");
            assertEquals(0, channel.creationAttempts());
            assertEquals(0, channel.nativeStats()[3]);
            assertEquals(0, channel.nativeStats()[5]);
            assertEquals(profile, host.hostProfile().toCompletableFuture().get(), "Monitoring cannot mutate admission incarnation/profile");
            host.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(controller.snapshot().families().values().stream().allMatch(f -> f.state() == EndpointConnectivityController.State.CLOSED));
            try (var reused = new DatagramSocket(bind)) { assertEquals(port, reused.getLocalPort()); }
            System.out.println("native-connectivity PASS dualStack=true sameGameplayPort=true responsesPerFamily=2 playerCreations=0 channelCloseReleasesMonitors=true");
        } finally {
            if (host != null) host.close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            responders.shutdownNow();
            assertTrue(responders.awaitTermination(2, TimeUnit.SECONDS));
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
