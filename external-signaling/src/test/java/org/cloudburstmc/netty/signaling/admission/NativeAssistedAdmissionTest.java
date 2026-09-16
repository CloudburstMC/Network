package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import org.cloudburstmc.netty.util.nethernet.TransportIdentityBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.*;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("native")
class NativeAssistedAdmissionTest {
    @TempDir Path directory;

    @Test @Timeout(35)
    void timedOutPrecreatedPeersReleaseAssistedCapacityAfterNativeCleanup() throws Exception {
        var identityHelper = new NativeDiagnosticHostTest(); identityHelper.directory = directory;
        var identity = identityHelper.identity();
        var bind = InetAddress.getByName("127.0.0.1");
        var loop = new DefaultEventLoopGroup(1);
        var endpoint = new NativeAdmissionServerChannel(identity, (request,now) -> null, new AdmissionGate.Limits(2,64,1,100));
        try {
            new ServerBootstrap().group(loop).channelFactory(() -> endpoint)
                    .childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                        protected void initChannel(AdmittedNetherNetChildChannel child) { }
                    }).bind(bind,NativeDiagnosticHostTest.port(bind)).sync();
            try (var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(bind).withDisableAutoNegotiation(true),Runnable::run)) {
                var gathered = new CountDownLatch(1);
                client.onGatheringStateChange.register((peer,state) -> { if(state==GatheringState.RTC_GATHERING_COMPLETE) gathered.countDown(); });
                client.createDataChannel("ReliableDataChannel");
                client.setLocalDescription("offer","timeoutClient","c".repeat(32));
                assertTrue(gathered.await(5,TimeUnit.SECONDS));
                for(int attempt=0;attempt<33;attempt++) {
                    var join=new AssistedJoin(String.format("%032x",attempt+1),"instance-test",1,"34".repeat(16),"K001",identity.fingerprint(),
                            System.currentTimeMillis()+15000,"1234",TestSignalingProvider.IDENTITY_CPK,
                            "timeoutHost"+attempt,"h".repeat(32),client.localDescription());
                    endpoint.assist(join,()->{}).toCompletableFuture().get(5,TimeUnit.SECONDS);
                    // Deliberately withhold the answer: no peer can reach activation or consume the CPK.
                    NativeDiagnosticHostTest.await(()->endpoint.liveNativePeers()==0);
                }
                assertEquals(33,endpoint.creationAttempts(),"Timeouts cannot permanently consume the32 assisted slots");
                assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
            }
        } finally {
            endpoint.close().awaitUninterruptibly();endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);
            assertEquals(0,endpoint.liveNativePeers());loop.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
        }
    }

    @Test @Timeout(40)
    void assistedPeerUsesRealGameplayChildIdentityAndTwoWayChannelsBothFamilies() throws Exception {
        var identityHelper = new NativeDiagnosticHostTest(); identityHelper.directory = directory;
        var identity = identityHelper.identity();
        var cpk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(TestSignalingProvider.IDENTITY_CPK)));
        for (String numeric : List.of("127.0.0.1", "::1")) {
            InetAddress bind = InetAddress.getByName(numeric);
            int port = NativeDiagnosticHostTest.port(bind);
            var loop = new DefaultEventLoopGroup(1);
            var endpoint = new NativeAdmissionServerChannel(identity, (r,now) -> null, new AdmissionGate.Limits(4,8,2,15000));
            var child = new AtomicReference<AdmittedNetherNetChildChannel>();
            var errors = new LinkedBlockingQueue<Throwable>();
            var echoes = new LinkedBlockingQueue<String>();
            try {
                new ServerBootstrap().group(loop).channelFactory(() -> endpoint).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                    protected void initChannel(AdmittedNetherNetChildChannel ch) {
                        child.set(ch);
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            boolean reliable = true, checked;
                            public void userEventTriggered(ChannelHandlerContext ctx,Object event) {
                                if (event instanceof NetherNetPacket.Delivery d) reliable = d.reliable();
                            }
                            protected void channelRead0(ChannelHandlerContext ctx,ByteBuf data) {
                                if (!checked) { assertNull(TransportIdentityBinding.mismatch(ch, cpk)); checked = true; }
                                ctx.writeAndFlush(new NetherNetPacket(data.retainedDuplicate(), reliable));
                            }
                            public void exceptionCaught(ChannelHandlerContext ctx,Throwable failure) { errors.add(failure); ctx.close(); }
                        });
                    }
                }).bind(bind,port).sync();
                try (var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT.withBindAddress(bind).withDisableAutoNegotiation(true), Runnable::run)) {
                    CountDownLatch gathered = new CountDownLatch(1);
                    client.onGatheringStateChange.register((p,s) -> { if (s == GatheringState.RTC_GATHERING_COMPLETE) gathered.countDown(); });
                    DataChannel[] channels = new DataChannel[2];
                    for (int i=0;i<2;i++) {
                        channels[i] = client.createDataChannel(i == 0 ? "ReliableDataChannel" : "UnreliableDataChannel",
                                DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT.withUnordered(i==1).withUnreliable(i==1).withMaxRetransmits(0)));
                        channels[i].onMessage.register(new DataChannelCallback.Message() {
                            public void onText(DataChannel dc,String s) { errors.add(new AssertionError("binary required")); }
                            public void onBinary(DataChannel dc,ByteBuffer bytes) { assertEquals(0,bytes.get()); echoes.add(dc.label()+":"+bytes.get()); }
                        });
                    }
                    client.setLocalDescription("offer","assistedPlayer", "p".repeat(128)); // Stateless 91-byte bound remains unchanged.
                    assertTrue(gathered.await(5,TimeUnit.SECONDS));
                    var join = new AssistedJoin("12".repeat(16), "instance-test", 1, "34".repeat(16), "K001", identity.fingerprint(),
                            System.currentTimeMillis()+15000, "1234", TestSignalingProvider.IDENTITY_CPK,
                            "assistedLocalUfrag", "s".repeat(32), client.localDescription());
                    String answer = endpoint.assist(join, () -> {}).toCompletableFuture().get(5,TimeUnit.SECONDS);
                    assertEquals(1,endpoint.creationAttempts(),"peer exists before client receives an answer");
                    assertThrows(ExecutionException.class, () -> endpoint.assist(join, () -> {}).toCompletableFuture().get());
                    assertEquals(1,endpoint.creationAttempts());
                    client.setRemoteDescription(answer.replaceAll("(?m)^a=candidate:[^\\r\\n]*\\r?\\n","")
                            .replace("a=end-of-candidates\r\n",""),SessionDescriptionType.ANSWER);
                    NativeDiagnosticHostTest.await(() -> channels[0].isOpen() && channels[1].isOpen());
                    for (int i=0;i<2;i++) channels[i].sendMessage(ByteBuffer.allocateDirect(2).put((byte)0).put((byte)(60+i)).flip());
                    assertEquals(Set.of("ReliableDataChannel:60","UnreliableDataChannel:61"), Set.of(echoes.poll(5,TimeUnit.SECONDS),echoes.poll(5,TimeUnit.SECONDS)));
                    assertTrue(errors.isEmpty(),errors.toString());
                    assertEquals(port,client.selectedCandidatePair().remote().getPort());
                    assertNotNull(child.get());
                    assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
                }
            } finally {
                endpoint.close().awaitUninterruptibly(); endpoint.termination().toCompletableFuture().get(6,TimeUnit.SECONDS);
                assertEquals(0,endpoint.liveNativePeers());
                loop.shutdownGracefully(0,1,TimeUnit.SECONDS).sync();
            }
        }
    }
}
