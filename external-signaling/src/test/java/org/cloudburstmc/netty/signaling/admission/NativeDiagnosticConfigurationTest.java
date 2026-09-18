package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.buffer.ByteBuf;
import org.cloudburstmc.netty.signaling.ProviderTransport;
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

/** Local diagnostic configuration on the gameplay mux; independent of player serving state. */
@Tag("native")
class NativeDiagnosticConfigurationTest {
    @TempDir Path directory;
    final Key diagnosticKey = new Key("D001", "test-diagnostic-install-secret-32bytes", 0, System.currentTimeMillis()+300_000);

    final class Host implements AutoCloseable {
        final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
        final InetAddress bind;
        final int port;
        final NativeHostIdentity identity;
        final NativeProviderTransport transport;
        final AtomicReference<List<InetSocketAddress>> advertised = new AtomicReference<>();
        final AtomicInteger children = new AtomicInteger();
        Host(String address) throws Exception { this(address,true); }
        Host(String address, boolean drained) throws Exception {
            bind = InetAddress.getByName(address); port = port(bind);
            var fixture = new NativeDiagnosticHostTest(); fixture.directory=directory; identity=fixture.identity();
            advertised.set(List.of(new InetSocketAddress(bind,port)));
            var bootstrap=new ServerBootstrap().group(group)
                .childHandler(new ChannelInitializer<Channel>() { protected void initChannel(Channel channel) {
                    children.incrementAndGet(); channel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                        protected void channelRead0(ChannelHandlerContext ctx,ByteBuf bytes) {ctx.writeAndFlush(bytes.retain());}
                    });
                } });
            transport=NativeProviderTransport.open(bootstrap,new InetSocketAddress(bind,port),advertised::get,directory.resolve("host.crt"),directory.resolve("host.key"),AdmissionGate.Limits.defaults())
                .toCompletableFuture().get(5,TimeUnit.SECONDS);
            if (drained) transport.channel().drainAdmissions();
            transport.installTicketKeys(List.of(new ProviderTransport.TicketKey("K001","test-player-secret-of-at-least32bytes"))).toCompletableFuture().get();
        }
        void remap(int selectedPort) throws Exception {
            advertised.set(List.of(new InetSocketAddress(bind,selectedPort)));
            transport.captureHostProfile().toCompletableFuture().get();
        }
        DiagnosticHostPolicy policy(long endpointExpiry) throws Exception {
            var snapshot=transport.captureHostProfile().toCompletableFuture().get();
            var profile=snapshot.profile();
            var context=new Context("https://provider.example","test-host",profile.getAsJsonObject("statelessAdmission").get("incarnation").getAsString(),1);
            var endpoint=new DiagnosticHostPolicy.Endpoint(bind instanceof Inet6Address?6:4,
                DiagnosticAdmissionCodec.address(bind instanceof Inet6Address?6:4,bind.getHostAddress()),port,snapshot.candidateRevision());
            return new DiagnosticHostPolicy(context,List.of(diagnosticKey),Set.of(endpoint),endpointExpiry);
        }
        void configure(DiagnosticHostPolicy policy) throws Exception { transport.configureDiagnostics(policy).toCompletableFuture().get(5,TimeUnit.SECONDS); }
        Client client(long expiry) throws Exception { return new Client(bind,expiry); }
        void connect(Client client,DiagnosticHostPolicy policy) throws Exception {
            client.connect(identity,policy.context(),diagnosticKey,bind instanceof Inet6Address?6:4,bind.getHostAddress(),port,false,policy.endpoints().iterator().next().candidateRevision(),null,null);
        }
        public void close() throws Exception {
            transport.close().toCompletableFuture().get(6,TimeUnit.SECONDS);
            group.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
        }
    }

    @Test @Timeout(30) void bothFamiliesUseLocalConfigurationWithoutEnablingPlayers() throws Exception {
        for(String family:List.of("127.0.0.1","::1"))try(Host host=new Host(family)) {
            assertTrue(host.transport.supportsDiagnosticAdmission());assertFalse(host.transport.channel().isServing());
            assertTrue(host.transport.pollDiagnosticResults(0).isEmpty());assertTrue(host.transport.diagnosticDroppedResultCount().isEmpty());
            long expiry=(System.currentTimeMillis()+15000)/1000*1000;var policy=host.policy(expiry+1000);host.configure(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);client.start(false);var results=new ArrayList<NativeDiagnosticHostGate.Result>();
                await(()->{client.tick();results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                var result=results.get(0);assertTrue(result.success(),result.toString());assertTrue(result.authenticated());assertTrue(result.cleanupComplete());
                assertTrue(client.exchange.complete());assertEquals(expiry,result.expiresAt());
                assertEquals(0,host.children.get());assertEquals(0,host.transport.channel().creationAttempts());assertEquals(0,host.transport.channel().liveNativePeers());
                assertFalse(host.transport.channel().isServing());assertTrue(host.transport.pollEvents(32).isEmpty());
                assertEquals(0,host.transport.diagnosticDroppedResultCount().orElseThrow());
            }
            host.transport.disableDiagnostics().toCompletableFuture().get();
            assertThrows(IllegalArgumentException.class,()->host.transport.pollDiagnosticResults(33));
        }
    }
    @Test @Timeout(25) void ordinaryTransportSupportsDiagnosticsWhileServing() throws Exception {
        for(String address:List.of("127.0.0.1","::1"))try(Host host=new Host(address,false)) {
            assertFalse(host.transport.hostProfile().toCompletableFuture().get().has("version"));
            assertTrue(host.transport.channel().isServing());
            long expiry=(System.currentTimeMillis()+15000)/1000*1000;var policy=host.policy(expiry+1000);host.configure(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);client.start(false);var results=new ArrayList<NativeDiagnosticHostGate.Result>();
                await(()->{client.tick();results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertTrue(results.get(0).success());assertTrue(results.get(0).authenticated());assertTrue(client.exchange.complete());
                assertTrue(results.get(0).cleanupComplete());assertEquals(0,host.children.get());assertEquals(0,host.transport.channel().creationAttempts());
            }
            host.transport.disableDiagnostics().toCompletableFuture().get();assertTrue(host.transport.channel().isServing());
        }
    }
    @Test @Timeout(25) void ordinarySnapshotRevisionFencesRemapAbaAndDrain() throws Exception {
        for(String address:List.of("127.0.0.1","::1")) try(Host host=new Host(address,false)) {
            var first=host.transport.captureHostProfile().toCompletableFuture().get();
            var policy=host.policy(System.currentTimeMillis()+20000);
            assertEquals(1,first.candidateRevision());host.configure(policy);
            assertEquals(first.candidateRevision(),host.transport.captureHostProfile().toCompletableFuture().get().candidateRevision());
            host.advertised.set(List.of(new InetSocketAddress(host.bind,host.port+1)));
            var second=host.transport.captureHostProfile().toCompletableFuture().get();
            assertTrue(second.candidateRevision()>first.candidateRevision());assertThrows(ProviderTransport.HostProfileSnapshotChangedException.class,first::requireCurrent);
            host.advertised.set(List.of(new InetSocketAddress(host.bind,host.port)));
            var third=host.transport.captureHostProfile().toCompletableFuture().get();
            assertTrue(third.candidateRevision()>second.candidateRevision());
            assertThrows(ExecutionException.class,()->host.transport.configureDiagnostics(policy).toCompletableFuture().get());
            var current=host.policy(System.currentTimeMillis()+20000);host.configure(current);
            host.transport.drain().toCompletableFuture().get();assertEquals(IllegalStateException.class,assertThrows(IllegalStateException.class,third::requireCurrent).getClass());
            assertThrows(ExecutionException.class,()->host.transport.configureDiagnostics(current).toCompletableFuture().get());
        }
    }
    @Test @Timeout(15) void queuedConfigurationUsesOriginalMonotonicDeadline() throws Exception {
        try(Host host=new Host("127.0.0.1",false)) {
            var policy=host.policy(System.currentTimeMillis()+20000);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            host.transport.channel().eventLoop().execute(()->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var pending=host.transport.configureDiagnostics(policy,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100)).toCompletableFuture();
            try {Thread.sleep(150);} finally {release.countDown();}
            assertThrows(ExecutionException.class,()->pending.get(3,TimeUnit.SECONDS));
            assertTrue(host.transport.diagnosticDroppedResultCount().isEmpty());assertTrue(host.transport.channel().isServing());
        }
    }
    @Test @Timeout(25) void queuedConfigurationCannotSurviveDisableRemapOrClose() throws Exception {
        for(String change:List.of("disable","remap","close"))try(Host host=new Host("127.0.0.1")) {
            var policy=host.policy(System.currentTimeMillis()+20000);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            host.transport.channel().eventLoop().execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var queued=host.transport.configureDiagnostics(policy).toCompletableFuture();
            var overCapacity=host.transport.configureDiagnostics(policy).toCompletableFuture();
            assertThrows(ExecutionException.class,()->overCapacity.get(1,TimeUnit.SECONDS));
            try {
                switch(change){case "disable"->host.transport.disableDiagnostics();case "remap"->host.remap(host.port+1);case "close"->host.transport.close();}
            } finally {release.countDown();}
            assertThrows(ExecutionException.class,()->queued.get(5,TimeUnit.SECONDS),change);assertEquals(0,host.children.get());
        }
    }
    @Test @Timeout(30) void withdrawalRemapAndContextChangesRetireDiagnosticsWithoutOpeningPlayers() throws Exception {
        for(String change:List.of("disable","remap","generation","key"))try(Host host=new Host("127.0.0.1")) {
            long expiry=(System.currentTimeMillis()+15000)/1000*1000;var policy=host.policy(expiry+1000);host.configure(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());
                switch(change) {
                    case "disable"->host.transport.disableDiagnostics().toCompletableFuture().get();
                    case "remap"->{host.remap(host.port+1);host.remap(host.port);}
                    case "generation"->host.configure(new DiagnosticHostPolicy(new Context(policy.context().providerOrigin(),policy.context().hostId(),policy.context().incarnation(),2),policy.keys(),policy.endpoints(),policy.expiresAt()));
                    case "key"->host.configure(new DiagnosticHostPolicy(policy.context(),List.of(),policy.endpoints(),policy.expiresAt()));
                }
                var results=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertFalse(results.get(0).success(),change);assertTrue(results.get(0).cleanupComplete());assertEquals(0,host.transport.channel().liveNativePeers());
                assertFalse(host.transport.channel().isServing());assertEquals(0,host.children.get());
            }
        }
    }
    @Test @Timeout(25) void refreshNeverExtendsAnAdmittedAttempt() throws Exception {
        try(Host host=new Host("127.0.0.1")) {
            long expiry=(System.currentTimeMillis()+3500)/1000*1000;var policy=host.policy(expiry+1000);host.configure(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);await(()->client.channels[0].isOpen()&&client.channels[1].isOpen());host.configure(host.policy(expiry+20000));
                var results=new ArrayList<NativeDiagnosticHostGate.Result>();await(()->{results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});
                assertFalse(results.get(0).success());assertEquals(expiry,results.get(0).expiresAt());assertTrue(results.get(0).cleanupComplete());
            }
        }
    }
    @Test @Timeout(25) void familyDeadlinesAreIndependentAndPermitMustFitItsExactEndpoint() throws Exception {
        try(Host host=new Host("::1")) {
            long expiry=(System.currentTimeMillis()+15000)/1000*1000;var policy=host.policy(expiry+1000);var six=policy.endpoints().iterator().next();
            var four=new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.1"),host.port,8);
            long shortExpiry=System.currentTimeMillis()+500;
            var gate=host.transport.channel().enableDiagnostics(new DiagnosticHostPolicy(policy.context(),policy.keys(),Set.of(four,six),policy.expiresAt(),Map.of(four,shortExpiry,six,policy.expiresAt()))).toCompletableFuture().get();
            while(System.currentTimeMillis()<=shortExpiry)Thread.sleep(10);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);client.start(false);var results=new ArrayList<NativeDiagnosticHostGate.Result>();
                await(()->{client.tick();results.addAll(gate.pollResults());return !results.isEmpty();});assertTrue(results.get(0).success());
            }
            gate.replacePolicy(new DiagnosticHostPolicy(policy.context(),policy.keys(),Set.of(six),policy.expiresAt(),Map.of(six,System.currentTimeMillis()+1000)));
            try(Client client=host.client(expiry)){long rejected=gate.stats().rejected();host.connect(client,policy);await(()->gate.stats().rejected()>rejected);assertEquals(0,gate.stats().active());}
        }
    }
    @Test @Timeout(25) void foreignListenerAndEndpointsFailClosed() throws Exception {
        try(Host host=new Host("127.0.0.1")) {
            var policy=host.policy(System.currentTimeMillis()+20000);
            var foreign=new Context(policy.context().providerOrigin(),policy.context().hostId(),"ff".repeat(16),1);
            var wrongEndpoint=new DiagnosticHostPolicy.Endpoint(4,DiagnosticAdmissionCodec.address(4,"127.0.0.2"),host.port,7);
            for(var bad:List.of(new DiagnosticHostPolicy(foreign,policy.keys(),policy.endpoints(),policy.expiresAt()),
                    new DiagnosticHostPolicy(policy.context(),policy.keys(),Set.of(wrongEndpoint),policy.expiresAt())))
                assertThrows(ExecutionException.class,()->host.transport.configureDiagnostics(bad).toCompletableFuture().get(3,TimeUnit.SECONDS));
            assertTrue(host.transport.diagnosticDroppedResultCount().isEmpty());assertFalse(host.transport.channel().isServing());
        }
    }
    @Test @Timeout(25) void localConfigurationAndWithdrawalPreserveExistingPlayer() throws Exception {
        try(Host host=new Host("127.0.0.1",false);PeerConnection player=PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(InetAddress.getLoopbackAddress()).withDisableAutoNegotiation(true))) {
            host.transport.installTicketKeys(List.of(new ProviderTransport.TicketKey("K001",TestSignalingProvider.SECRET))).toCompletableFuture().get();
            long expiry=(System.currentTimeMillis()+20000)/1000*1000;AtomicInteger echoes=new AtomicInteger();
            var reliable=player.createDataChannel("ReliableDataChannel");player.createDataChannel("UnreliableDataChannel",DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT.withUnordered(true).withUnreliable(true).withMaxRetransmits(0)));
            reliable.onMessage.register(DataChannelCallback.Message.handleBinary((channel,bytes)->{if(bytes.remaining()==2&&bytes.get()==0&&bytes.get()==42)echoes.incrementAndGet();}));
            player.setLocalDescription("offer","playerFixture","p".repeat(24));
            var answer=TestSignalingProvider.answer(player.localDescription(),host.identity.fingerprint(),host.port,expiry,NativeProviderTransport.audience(host.transport.hostProfile().toCompletableFuture().get().getAsJsonObject("statelessAdmission").get("incarnation").getAsString()),false);
            player.setRemoteDescription(answer.sdp(),SessionDescriptionType.ANSWER);await(reliable::isOpen);
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==1);
            var policy=host.policy(expiry+1000);host.configure(policy);
            try(Client client=host.client(expiry)) {
                host.connect(client,policy);client.start(false);var results=new ArrayList<NativeDiagnosticHostGate.Result>();
                await(()->{client.tick();results.addAll(host.transport.pollDiagnosticResults(1));return !results.isEmpty();});assertTrue(results.get(0).success());
            }
            host.transport.disableDiagnostics().toCompletableFuture().get();assertTrue(host.transport.channel().isServing());assertEquals(1,host.transport.channel().liveNativePeers());assertEquals(1,host.children.get());
            reliable.sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)42).flip());await(()->echoes.get()==2);
            assertTrue(player.closeAndAwait(Duration.ofSeconds(5)));
        }
    }
}
