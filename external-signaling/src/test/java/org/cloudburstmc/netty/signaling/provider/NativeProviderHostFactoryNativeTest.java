package org.cloudburstmc.netty.signaling.provider;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeProviderHostFactoryNativeTest {
    @Test void diagnosticOptionUsesOrdinaryListenerWithoutControlledModeOrStun(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try {
            for (String ip : List.of("127.0.0.1", "::1")) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip), 0))) { port = reservation.getLocalPort(); }
                var options = new HashMap<String, String>(); options.put("stateDirectory", directory.resolve(ip.equals("::1") ? "v6" : "v4").toString());
                options.put("diagnosticAdmission", "true");
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                options.put("advertisedEndpoints", "[{\"address\":\"" + (ip.equals("::1") ? "2606:4700:4700::1111" : "8.8.8.8") + "\",\"port\":43000}]");
                options.put("stunServers", "unused: diagnostics do not opt into STUN");
                var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) { channel.close(); }
                });
                var host = new NativeProviderHostFactory().open(bootstrap, new InetSocketAddress(InetAddress.getByName(ip), port), options).toCompletableFuture().get(10, TimeUnit.SECONDS);
                try {
                    var nativeHost = (org.cloudburstmc.netty.signaling.admission.NativeProviderTransport) host.transport();
                    assertTrue(nativeHost.supportsDiagnosticAdmission()); assertFalse(nativeHost.supportsNativeIdentityCapture());
                    assertEquals(0, nativeHost.candidatePublicationVersion()); assertTrue(nativeHost.channel().isServing());
                    nativeHost.installTicketKeys(List.of(new ProviderTransport.TicketKey("A001", "public-test-only-player-admission-secret"))).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var snapshot = nativeHost.captureHostProfile().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertEquals(java.util.Set.of(ip.equals("::1") ? 6 : 4), snapshot.assistedFamilies());
                    var profile = snapshot.profile(); assertFalse(profile.has("version")); assertTrue(snapshot.candidateRevision() > 0); long now = System.currentTimeMillis();
                    var context = new DiagnosticAdmissionCodec.Context("https://provider.example", "factory_host", profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString(), 1);
                    var policy = new DiagnosticHostPolicy(context, List.of(new DiagnosticAdmissionCodec.Key("D001", "public-test-only-diagnostic-epoch-secret", now, now + 60000)), java.util.Set.of(DiagnosticHostPolicy.Endpoint.assisted(ip.equals("::1") ? 6 : 4, snapshot.candidateRevision())), now + 60000);
                    nativeHost.configureDiagnostics(policy).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    assertTrue(nativeHost.channel().isServing()); assertEquals(0, nativeHost.channel().nativeStats()[2]);
                    nativeHost.disableDiagnostics().toCompletableFuture().get(5, TimeUnit.SECONDS);
                } finally { host.transport().close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
                try (var reclaimed = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip), port))) { assertEquals(port, reclaimed.getLocalPort()); }
            }
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly(); }
    }
    @Test void explicitAssistanceSuppressesBackgroundStunOnTheGameplaySocket(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try (var stun = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            for (boolean assisted : List.of(true, false)) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { port = reservation.getLocalPort(); }
                var options = new HashMap<String, String>();
                options.put("stateDirectory", directory.resolve(Boolean.toString(assisted)).toString());
                options.put("candidatePublication", NativeProviderHostFactory.MAINTAINED_V1);
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                options.put("assistedJoins", Boolean.toString(assisted));
                var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) { channel.close(); }
                });
                var host = new NativeProviderHostFactory().open(bootstrap, new InetSocketAddress("127.0.0.1", port), options)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                try {
                    var nativeHost = (org.cloudburstmc.netty.signaling.admission.NativeProviderTransport) host.transport();
                    nativeHost.configureStunServers(List.of(new ProviderTransport.StunServer("127.0.0.1", stun.getLocalPort())))
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var packet = new java.net.DatagramPacket(new byte[1024], 1024);
                    stun.setSoTimeout(assisted ? 250 : 2000);
                    if (assisted) assertThrows(java.net.SocketTimeoutException.class, () -> stun.receive(packet));
                    else { stun.receive(packet); assertEquals(port, packet.getPort()); }
                    assertEquals(assisted ? 0 : 1, nativeHost.channel().nativeStats()[2]);
                } finally { host.transport().close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            }
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly(); }
    }
    @Test void maintainedConfiguredHostsNeverCreateMonitorsOrPublishMissingFamilies(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try {
            for (String ip : List.of("127.0.0.1", "::1")) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip), 0))) { port = reservation.getLocalPort(); }
                String advertised = ip.equals("::1") ? "2606:4700:4700::1111" : "8.8.8.8";
                var options = new HashMap<String, String>();
                options.put("stateDirectory", directory.resolve(ip.equals("::1") ? "v6" : "v4").toString());
                options.put("candidatePublication", NativeProviderHostFactory.MAINTAINED_V1);
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                options.put("advertisedEndpoints", "[{\"address\":\"" + advertised + "\",\"port\":43000}]");
                options.put("stunServers", "unused configured-only input must not be parsed or resolved");
                var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) { channel.close(); }
                });
                var host = new NativeProviderHostFactory().open(bootstrap, new InetSocketAddress(InetAddress.getByName(ip), port), options).toCompletableFuture().get(10, TimeUnit.SECONDS);
                try {
                    var nativeHost = (org.cloudburstmc.netty.signaling.admission.NativeProviderTransport) host.transport();
                    assertTrue(nativeHost.candidatePublicationVersion() > 0); assertTrue(nativeHost.channel().isServing());
                    nativeHost.installTicketKeys(List.of(new ProviderTransport.TicketKey("A001", ProviderCrypto.base64(new byte[32])))).toCompletableFuture().get();
                    var before = nativeHost.captureHostProfile().toCompletableFuture().get();
                    for (boolean owned : List.of(false, true, false, true)) {
                        before.requireCurrent(); assertFalse(before.profile().has("version"));
                        assertEquals(0, nativeHost.channel().nativeStats()[2], "Configured forwarding suppresses monitor allocation");
                        var candidates = nativeHost.hostProfile().toCompletableFuture().get().getAsJsonArray("candidates"); assertEquals(1, candidates.size());
                        assertEquals(InetAddress.getByName(advertised).getHostAddress(), candidates.get(0).getAsJsonObject().get("address").getAsString());
                        assertEquals(43000, candidates.get(0).getAsJsonObject().get("port").getAsInt());
                    }
                } finally { host.transport().close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
            }
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly(); }
    }
    @Test void actualListenerPublishesOnlyConfiguredEndpointOnBothFamiliesAndControlModes(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(2);
        try {
            for (String bindAddress : List.of("127.0.0.1", "::1")) for (boolean controlled : List.of(false)) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), 0))) { port = reservation.getLocalPort(); }
                var options = new HashMap<String, String>();
                options.put("stateDirectory", directory.resolve((bindAddress.equals("::1") ? "v6" : "v4") + controlled).toString());
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                String configuredAddress = bindAddress.equals("::1") ? "2606:4700:4700::1111" : "8.8.8.8";
                int forwardedPort = bindAddress.equals("::1") ? 39133 : 29133;
                options.put("advertisedEndpoints", "[{\"address\":\"" + configuredAddress + "\",\"port\":" + forwardedPort + "}]");
                options.put("localDevelopment", "true");
                var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) { channel.close(); }
                });
                var host = new NativeProviderHostFactory().open(bootstrap, new InetSocketAddress(InetAddress.getByName(bindAddress), port), options).toCompletableFuture().get(10, TimeUnit.SECONDS);
                try {
                    assertEquals(port, ((InetSocketAddress) host.channel().localAddress()).getPort());
                    host.transport().installTicketKeys(List.of(new ProviderTransport.TicketKey("A001", ProviderCrypto.base64(new byte[32]), 0, Long.MAX_VALUE))).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var first = host.transport().hostProfile().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var candidates = first.getAsJsonArray("candidates"); assertEquals(1, candidates.size());
                    assertEquals(InetAddress.getByName(configuredAddress).getHostAddress(), candidates.get(0).getAsJsonObject().get("address").getAsString());
                    assertEquals(forwardedPort, candidates.get(0).getAsJsonObject().get("port").getAsInt());
                    assertEquals(first, host.transport().hostProfile().toCompletableFuture().get(5, TimeUnit.SECONDS));
                } finally { host.transport().close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
                assertFalse(host.channel().isActive());
                try (var reclaimed = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), port))) { assertEquals(port, reclaimed.getLocalPort()); }
            }
        } finally { group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly(); }
    }
}
