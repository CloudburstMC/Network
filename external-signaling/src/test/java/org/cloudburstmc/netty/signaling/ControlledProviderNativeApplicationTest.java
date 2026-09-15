package org.cloudburstmc.netty.signaling;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.provider.ProviderHostIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JNI listener/application gating; heartbeat delivery is a typed unit fixture, not Warden/carrier evidence. */
@Tag("native")
class ControlledProviderNativeApplicationTest {
    @Test void eachFamilyReappliesToANewNativeIncarnation(@TempDir Path directory) throws Exception {
        for (String address : List.of("127.0.0.1", "::1")) {
            Path statePath = directory.resolve(address.equals("::1") ? "v6" : "v4");
            var group = new DefaultEventLoopGroup(1); var executor = Executors.newSingleThreadExecutor();
            try (var store = new ProviderStateStore(statePath)) {
                ControlledProviderStateTest.seed(store, ControlledProviderApplicationTest.ORIGIN);
                var identity = ProviderHostIdentity.ensure(statePath); String firstIncarnation = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    int port; try (var reservation = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(address), 0))) { port = reservation.getLocalPort(); }
                    var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                        @Override protected void initChannel(AdmittedNetherNetChildChannel channel) { channel.close(); }
                    });
                    var endpoint = new InetSocketAddress(InetAddress.getByName(address), port);
                    var nativeTransport = NativeProviderTransport.openControlled(bootstrap, endpoint, () -> List.of(endpoint),
                            identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledProviderApplicationTest.ORIGIN))) {
                        assertFalse(nativeTransport.channel().isServing());
                        var now = new AtomicLong(System.currentTimeMillis());
                        var exchange = new ControlledProviderApplicationTest.Exchange(executor, now, "serving");
                        var application = new ControlledProviderApplication(storage, nativeTransport, executor, now::get, () -> null,
                                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
                        application.synchronize(exchange).toCompletableFuture().get(5, TimeUnit.SECONDS);
                        assertNotNull(exchange.applied); assertTrue(nativeTransport.channel().isServing());
                        var profile = nativeTransport.hostProfile().toCompletableFuture().get(2, TimeUnit.SECONDS);
                        assertEquals(storage.application().getAsJsonObject("profile"), profile);
                        String incarnation = profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString();
                        if (firstIncarnation == null) firstIncarnation = incarnation; else assertNotEquals(firstIncarnation, incarnation);
                        assertTrue(exchange.bodies.stream().anyMatch(body -> body.has("hostProfile")));
                        assertFalse(exchange.bodies.get(0).has("applicationAck"));
                    } finally {
                        nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS);
                        assertFalse(nativeTransport.channel().isActive()); assertEquals(0, nativeTransport.channel().liveNativePeers());
                    }
                }
            } finally { executor.shutdownNow(); group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
        }
    }
    @Test void actualNativeAdmissionRemainsClosedAfterApplicationSaveFailure(@TempDir Path directory) throws Exception {
        var group = new DefaultEventLoopGroup(1); var executor = Executors.newSingleThreadExecutor();
        try (var store = new ProviderStateStore(directory)) {
            ControlledProviderStateTest.seed(store, ControlledProviderApplicationTest.ORIGIN); var identity = ProviderHostIdentity.ensure(directory);
            int port; try (var reservation = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) { port = reservation.getLocalPort(); }
            var endpoint = new InetSocketAddress("127.0.0.1", port);
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel channel) { channel.close(); }
            });
            var nativeTransport = NativeProviderTransport.openControlled(bootstrap, endpoint, () -> List.of(endpoint), identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            try (var storage = ControlledProviderState.open(store, ControlledProviderStateTest.config(ControlledProviderApplicationTest.ORIGIN), value -> {
                if (value.getAsJsonObject("controlApplication").has("basis")) throw new IOException("injected native application fsync failure"); store.write(value);
            })) {
                var now = new AtomicLong(System.currentTimeMillis()); var exchange = new ControlledProviderApplicationTest.Exchange(executor, now, "serving");
                var application = new ControlledProviderApplication(storage, nativeTransport, executor, now::get, () -> null,
                        () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
                var error = assertThrows(ExecutionException.class, () -> application.synchronize(exchange).toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof IOException); assertTrue(error.getCause().getMessage().contains("injected native application"));
                assertFalse(nativeTransport.channel().isServing()); assertNull(exchange.applied); assertEquals(0, nativeTransport.channel().creationAttempts());
            } finally { nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS); }
        } finally { executor.shutdownNow(); group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync(); }
    }
}
