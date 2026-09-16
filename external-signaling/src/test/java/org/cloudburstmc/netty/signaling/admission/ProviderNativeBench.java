package org.cloudburstmc.netty.signaling.admission;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.ProviderClient;
import org.cloudburstmc.netty.signaling.ProviderStateStore;
import org.cloudburstmc.netty.signaling.ServerStatus;
import org.cloudburstmc.netty.signaling.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real provider/native composition; loopback fixture identity, never gameplay evidence.
 */
public final class ProviderNativeBench {
    private static final Gson JSON = new Gson();

    private static synchronized void emit(String kind, Object value) {
        JsonObject event = new JsonObject();
        event.addProperty("kind", kind);
        event.add("value", JSON.toJsonTree(value));
        System.out.println(event);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        URI origin = URI.create(args[0]);
        if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(origin.getHost())) {
            throw new IllegalArgumentException("Loopback bench only");
        }
        Path state = Path.of(args[1]), stop = Path.of(args[3]);
        int port = Integer.parseInt(args[2]);
        var group = new DefaultEventLoopGroup(2);
        AtomicInteger delivered = new AtomicInteger();
        ServerBootstrap bootstrap = new ServerBootstrap().group(group)
                .childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                    @Override
                    protected void initChannel(AdmittedNetherNetChildChannel child) {
                        child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            boolean reliable = true;

                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                if (event instanceof NetherNetPacket.Delivery delivery) {
                                    reliable = delivery.reliable();
                                }
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf data) {
                                delivered.getAndUpdate(mask -> mask | (reliable ? 1 : 2));
                                ctx.writeAndFlush(new NetherNetPacket(data.retainedDuplicate(), reliable));
                            }
                        });
                    }
                });
        NativeProviderTransport nativeHost = null;
        ProviderClient provider = null;
        try {
            String advertisedAddress = System.getProperty("providerAdvertisedAddress");
            var bindAddress = org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(
                    System.getProperty("providerBindAddress", "127.0.0.1"));
            if (!bindAddress.isLoopbackAddress()) {
                throw new IllegalArgumentException("Loopback bench bind only");
            }
            var bind = new InetSocketAddress(bindAddress, port);
            var advertised = advertisedAddress == null ? bind : new InetSocketAddress(
                    org.cloudburstmc.netty.util.nethernet.EndpointAddress.parse(advertisedAddress), port);
            nativeHost = NativeProviderTransport.open(bootstrap, bind, advertised,
                    state.resolve("host-cert.pem"), state.resolve("host-key.pem"),
                    new AdmissionGate.Limits(4, 8, 2, 10_000)).toCompletableFuture().get(10, TimeUnit.SECONDS);
            provider = new ProviderClient(
                    new ProviderClient.Configuration(origin, "nxs-admission-v1", "Provider native integration",
                            ProviderClient.NEW_SERVICE, ProviderClient.ANONYMOUS_PROOF_OF_WORK, null,
                            null, null, Map.of(), ProviderClient.ControlTransport.HTTP,
                            Boolean.getBoolean("providerDiagnosticAdmission"),
                            advertisedAddress == null ? "discovered" : "defined"),
                    new ProviderStateStore(state), nativeHost,
                    () -> new ServerStatus("Automatic native server", 1234, "fixture-only", "Integration", 0, 4, 0),
                    () -> new ProviderClient.Health(true, true, 4, 0, "nethernet", "provider-native-bench"),
                    System.err::println);
            JsonObject registration = provider.start().get(45, TimeUnit.SECONDS);
            if (args.length > 4) {
                ExtensionFixtureFile.write(Path.of(args[4]), provider.extensions().get(10, TimeUnit.SECONDS));
            }
            // Emit assigned IDs only; optional metadata and credentials are excluded.
            var assignedIds = new LinkedHashMap<String, String>();
            assignedIds.put("instanceId", registration.get("instanceId").getAsString());
            if (registration.has("serviceId")) {
                assignedIds.put("serviceId", registration.get("serviceId").getAsString());
            }
            emit("registered", assignedIds);
            emit("profile", nativeHost.hostProfile().toCompletableFuture().get());
            JsonObject readiness = provider.readiness().get(10, TimeUnit.SECONDS);
            readiness.remove("extensions");
            emit("readiness", readiness);
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
            boolean updated = false;
            while (!Files.exists(stop)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Provider native bench deadline");
                }
                if (!updated && Files.exists(state.resolve("update-status"))) {
                    provider.setServerStatus(
                            new ServerStatus("Updated native server", 1235, "fixture-updated", "Updated level", 1, 8,
                                    1));
                    updated = true;
                }
                if (Files.deleteIfExists(state.resolve("check-connectivity"))) {
                    JsonObject refreshed = provider.readiness().get(10, TimeUnit.SECONDS);
                    JsonObject feedback = new JsonObject();
                    if (refreshed.has("extensions")) {
                        JsonObject extensions = refreshed.getAsJsonObject("extensions");
                        if (extensions.has("org.nethernet.connectivity")) {
                            feedback = extensions.getAsJsonObject("org.nethernet.connectivity")
                                    .getAsJsonObject("data");
                        }
                    }
                    emit("connectivity", feedback);
                }
                var endpoint = nativeHost.channel();
                emit("stats", Map.of("admission", endpoint.admissionStats(), "native", endpoint.nativeStats(),
                        "nativeCreationAttempts", NativeDiagnostics.creationAttempts().orElse(-1), "hostCreations",
                        endpoint.creationAttempts(), "deliveredChannels", delivered.get()));
                Thread.sleep(100);
            }
            provider.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
            provider = null;
            try (var reuse = new DatagramSocket(bind)) {
                emit("closed", Map.of("udpReleased", reuse.getLocalPort() == port));
            }
        } finally {
            if (provider != null) {
                provider.stop().toCompletableFuture().get(20, TimeUnit.SECONDS);
            }
            if (nativeHost != null) {
                nativeHost.close().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
