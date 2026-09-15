package org.cloudburstmc.netty.signaling.provider;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.cloudburstmc.netty.signaling.ProviderTransport;
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
    @Test void maintainedConfiguredHostsNeverCreateMonitorsOrPublishMissingFamilies(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(1);
        try {
            for (String ip : List.of("127.0.0.1", "::1")) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(ip), 0))) { port = reservation.getLocalPort(); }
                String advertised = ip.equals("::1") ? "2606:4700:4700::1111" : "8.8.8.8";
                var options = new HashMap<String, String>();
                options.put("stateDirectory", directory.resolve(ip.equals("::1") ? "v6" : "v4").toString());
                options.put("controlMode", "nethernet-control-v1"); options.put("candidatePublication", NativeProviderHostFactory.MAINTAINED_V1);
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                options.put("advertisedEndpoints", "[{\"address\":\"" + advertised + "\",\"port\":43000}]");
                options.put("stunServers", "unused configured-only input must not be parsed or resolved");
                var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) { channel.close(); }
                });
                var host = new NativeProviderHostFactory().open(bootstrap, new InetSocketAddress(InetAddress.getByName(ip), port), options).toCompletableFuture().get(10, TimeUnit.SECONDS);
                try {
                    var nativeHost = (org.cloudburstmc.netty.signaling.admission.NativeProviderTransport) host.transport();
                    assertTrue(nativeHost.supportsMaintainedCandidateLeases()); assertFalse(nativeHost.channel().isServing());
                    nativeHost.installTicketKeys(List.of(new ProviderTransport.TicketKey("A001", ProviderCrypto.base64(new byte[32])))).toCompletableFuture().get();
                    var before = nativeHost.captureHostProfile().toCompletableFuture().get();
                    for (boolean owned : List.of(false, true, false, true)) {
                        assertTrue(nativeHost.maintainCandidateLeases(owned).observations().isEmpty()); before.requireCurrent();
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
            for (String bindAddress : List.of("127.0.0.1", "::1")) for (boolean controlled : List.of(false, true)) {
                int port;
                try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), 0))) { port = reservation.getLocalPort(); }
                var options = new HashMap<String, String>();
                options.put("stateDirectory", directory.resolve((bindAddress.equals("::1") ? "v6" : "v4") + controlled).toString());
                options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
                String configuredAddress = bindAddress.equals("::1") ? "2606:4700:4700::1111" : "8.8.8.8";
                int forwardedPort = bindAddress.equals("::1") ? 39133 : 29133;
                options.put("advertisedEndpoints", "[{\"address\":\"" + configuredAddress + "\",\"port\":" + forwardedPort + "}]");
                options.put("localDevelopment", "true");
                if (controlled) options.put("controlMode", "nethernet-control-v1");
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
