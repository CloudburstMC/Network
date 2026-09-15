package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.control.CandidateLeaseCodec;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.time.Duration;
import tel.schich.libdatachannel.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.cloudburstmc.netty.signaling.admission.NativeDiagnosticHostTest.*;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Actual ProviderTransport installation on the gameplay mux; no control facade or player application claim. */
@Tag("native")
class NativeDiagnosticInstallationTest {
    @TempDir Path directory;
    final Key diagnosticKey = new Key("D001", "test-diagnostic-install-secret-32bytes", 0, System.currentTimeMillis()+300_000);

    final class Host implements AutoCloseable {
        final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        final InetAddress bind;
        final int port;
        final NativeHostIdentity identity;
        final NativeProviderTransport transport;
        final AtomicInteger children = new AtomicInteger();
        Host(String address) throws Exception {
            bind = InetAddress.getByName(address); port = port(bind);
            var fixture = new NativeDiagnosticHostTest(); fixture.directory=directory; identity=fixture.identity();
            transport=NativeProviderTransport.openControlledVersion2(new ServerBootstrap().group(group)
                .childHandler(new ChannelInitializer<Channel>() { protected void initChannel(Channel channel) {
                    children.incrementAndGet(); channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        protected void channelRead0(ChannelHandlerContext ctx,ByteBuf bytes) {ctx.writeAndFlush(bytes.retain());}
                    });
                } }),
                new InetSocketAddress(bind,port), snapshot(port), directory.resolve("host.crt"),directory.resolve("host.key"),AdmissionGate.Limits.defaults())
                .toCompletableFuture().get(5,TimeUnit.SECONDS);
            transport.installTicketKeys(List.of(new ProviderTransport.TicketKey("K001","test-player-secret-of-at-least32bytes"))).toCompletableFuture().get();
        }
        NativeCandidateSnapshot snapshot(int selectedPort) { return NativeCandidateSnapshot.hosts(List.of(new InetSocketAddress(bind,selectedPort))); }
        DiagnosticAdmission.Policy policy(long owner, long revision, long endpointExpiry) throws Exception {
            var profile=CandidateLeaseCodec.readProfile(transport.hostProfile().toCompletableFuture().get());
            var context=new Context("https://provider.example","test-host",profile.nativeIncarnation(),1);
            var binding=new DiagnosticAdmission.Binding(context,"authority_fixture_001",owner,"host_profile_revision_"+revision,
                CandidateLeaseCodec.profileDigest(profile),revision,"A".repeat(43),identity.fingerprint().substring(8).replace(":","").toLowerCase(Locale.ROOT));
            var endpoint=new DiagnosticAdmission.Endpoint(new DiagnosticHostPolicy.Endpoint(bind instanceof Inet6Address?6:4,
                DiagnosticAdmissionCodec.address(bind instanceof Inet6Address?6:4,bind.getHostAddress()),port,7),"host",endpointExpiry);
            return new DiagnosticAdmission.Policy(binding,List.of(diagnosticKey),List.of(endpoint),System.currentTimeMillis()-1000,endpointExpiry+20_000);
        }
        DiagnosticAdmission.Installation install(DiagnosticAdmission.Policy policy) throws Exception {
            return transport.installDiagnosticPolicy(policy,()->{}).toCompletableFuture().get(5,TimeUnit.SECONDS);
        }
        Client client(long expiry) throws Exception { return new Client(bind,port,expiry); }
        void connect(Client client,DiagnosticAdmission.Policy policy) throws Exception {
            client.connect(identity,policy.binding().context(),diagnosticKey,bind instanceof Inet6Address?6:4,bind.getHostAddress(),port,false);
        }
        public void close() throws Exception {
            transport.close().toCompletableFuture().get(6,TimeUnit.SECONDS);
            group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
        }
    }

    @Test @Timeout(40) void providerOwnedBothFamiliesRetainAdmissionBindingThroughRenewalAndBoundedPolling() throws Exception {
        for(String family:List.of("127.0.0.1","::1")) try(Host host=new Host(family)) {
            assertTrue(host.transport.supportsDiagnosticAdmission()); assertTrue(host.transport.captureDiagnosticInstallation().isEmpty());
            assertTrue(host.transport.pollDiagnosticResults(0).isEmpty()); assertFalse(host.transport.channel().isServing());
            long expiry=(System.currentTimeMillis()+18_000)/1000*1000;
            var original=host.policy(1,1,expiry+1000); var first=host.install(original); first.requireCurrent();
            try(Client client=host.client(expiry)) {
                host.connect(client,original); await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());
                // Rebind the full player profile to a new key while this peer still owns its old diagnostic binding.
                host.transport.installTicketKeys(List.of(new ProviderTransport.TicketKey("K002","replacement-player-secret-at-least32bytes"))).toCompletableFuture().get();
                assertThrows(IllegalStateException.class,first::requireCurrent);
                var renewed=host.policy(1,2,expiry+5000); var second=host.install(renewed); second.requireCurrent();
                assertFalse(host.transport.withdrawDiagnosticPolicy(first).toCompletableFuture().get()); second.requireCurrent();
                client.start(false); var completed=new ArrayList<DiagnosticAdmission.Completion>();
                await(()->{client.tick(); completed.addAll(host.transport.pollDiagnosticResults(1)); return !completed.isEmpty();});
                var report=completed.get(0); assertTrue(report.success(),report.toString()); assertTrue(report.cleanupComplete());
                assertEquals(original.binding(),report.installation()); assertNotEquals(renewed.binding(),report.installation());
                assertEquals(expiry,report.expiresAt()); assertEquals(client.exchange.completionDigestHex(),report.completionDigestHex());
                assertEquals(host.port,report.selectedLocal().getPort()); assertEquals(host.bind,report.selectedRemote().getAddress());
                assertTrue(report.udp().sent()>0); assertTrue(report.udp().reserved()<=256); assertEquals(0,report.udp().rejected());
                assertEquals(0,host.children.get()); assertEquals(0,host.transport.channel().creationAttempts());
                assertEquals(0,host.transport.channel().liveNativePeers()); assertTrue(host.transport.pollEvents(32).isEmpty());
                assertFalse(host.transport.channel().isServing());
                try(Client next=host.client(expiry)) {
                    host.connect(next,renewed); next.start(true);
                    await(()->host.transport.channel().liveNativePeers()==0);
                    assertTrue(host.transport.pollDiagnosticResults(0).isEmpty());
                    var failure=new ArrayList<DiagnosticAdmission.Completion>();
                    await(()->{failure.addAll(host.transport.pollDiagnosticResults(1));return !failure.isEmpty();});
                    assertFalse(failure.get(0).success()); assertTrue(failure.get(0).cleanupComplete()); assertNull(failure.get(0).completionDigestHex());
                    assertEquals(renewed.binding(),failure.get(0).installation());
                }
                assertThrows(IllegalArgumentException.class,()->host.transport.pollDiagnosticResults(33));
                assertTrue(host.transport.withdrawDiagnosticPolicy(second).toCompletableFuture().get());
                assertTrue(host.transport.captureDiagnosticInstallation().isEmpty()); assertThrows(IllegalStateException.class,second::requireCurrent);
            }
        }
    }

    @Test @Timeout(25) void shorterSameEndpointRenewalPreservesTheOriginalAdmittedDeadline() throws Exception {
        try (Host host = new Host("127.0.0.1")) {
            long expiry = (System.currentTimeMillis() + 15_000) / 1000 * 1000;
            var original = host.policy(1, 1, expiry + 1000);
            host.install(original);
            try (Client client = host.client(expiry)) {
                host.connect(client, original);
                await(() -> client.channels[0].isOpen() && client.channels[1].isOpen());
                long shortenedExpiry = System.currentTimeMillis() + 1500;
                var updated = host.policy(1, 2, shortenedExpiry);
                var renewed = new DiagnosticAdmission.Policy(updated.binding(), updated.keys(), updated.endpoints(),
                    updated.notBefore(), shortenedExpiry);
                var current = host.install(renewed);
                while (System.currentTimeMillis() <= shortenedExpiry + 100) Thread.sleep(10);
                // New admission/ACK authority ended, but the original permit still owns its fixed window.
                assertThrows(IllegalStateException.class, current::requireCurrent);
                client.start(false);
                var results = new ArrayList<DiagnosticAdmission.Completion>();
                await(() -> { client.tick(); results.addAll(host.transport.pollDiagnosticResults(1)); return !results.isEmpty(); });
                assertTrue(results.get(0).success(), results.toString());
                assertTrue(results.get(0).cleanupComplete());
                assertEquals(original.binding(), results.get(0).installation());
                assertEquals(expiry, results.get(0).expiresAt());
            }
        }
    }

    @Test @Timeout(35) void queuedAndPostInstallGuardsWithdrawOnlyTheirOwnPolicy() throws Exception {
        try(Host host=new Host("127.0.0.1")) {
            long expiry=System.currentTimeMillis()+25_000;
            var first=host.install(host.policy(1,1,expiry));
            CountDownLatch blocked=new CountDownLatch(1), release=new CountDownLatch(1);
            host.transport.channel().eventLoop().execute(()->{blocked.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}});
            assertTrue(blocked.await(3,TimeUnit.SECONDS));
            var replacement=host.transport.installDiagnosticPolicy(host.policy(1,2,expiry),()->{}).toCompletableFuture();
            var overCapacity=host.transport.installDiagnosticPolicy(host.policy(1,3,expiry),()->{}).toCompletableFuture();
            assertThrows(ExecutionException.class,()->overCapacity.get(1,TimeUnit.SECONDS));
            // Old cleanup cannot cancel a newer pending install, even before its event-loop execution.
            assertFalse(host.transport.withdrawDiagnosticPolicy(first).toCompletableFuture().get());
            release.countDown(); var second=replacement.get(5,TimeUnit.SECONDS);second.requireCurrent();
            assertFalse(host.transport.withdrawDiagnosticPolicy(first).toCompletableFuture().get());
            AtomicInteger guard=new AtomicInteger();
            var bad=host.transport.installDiagnosticPolicy(host.policy(1,3,expiry),()->{if(guard.incrementAndGet()==3)throw new IllegalStateException("after-install");}).toCompletableFuture();
            assertThrows(ExecutionException.class,()->bad.get(5,TimeUnit.SECONDS)); assertTrue(host.transport.captureDiagnosticInstallation().isEmpty());
            var third=host.install(host.policy(1,4,expiry)); third.requireCurrent();
            AtomicInteger keys=new AtomicInteger();
            var changed=host.transport.installDiagnosticPolicy(host.policy(1,5,expiry),()->{
                if(keys.incrementAndGet()==2)host.transport.installTicketKeys(List.of(new ProviderTransport.TicketKey("K003","third-player-secret-at-least32bytes")));
            }).toCompletableFuture();
            assertThrows(ExecutionException.class,()->changed.get(5,TimeUnit.SECONDS));
            assertTrue(host.transport.captureDiagnosticInstallation().isEmpty());
            assertEquals(0,host.transport.channel().liveNativePeers()); assertEquals(0,host.children.get());
        }
    }

    @Test @Timeout(35) void remapAndOwnerReplacementStopOldPeersAndNeverRebindResults() throws Exception {
        for(String change:List.of("remap","owner","withdraw","drain")) try(Host host=new Host("127.0.0.1")) {
            long expiry=(System.currentTimeMillis()+15_000)/1000*1000;
            var policy=host.policy(1,1,expiry+1000);var installed=host.install(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());
                switch(change) {
                    case "remap" -> {host.transport.replaceCandidates(host.snapshot(host.port+1));host.transport.replaceCandidates(host.snapshot(host.port));}
                    case "owner" -> host.install(host.policy(2,2,expiry+1000));
                    case "withdraw" -> host.transport.withdrawDiagnosticPolicy(installed).toCompletableFuture().get();
                    case "drain" -> host.transport.drain().toCompletableFuture().get();
                }
                assertThrows(IllegalStateException.class,installed::requireCurrent);
                var results=new ArrayList<DiagnosticAdmission.Completion>();await(()->{results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertFalse(results.get(0).success(),change);assertTrue(results.get(0).cleanupComplete(),change);
                assertEquals(policy.binding(),results.get(0).installation(),change);assertEquals(0,host.transport.channel().liveNativePeers());
            }
        }
    }

    @Test @Timeout(25) void independentFamilyExpiryAndUninstalledLongPermitFailClosed() throws Exception {
        // One expired IPv4 authority cannot cap the IPv6 endpoint's parent or live attempt.
        try(Host host=new Host("::1")) {
            long expiry=(System.currentTimeMillis()+15_000)/1000*1000;
            var base=host.policy(1,1,expiry+2000);
            var shortFour=new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),host.port,8);
            var six=base.endpoints().get(0).target();long shortExpiry=System.currentTimeMillis()+600;
            var low=new DiagnosticHostPolicy(base.binding().context(),List.of(diagnosticKey),Set.of(shortFour,six),base.expiresAt(),
                Map.of(shortFour,shortExpiry,six,expiry+2000),base.binding());
            var gate=host.transport.channel().enableDiagnostics(low).toCompletableFuture().get();
            while(System.currentTimeMillis()<shortExpiry)Thread.sleep(10);
            try(Client client=host.client(expiry)) {
                host.connect(client,base);client.start(false);var results=new ArrayList<NativeDiagnosticHostGate.Result>();
                await(()->{client.tick();results.addAll(gate.pollResults(1));return !results.isEmpty();});
                assertTrue(results.get(0).success(),results.toString());assertEquals(6,results.get(0).target().family());
            }
            // Fixed permit end must fit this endpoint, even while the global parent remains live.
            gate.replacePolicy(new DiagnosticHostPolicy(low.context(),low.keys(),Set.of(six),low.expiresAt(),Map.of(six,System.currentTimeMillis()+1000),low.installation()));
            try(Client tooLong=host.client(expiry)) {
                long rejected=gate.stats().rejected();host.connect(tooLong,base);await(()->gate.stats().rejected()>rejected);
                assertEquals(0,gate.stats().active());assertEquals(0,host.transport.channel().creationAttempts());
            }
        }
    }

    @Test @Timeout(30) void installedDiagnosticReplacementAndWithdrawalLeaveExistingPlayerAndAdmissionAlone() throws Exception {
        try(Host host=new Host("127.0.0.1"); PeerConnection player=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(InetAddress.getLoopbackAddress()).withDisableAutoNegotiation(true))) {
            var update=host.transport.beginAdmissionUpdate(System.nanoTime()+TimeUnit.SECONDS.toNanos(30));
            host.transport.installTicketKeys(update,List.of(new ProviderTransport.TicketKey("K001",TestSignalingProvider.SECRET))).toCompletableFuture().get();
            assertEquals(ProviderTransport.ApplyResult.APPLIED,host.transport.commitAdmissionUpdate(update,()->{}).toCompletableFuture().get());
            long expiry=(System.currentTimeMillis()+20_000)/1000*1000;
            AtomicInteger echoes=new AtomicInteger();
            var reliable=player.createDataChannel("ReliableDataChannel");
            player.createDataChannel("UnreliableDataChannel",DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT.withUnordered(true).withUnreliable(true).withMaxRetransmits(0)));
            reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel,bytes)->{if(bytes.remaining()==2&&bytes.get()==0&&bytes.get()==42)echoes.incrementAndGet();}));
            player.setLocalDescription("offer","playerFixture","p".repeat(24));
            String audience=NativeProviderTransport.audience(host.transport.captureNativeIdentity().incarnation());
            var answer=TestSignalingProvider.answer(player.localDescription(),host.identity.fingerprint(),host.port,expiry,audience,false);
            player.setRemoteDescription(answer.sdp(),SessionDescriptionType.ANSWER);await(reliable::isOpen);
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==1);
            var events=new ArrayList<com.google.gson.JsonObject>();
            await(()->{events.addAll(host.transport.pollEvents(32));return events.stream().anyMatch(event->event.get("stage").getAsString().equals("ticket.data_channels_open"));});
            var policy=host.policy(1,1,expiry+1000);var installed=host.install(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);client.start(false);var results=new ArrayList<DiagnosticAdmission.Completion>();
                await(()->{client.tick();results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertTrue(results.get(0).success(),results.toString());
            }
            assertTrue(host.transport.channel().isServing());assertEquals(1,host.transport.channel().liveNativePeers());
            assertTrue(host.transport.withdrawDiagnosticPolicy(installed).toCompletableFuture().get());
            assertTrue(host.transport.channel().isServing());assertEquals(1,host.children.get());assertEquals(1,host.transport.channel().creationAttempts());
            assertTrue(host.transport.pollEvents(32).isEmpty());
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==2);
            assertTrue(player.closeAndAwait(Duration.ofSeconds(5)));
        }
    }

    @Test @Timeout(25) void originalExpiryCannotBeExtendedByPolicyRefreshAndBadIdentityNeverInstalls() throws Exception {
        try(Host host=new Host("127.0.0.1")) {
            long expiry=(System.currentTimeMillis()+4000)/1000*1000;
            var policy=host.policy(1,1,expiry+1000);var original=host.install(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());
                host.install(host.policy(1,2,expiry+20_000));
                var results=new ArrayList<DiagnosticAdmission.Completion>();
                await(()->{results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertFalse(results.get(0).success());assertEquals(expiry,results.get(0).expiresAt());
                assertEquals(policy.binding(),results.get(0).installation());assertTrue(results.get(0).cleanupComplete());
                assertThrows(IllegalStateException.class,original::requireCurrent);
            }
            var binding=host.policy(1,3,expiry+20_000).binding();
            for(String failure:List.of("incarnation","fingerprint","profile")) {
                var bad=new DiagnosticAdmission.Binding(failure.equals("incarnation")?new Context(binding.context().providerOrigin(),binding.context().hostId(),"ff".repeat(16),1):binding.context(),
                    binding.authorityIncarnation(),1,binding.hostProfileRevision(),failure.equals("profile")?"A".repeat(43):binding.hostProfileSha256(),3,binding.installationSha256(),failure.equals("fingerprint")?"00".repeat(32):binding.hostFingerprintHex());
                var requested=new DiagnosticAdmission.Policy(bad,policy.keys(),host.policy(1,3,expiry+20_000).endpoints(),policy.notBefore(),expiry+30_000);
                assertThrows(ExecutionException.class,()->host.transport.installDiagnosticPolicy(requested,()->{}).toCompletableFuture().get(3,TimeUnit.SECONDS),failure);
            }
        }
    }

    @Test @Timeout(20) void exactThirtyTwoEndpointNativeSnapshotAndQueuedRemapAreValidated() throws Exception {
        try(Host host=new Host("127.0.0.1")) {
            var addresses=new ArrayList<InetSocketAddress>();
            for(int i=1;i<=32;i++)addresses.add(new InetSocketAddress(InetAddress.getByName("127.0.0."+i),host.port));
            host.transport.replaceCandidates(NativeCandidateSnapshot.hosts(addresses));
            long expiry=System.currentTimeMillis()+25_000;var base=host.policy(1,1,expiry);
            var endpoints=new ArrayList<DiagnosticAdmission.Endpoint>();
            for(var address:addresses)endpoints.add(new DiagnosticAdmission.Endpoint(new DiagnosticHostPolicy.Endpoint(4,
                DiagnosticAdmissionCodec.address(4,address.getAddress().getHostAddress()),host.port,7),"host",expiry));
            var full=new DiagnosticAdmission.Policy(base.binding(),base.keys(),endpoints,base.notBefore(),base.expiresAt());
            var installed=host.install(full);installed.requireCurrent();assertEquals(32,host.transport.hostProfile().toCompletableFuture().get().getAsJsonArray("candidates").size());
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            host.transport.channel().eventLoop().execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var queued=host.transport.installDiagnosticPolicy(full,()->{}).toCompletableFuture();
            host.transport.replaceCandidates(host.snapshot(host.port+1));
            release.countDown();assertThrows(ExecutionException.class,()->queued.get(5,TimeUnit.SECONDS));
            assertTrue(host.transport.captureDiagnosticInstallation().isEmpty());assertEquals(0,host.children.get());
        }
    }
}
