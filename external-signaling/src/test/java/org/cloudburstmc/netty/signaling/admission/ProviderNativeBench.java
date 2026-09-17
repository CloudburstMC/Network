package org.cloudburstmc.netty.signaling.admission;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.ProviderClient;
import org.cloudburstmc.netty.signaling.ProviderStateStore;
import org.cloudburstmc.netty.signaling.ServerStatus;
import org.cloudburstmc.netty.signaling.*;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticHostPolicy;
import org.cloudburstmc.netty.signaling.diagnostic.NativeDiagnosticHostGate;

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

    /** Local fixture declaration only; all signaling, keys, peer admission and bytes use the real implementations. */
    private static final class LoopbackAssistedDiagnostics implements ProviderTransport {
        private final NativeProviderTransport transport;
        private final int family;
        private final InetSocketAddress bind;
        private NativeDiagnosticHostGate diagnostics;

        LoopbackAssistedDiagnostics(NativeProviderTransport transport, InetSocketAddress bind) {
            if (!bind.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Loopback fixture only");
            this.transport = transport;
            this.bind = bind;
            family = bind.getAddress() instanceof Inet6Address ? 6 : 4;
        }

        @Override public CompletionStage<HostProfileSnapshot> captureHostProfile() {
            return transport.captureHostProfile().thenApply(original -> {
                JsonObject profile = original.profile();
                profile.add("candidates", new JsonArray());
                return new HostProfileSnapshot(profile, original.candidateRevision(), original.publicationVersion(),
                        new JsonArray(), Set.of(family), original::requireCurrent);
            });
        }
        @Override public CompletionStage<JsonObject> hostProfile() { return captureHostProfile().thenApply(HostProfileSnapshot::profile); }
        @Override public long candidatePublicationVersion() { return transport.candidatePublicationVersion(); }
        @Override public boolean supportsAssistedJoins() { return true; }
        @Override public CompletionStage<String> assistedJoin(AssistedJoin join, Runnable requireCurrent) {
            return transport.captureHostProfile().thenCompose(snapshot -> transport.channel().assistDiagnostic(join, () -> {
                requireCurrent.run(); snapshot.requireCurrent();
                if (!snapshot.profile().get("credentialKeyId").getAsString().equals(join.keyId()))
                    throw new IllegalStateException("Fixture admission key changed");
            }, Map.of(), Map.of(family, bind)));
        }
        @Override public boolean supportsDiagnosticAdmission() { return true; }
        @Override public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy, long deadlineNanos) {
            return captureHostProfile().thenCompose(snapshot -> {
                var result = new CompletableFuture<Void>();
                transport.channel().eventLoop().execute(() -> {
                    try {
                        snapshot.requireCurrent();
                        var profile = snapshot.profile();
                        long remaining = deadlineNanos - System.nanoTime();
                        if (remaining <= 0 || remaining > TimeUnit.MINUTES.toNanos(5)
                                || !policy.context().incarnation().equals(profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString())
                                || !policy.endpoints().equals(Set.of(DiagnosticHostPolicy.Endpoint.assisted(family, snapshot.candidateRevision()))))
                            throw new IllegalStateException("Fixture diagnostic authority changed");
                        long expiry = Math.min(policy.expiresAt(), System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(remaining));
                        var endpoint = policy.endpoints().iterator().next();
                        var bounded = new DiagnosticHostPolicy(policy.context(), policy.keys(), policy.endpoints(), expiry,
                                Map.of(endpoint, Math.min(expiry, policy.endpointExpiries().get(endpoint))));
                        diagnostics = transport.channel().installDiagnostics(bounded, diagnostics);
                        result.complete(null);
                    } catch (Throwable failure) { result.completeExceptionally(failure); }
                });
                return result;
            });
        }
        @Override public CompletionStage<Void> disableDiagnostics() {
            if (diagnostics != null) diagnostics.retainEndpoints(Set.of());
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) { return transport.installTicketKeys(keys); }
        @Override public CompletionStage<ApplyResult> applyState(String state) { return transport.applyState(state); }
        @Override public List<JsonObject> pollEvents() { return transport.pollEvents(); }
        @Override public CompletionStage<Void> drain() { return transport.drain(); }
        @Override public CompletionStage<Void> close() { return transport.close(); }
    }

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
        boolean assisted = Boolean.getBoolean("providerAssistedJoins");
        boolean diagnostic = Boolean.getBoolean("providerDiagnosticAdmission");
        boolean diagnosticAssisted = assisted && diagnostic;
        var identityVerified = new java.util.concurrent.atomic.AtomicBoolean();
        var expectedCpk = assisted && !diagnosticAssisted ? java.security.KeyFactory.getInstance("EC").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(System.getProperty("providerExpectedCpk")))) : null;
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
                                if (assisted && !diagnosticAssisted && !identityVerified.get()) {
                                    String mismatch = org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding.mismatch(child, expectedCpk);
                                    if (mismatch != null) throw new IllegalStateException(mismatch);
                                    identityVerified.set(true);
                                }
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
            nativeHost = (assisted && !diagnosticAssisted ? NativeProviderTransport.openMaintained(bootstrap,
                    org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.select(bind,List.of(),List.of()), Map.of(),
                    state.resolve("host-cert.pem"),state.resolve("host-key.pem"),new AdmissionGate.Limits(4,8,2,15_000))
                    : NativeProviderTransport.open(bootstrap, bind, advertised,
                    state.resolve("host-cert.pem"), state.resolve("host-key.pem"),
                    new AdmissionGate.Limits(4, 8, 2, 10_000))).toCompletableFuture().get(10, TimeUnit.SECONDS);
            ProviderTransport transport = diagnosticAssisted ? new LoopbackAssistedDiagnostics(nativeHost, bind) : nativeHost;
            provider = new ProviderClient(
                    new ProviderClient.Configuration(origin, "nxs-admission-v1", "Provider native integration",
                            ProviderClient.NEW_SERVICE, ProviderClient.ANONYMOUS_PROOF_OF_WORK, null,
                            null, null, Map.of(), assisted ? ProviderClient.ControlTransport.AUTO : ProviderClient.ControlTransport.HTTP,
                            diagnostic,
                            advertisedAddress == null ? "discovered" : "defined", assisted),
                    new ProviderStateStore(state), transport,
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
            emit("profile", transport.hostProfile().toCompletableFuture().get());
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
                        endpoint.creationAttempts(), "deliveredChannels", delivered.get(), "controlCarrier", provider.lastControlCarrier(),
                        "identityVerified", identityVerified.get()));
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
