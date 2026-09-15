package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.control.*;
import org.cloudburstmc.netty.signaling.provider.ProviderHostIdentity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec.*;

@Tag("native")
class ControlledDiagnosticApplicationNativeTest {
    @Test @Timeout(65) void actualApplicationPersistsAcknowledgesAndPreservesAdmittedPeerAcrossPlayerRebind(@TempDir Path directory) throws Exception {
        for (String address : List.of("127.0.0.1", "::1")) {
            var group = new DefaultEventLoopGroup(1); var executor = Executors.newSingleThreadExecutor();
            var bind = InetAddress.getByName(address); int port;
            try (var reservation = new DatagramSocket(new InetSocketAddress(bind, 0))) { port = reservation.getLocalPort(); }
            Path home = directory.resolve(address.equals("::1") ? "v6" : "v4"); var identity = ProviderHostIdentity.ensure(home.resolve("identity"));
            try (var store = new ProviderStateStore(home.resolve("state"))) {
                ControlledProviderStateTest.seed(store, ControlledNativeOwnerApplicationTest.ORIGIN);
                try (var storage = ControlledProviderState.open(store, ControlledDiagnosticApplicationTest.config())) {
                    var children = new AtomicInteger();
                    var nativeHost = NativeProviderTransport.openControlledVersion2(new ServerBootstrap().group(group).childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) { children.incrementAndGet(); channel.close(); }
                    }), new InetSocketAddress(bind, port), NativeCandidateSnapshot.hosts(List.of(new InetSocketAddress(bind, port))),
                            identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    try {
                        var now = new AtomicLong(System.currentTimeMillis());
                        var app = new ControlledProviderApplication(storage, nativeHost, executor, now::get, () -> null,
                                () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), null);
                        var provider = new Provider(app, storage, now, nativeHost);
                        try {
                            provider.sync(); provider.sync(); var original = provider.document;
                            assertNotNull(provider.lastAck); assertEquals(original.binding(), provider.lastAck.binding());
                            assertEquals(original, decodeInstallation(storage.application().get("diagnosticInstallation").toString()));
                            assertTrue(nativeHost.channel().isServing());
                            var report = DiagnosticApplicationPeerFixture.admittedAcrossRebind(nativeHost, identity,
                                    ControlledDiagnosticApplication.nativePolicy(original), () -> {
                                        executor.submit(() -> { app.requestKey(); return null; }).get(3, TimeUnit.SECONDS);
                                        provider.rotate = true; provider.sync(); provider.sync();
                                        assertNotEquals(original.binding().installationSha256(), provider.document.binding().installationSha256());
                                        assertEquals(provider.document.binding(), provider.lastAck.binding());
                                        assertTrue(nativeHost.channel().isServing());
                                    });
                            assertEquals(original.binding().installationSha256(), report.installation().installationSha256());
                            assertEquals(0, children.get()); assertEquals(0, nativeHost.channel().creationAttempts());
                            assertTrue(nativeHost.pollEvents(32).isEmpty());
                        } finally { app.close(); }
                    } finally { nativeHost.close().toCompletableFuture().get(5, TimeUnit.SECONDS); }
                }
            } finally { executor.shutdownNow(); group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly(); }
            try (var reclaimed = new DatagramSocket(new InetSocketAddress(bind, port))) { assertEquals(port, reclaimed.getLocalPort()); }
        }
    }
    static final class Provider {
        final ControlledProviderApplication app; final ControlledProviderState storage; final AtomicLong now; final NativeProviderTransport nativeHost;
        final ControlledNativeOwnerApplicationTest.Provider owner = new ControlledNativeOwnerApplicationTest.Provider();
        int profileSequence; boolean rotate; Installation document; Acknowledgement lastAck;
        ControlStateCodec.TicketPolicy playerPolicy = new ControlStateCodec.TicketPolicy("A001", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null)));
        Provider(ControlledProviderApplication app, ControlledProviderState storage, AtomicLong now, NativeProviderTransport nativeHost) {
            this.app = app; this.storage = storage; this.now = now; this.nativeHost = nativeHost;
        }
        void sync() throws Exception {
            app.request(); var exchange = new Exchange(); app.synchronize(exchange).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertNotNull(exchange.parent.delegate.applied);
        }
        final class Exchange implements ControlClientIo.Synchronization {
            final ControlledNativeOwnerApplicationTest.Exchange parent = new ControlledNativeOwnerApplicationTest.Exchange(owner, app, now, "serving");
            Exchange() { parent.delegate.policy = playerPolicy; }
            public long deadlineMillis() { return parent.deadlineMillis(); }
            public Optional<byte[]> pendingHeartbeat() { return Optional.empty(); }
            public void requireCurrent() { parent.requireCurrent(); }
            public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) { throw new AssertionError("unguarded"); }
            public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes, Runnable guard) {
                return parent.heartbeat(bytes, guard).thenApply(result -> {
                    var body = ControlledProviderJson.parse(new String(bytes, StandardCharsets.UTF_8), 65536);
                    var response = ControlledProviderJson.parse(new String(result.bodyBytes().orElseThrow(), StandardCharsets.UTF_8), 65536);
                    if (body.has("hostProfile")) {
                        String revision = "hpr_native_diag_" + ++profileSequence; parent.delegate.profileRevision = revision; owner.revision = revision;
                        response.addProperty("hostProfileRevision", revision);
                        response.getAsJsonObject("application").getAsJsonObject("expectedBasis").addProperty("hostProfileRevision", revision);
                    }
                    if (rotate && body.has("keyRequestId")) {
                        var key = new JsonObject(); key.addProperty("keyId", "A002"); key.addProperty("secret", "test-native-player-rotated-admission-secret"); response.add("ticketKey", key);
                        var request = new JsonObject(); request.add("id", body.get("keyRequestId")); request.addProperty("keyId", "A002"); response.add("keyRequest", request);
                        playerPolicy = new ControlStateCodec.TicketPolicy("A002", List.of(new ControlStateCodec.TicketEpoch("A001", 0, null), new ControlStateCodec.TicketEpoch("A002", 0, null)));
                        parent.delegate.policy = playerPolicy;
                    }
                    var profileJson = body.has("hostProfile") ? body.getAsJsonObject("hostProfile") : storage.application().getAsJsonObject("profile");
                    if (profileJson != null && owner.owner != null && owner.revision != null && !response.has("ticketKey")) {
                        var profile = CandidateLeaseCodec.readProfile(profileJson);
                        if (document == null || !document.binding().hostProfileSha256().equals(CandidateLeaseCodec.profileDigest(profile))) {
                            long revision = document == null ? 1 : document.binding().policyRevision() + 1;
                            document = ControlledDiagnosticApplicationTest.mutate(ControlledDiagnosticApplicationTest.document(profileJson, owner.owner, owner.revision, now.get()), json -> {
                                json.getAsJsonObject("binding").addProperty("policyRevision", revision);
                                var local = (InetSocketAddress) nativeHost.channel().localAddress(); int family = local.getAddress() instanceof Inet6Address ? 6 : 4;
                                var endpoint = json.getAsJsonArray("endpoints").get(0).getAsJsonObject(); endpoint.addProperty("family", family);
                                endpoint.addProperty("addressHex", org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.address(family, local.getAddress().getHostAddress()));
                                endpoint.addProperty("candidateRevision", 7);
                            });
                        }
                    }
                    var request = ControlDiagnosticHeartbeatCodec.decodeRequest(body.get("diagnosticAdmission").toString());
                    if (request.installed() != null) {
                        assertTrue(storage.application().has("diagnosticInstallation"));
                        assertEquals(request.installed().binding(), decodeInstallation(storage.application().get("diagnosticInstallation").toString()).binding());
                        assertEquals(request.installed().binding().installationSha256(), nativeHost.captureDiagnosticInstallation().orElseThrow().binding().installationSha256());
                        lastAck = request.installed();
                    }
                    response.add("diagnosticAdmission", JsonParser.parseString(ControlDiagnosticHeartbeatCodec.encodeResponse(new ControlDiagnosticHeartbeatCodec.Response(document, request.installed()))));
                    return ControlledApplicationResultFixture.delivered(response.toString(), () -> { requireCurrent(); guard.run(); });
                });
            }
            public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { throw new AssertionError("unguarded"); }
            public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis, Runnable guard) { return parent.applied(basis, guard); }
        }
    }
}
